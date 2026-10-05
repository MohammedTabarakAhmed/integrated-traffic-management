package gov.itms.hold.application;

import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.hold.domain.Actor;
import gov.itms.hold.domain.Channel;
import gov.itms.hold.domain.DomainException;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.HoldOverride;
import gov.itms.hold.domain.HoldStatus;
import gov.itms.hold.domain.Subject;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-030")
class HoldServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final Actor OFFICER = new Actor("u-officer", "officer");
    private static final Subject VEHICLE = new Subject(Subject.Type.VEHICLE, "VIN-1");

    private final Holds holds = new Holds();
    private final List<String> audit = new ArrayList<>();
    private final HoldService service = new HoldService(holds, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action + " " + entityType + " " + entityId);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    /** A caller whose grants hold {@code permissions} in {@code modules} through {@code role}. */
    private static Caller caller(String role, Set<String> permissions, Set<String> modules, Channel channel) {
        return new Caller(OFFICER, channel, new Caller.Grants() {
            @Override
            public boolean allows(String permission, String module) {
                return permissions.contains(permission) && (module == null || modules.contains(module));
            }

            @Override
            public boolean allowsThroughRole(String permission, List<String> roles, String module) {
                return roles.contains(role) && allows(permission, module);
            }
        });
    }

    private static final Caller OFFICER_VIOLATIONS = caller("HOLD_OFFICER",
            Set.of(HoldService.PLACE, HoldService.LIFT, HoldService.READ), Set.of("VIOLATIONS"), Channel.BACKOFFICE);

    private static Hold.Spec spec(String module) {
        return new Hold.Spec(VEHICLE, "UNPAID_FINES", module, "VIOLATIONS", null, "unpaid fine", null, null,
                List.of("*"), List.of("HOLD_OFFICER"));
    }

    private static String code(Supplier<?> action) {
        try {
            action.get();
        } catch (DomainException e) {
            return e.code();
        }
        return "none";
    }

    @Test
    void placeReadListAndLiftAreScopedAndAudited() {
        Hold placed = service.place(spec("VIOLATIONS"), OFFICER_VIOLATIONS);
        assertThat(service.get(placed.id(), OFFICER_VIOLATIONS).id()).isEqualTo(placed.id());
        assertThat(service.bySubject(VEHICLE, OFFICER_VIOLATIONS)).hasSize(1);
        Hold lifted = service.lift(placed.id(), "fines paid", OFFICER_VIOLATIONS);
        assertThat(lifted.status(service.today())).isEqualTo(HoldStatus.LIFTED);
        assertThat(service.history(placed.id(), OFFICER_VIOLATIONS)).hasSize(2);
        String id = placed.id().toString();
        assertThat(audit).containsExactly("HOLD_PLACED Hold " + id, "HOLD_VIEWED Hold " + id,
                "HOLDS_LISTED SubjectHolds VEHICLE:VIN-1", "HOLD_LIFTED Hold " + id, "HOLD_HISTORY_VIEWED Hold " + id);
    }

    @Test
    void outsideTheCallersModulesAHoldDoesNotExist() {
        Hold placed = service.place(spec("VIOLATIONS"), OFFICER_VIOLATIONS);
        Caller licensing = caller("HOLD_OFFICER", Set.of(HoldService.PLACE, HoldService.LIFT, HoldService.READ),
                Set.of("LICENSING"), Channel.BACKOFFICE);
        assertThat(code(() -> service.place(spec("VIOLATIONS"), licensing))).isEqualTo("SCOPE_EXCEEDED");
        assertThat(code(() -> service.get(placed.id(), licensing))).isEqualTo("HOLD_NOT_FOUND");
        assertThat(code(() -> service.history(placed.id(), licensing))).isEqualTo("HOLD_NOT_FOUND");
        assertThat(code(() -> service.lift(placed.id(), "paid", licensing))).isEqualTo("HOLD_NOT_FOUND");
        assertThat(service.bySubject(VEHICLE, licensing)).isEmpty();
        assertThat(code(() -> service.get(UUID.randomUUID(), OFFICER_VIOLATIONS))).isEqualTo("HOLD_NOT_FOUND");
    }

    @Test
    void onlyTheHoldsRolesMayLiftIt() {
        Hold placed = service.place(spec("VIOLATIONS"), OFFICER_VIOLATIONS);
        Caller otherRole = caller("COURT_LIAISON", Set.of(HoldService.LIFT, HoldService.READ), Set.of("VIOLATIONS"),
                Channel.BACKOFFICE);
        assertThat(code(() -> service.lift(placed.id(), "paid", otherRole))).isEqualTo("LIFT_NOT_ALLOWED");
        Caller liftOnly = caller("HOLD_OFFICER", Set.of(HoldService.LIFT), Set.of("VIOLATIONS"), Channel.BACKOFFICE);
        assertThat(service.lift(placed.id(), "paid", liftOnly).storedStatus()).isEqualTo(HoldStatus.LIFTED);
        assertThat(code(() -> service.lift(placed.id(), "paid", liftOnly))).isEqualTo("HOLD_NOT_ACTIVE");
    }

    private static final HoldOverride.Document RULING = new HoldOverride.Document("COURT_RULING", "CR-1");
    private static final String REF = "VL_RENEW-R1-2026-0000001";

    private static Caller supervisor(String userId, Set<String> modules) {
        Caller base = caller("HOLD_SUPERVISOR", Set.of(HoldService.OVERRIDE, HoldService.READ), modules,
                Channel.BACKOFFICE);
        return new Caller(new Actor(userId, userId), base.channel(), base.grants());
    }

    @Test
    @Tag("CC-034")
    void overrideNeedsASupervisorOfTheModuleOtherThanThePlacerAndIsAudited() {
        Hold placed = service.place(spec("VIOLATIONS"), OFFICER_VIOLATIONS);
        audit.clear();
        assertThat(code(() -> service.override(placed.id(), REF, "court ruling", RULING, OFFICER_VIOLATIONS)))
                .as("hold.place/lift/read are not hold.override").isEqualTo("OVERRIDE_NOT_ALLOWED");
        assertThat(code(() -> service.override(placed.id(), REF, "court ruling", RULING,
                supervisor("u-super", Set.of("LICENSING"))))).isEqualTo("HOLD_NOT_FOUND");
        assertThat(code(() -> service.override(placed.id(), REF, "court ruling", RULING,
                supervisor(OFFICER.id(), Set.of("VIOLATIONS"))))).isEqualTo("OVERRIDE_BY_PLACER");
        Caller overrideOnly = new Caller(new Actor("u-super", "super"), Channel.BACKOFFICE,
                caller("HOLD_SUPERVISOR", Set.of(HoldService.OVERRIDE), Set.of("VIOLATIONS"), null).grants());
        HoldOverride made = service.override(placed.id(), REF, "court ruling", RULING, overrideOnly);
        assertThat(made.serviceCode()).isEqualTo("VL_RENEW");
        assertThat(made.actor().id()).isEqualTo("u-super");
        assertThat(holds.overriddenFor(REF)).containsExactly(placed.id());
        assertThat(placed.storedStatus()).as("the hold itself does not change").isEqualTo(HoldStatus.ACTIVE);
        assertThat(service.overrides(placed.id(), OFFICER_VIOLATIONS)).containsExactly(made);
        assertThat(code(() -> service.overrides(placed.id(), overrideOnly))).as("listing needs hold.read")
                .isEqualTo("HOLD_NOT_FOUND");
        String id = placed.id().toString();
        assertThat(audit).containsExactly("HOLD_OVERRIDDEN Hold " + id, "HOLD_OVERRIDES_VIEWED Hold " + id);
        Caller noChannel = new Caller(new Actor("u-super", "super"), null, overrideOnly.grants());
        assertThat(code(() -> service.override(placed.id(), REF, "court ruling", RULING, noChannel)))
                .isEqualTo("CHANNEL_REQUIRED");
    }

    @Test
    void aChannelIsRequired() {
        Caller noChannel = caller("HOLD_OFFICER", Set.of(HoldService.PLACE, HoldService.LIFT), Set.of("VIOLATIONS"),
                null);
        assertThat(code(() -> service.place(spec("VIOLATIONS"), noChannel))).isEqualTo("CHANNEL_REQUIRED");
        Hold placed = service.place(spec("VIOLATIONS"), OFFICER_VIOLATIONS);
        assertThat(code(() -> service.lift(placed.id(), "paid", noChannel))).isEqualTo("CHANNEL_REQUIRED");
        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    /** In-memory {@link HoldRepository}; keeps a copy of every version like the database trigger. */
    static final class Holds implements HoldRepository {
        final Map<UUID, Hold> rows = new LinkedHashMap<>();
        final List<HoldVersion> versions = new ArrayList<>();
        final List<HoldOverride> overrides = new ArrayList<>();

        @Override
        public void insert(Hold hold) {
            rows.put(hold.id(), hold);
            versions.add(new HoldVersion(hold, hold.placed().at(), null, hold.placed().actor().id()));
        }

        @Override
        public Optional<Hold> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Hold> findForUpdate(UUID id) {
            return find(id);
        }

        @Override
        public List<Hold> findBySubject(Subject subject) {
            return rows.values().stream().filter(h -> h.spec().subject().equals(subject))
                    .sorted(Comparator.comparing((Hold h) -> h.placed().at()).reversed()).toList();
        }

        @Override
        public void saveLift(Hold hold) {
            versions.add(new HoldVersion(hold, hold.lifted().at(), null, hold.lifted().actor().id()));
        }

        @Override
        public void insertOverride(HoldOverride override) {
            if (overrides.stream().anyMatch(o -> o.holdId().equals(override.holdId())
                    && o.transactionReference().equals(override.transactionReference()))) {
                throw new DomainException(DomainException.Kind.CONFLICT, "OVERRIDE_EXISTS", "exists",
                        List.of("CC-034"));
            }
            overrides.add(override);
        }

        @Override
        public List<HoldOverride> findOverrides(UUID holdId) {
            return overrides.stream().filter(o -> o.holdId().equals(holdId)).toList();
        }

        @Override
        public Set<UUID> overriddenFor(String transactionReference) {
            Set<UUID> ids = new LinkedHashSet<>();
            overrides.stream().filter(o -> o.transactionReference().equals(transactionReference))
                    .forEach(o -> ids.add(o.holdId()));
            return ids;
        }

        @Override
        public List<HoldVersion> history(UUID id) {
            return versions.stream().filter(v -> v.hold().id().equals(id)).toList();
        }
    }
}
