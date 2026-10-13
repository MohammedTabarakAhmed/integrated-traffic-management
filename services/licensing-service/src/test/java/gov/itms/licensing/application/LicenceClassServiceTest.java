package gov.itms.licensing.application;

import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.licensing.domain.Actor;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.LicenceClassVersion;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("DL-020")
class LicenceClassServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    private final Classes classes = new Classes();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final LicenceClassService service = new LicenceClassService(classes, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action + " " + entityId);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, new DomainEventRecorder() {
        @Override
        public void record(String eventType, String subject, Object data) {
            events.add(eventType + " " + subject);
        }

        @Override
        public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
            record(eventType, subject, data);
        }
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    private static final Caller MAKER = caller("u-maker", Channel.BACKOFFICE, LicenceClassService.SUBMIT,
            LicenceClassService.READ);
    private static final Caller CHECKER = caller("u-checker", Channel.BACKOFFICE, LicenceClassService.APPROVE,
            LicenceClassService.READ);
    private static final Caller READER = caller("svc-exam", Channel.BACKOFFICE, LicenceClassService.READ);

    private static Caller caller(String id, Channel channel, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), channel, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        }, "corr-1");
    }

    private static LicenceClassVersion.Spec spec(String code, LocalDate from, int citizenValidity) {
        return new LicenceClassVersion.Spec(code, "مركبة خفيفة", "Light vehicle", "سيارات", "Cars and light vans",
                true, List.of(
                new LicenceClassVersion.CategoryRule(null, 18, null, List.of("THEORY", "YARD", "ROAD"), 20, false, 24,
                        true),
                new LicenceClassVersion.CategoryRule("CITIZEN", 18, null, List.of("THEORY", "YARD", "ROAD"), 20,
                        false, citizenValidity, false)), from, null);
    }

    private LicenceClassVersion approved(String code, LocalDate from, int citizenValidity) {
        LicenceClassVersion v = service.submit(spec(code, from, citizenValidity), "new class", MAKER);
        return service.approve(v.id(), "checked", CHECKER);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (DomainException e) {
            return e.code();
        }
        return null;
    }

    @Test
    @DisplayName("submit, approve by another person, then read the version in force and the category rule")
    void submitApproveRead() {
        LicenceClassVersion v = service.submit(spec("LIGHT", TODAY, 120), "new class", MAKER);
        assertThat(v.status()).isEqualTo(LicenceClassVersion.Status.PENDING);
        assertThat(v.versionNo()).isEqualTo(1);
        assertThat(code(() -> service.inForce("LIGHT", null, READER))).isEqualTo("CLASS_NOT_IN_FORCE");

        service.approve(v.id(), "checked", CHECKER);

        assertThat(service.inForce("LIGHT", null, READER).id()).isEqualTo(v.id());
        assertThat(service.inForce(null, READER)).extracting(LicenceClassVersion::id).containsExactly(v.id());
        assertThat(service.rule("LIGHT", "CITIZEN", null, READER).rule().validityMonths()).isEqualTo(120);
        assertThat(service.rule("LIGHT", "RESIDENT", null, READER).rule().residencyLinked()).isTrue();
        assertThat(audit).containsExactly("LICENCE_CLASS_SUBMITTED " + v.id(), "LICENCE_CLASS_APPROVED " + v.id());
        assertThat(events).containsExactly("itms.licensing.class-approved LIGHT");
    }

    @Test
    @Tag("DL-062")
    @DisplayName("a class without a rule for the category and without a default rule gives CLASS_RULE_NOT_FOUND")
    void noRuleForCategory() {
        LicenceClassVersion v = service.submit(new LicenceClassVersion.Spec("BUS", "حافلة", "Heavy bus", "حافلات",
                "Over 26 passengers", false, List.of(new LicenceClassVersion.CategoryRule("CITIZEN", 21, null,
                List.of("THEORY", "ROAD"), 30, true, 60, false)), TODAY, null), "new class", MAKER);
        service.approve(v.id(), null, CHECKER);
        assertThat(code(() -> service.rule("BUS", "VISITOR", null, READER))).isEqualTo("CLASS_RULE_NOT_FOUND");
    }

    @Test
    @DisplayName("approving a successor ends the earlier version the day before; a later-starting one is an overlap")
    void successorAndOverlap() {
        LicenceClassVersion first = approved("MOTO", TODAY, 120);
        LicenceClassVersion second = approved("MOTO", TODAY.plusDays(10), 60);

        assertThat(classes.find(first.id()).orElseThrow().effectiveTo()).isEqualTo(TODAY.plusDays(9));
        assertThat(service.inForce("MOTO", TODAY.plusDays(9), READER).id()).isEqualTo(first.id());
        assertThat(service.inForce("MOTO", TODAY.plusDays(10), READER).id()).isEqualTo(second.id());
        assertThat(audit).contains("LICENCE_CLASS_ENDED " + first.id());

        LicenceClassVersion earlier = service.submit(spec("MOTO", TODAY.plusDays(5), 90), "in between", MAKER);
        assertThat(code(() -> service.approve(earlier.id(), null, CHECKER))).isEqualTo("CLASS_VERSION_OVERLAP");
    }

    @Test
    @Tag("CC-002")
    @DisplayName("the maker cannot approve; the maker may withdraw; others need the approve permission to reject")
    void segregationAndWithdrawal() {
        Caller makerChecker = caller("u-maker", Channel.BACKOFFICE, LicenceClassService.SUBMIT,
                LicenceClassService.APPROVE);
        LicenceClassVersion v = service.submit(spec("TRACTOR", TODAY, 120), "new class", MAKER);
        assertThat(code(() -> service.approve(v.id(), null, makerChecker))).isEqualTo("SAME_MAKER_CHECKER");
        assertThat(code(() -> service.reject(v.id(), "not mine", READER))).isEqualTo("SCOPE_EXCEEDED");

        service.reject(v.id(), "submitted by mistake", MAKER);
        assertThat(audit).contains("LICENCE_CLASS_WITHDRAWN " + v.id());
        assertThat(code(() -> service.approve(v.id(), null, CHECKER))).isEqualTo("CLASS_VERSION_DECIDED");
    }

    @Test
    @Tag("CC-001")
    @DisplayName("every operation needs its permission and a known channel")
    void permissionsAndChannel() {
        assertThat(code(() -> service.submit(spec("LIGHT", TODAY, 120), "new class", READER)))
                .isEqualTo("SCOPE_EXCEEDED");
        assertThat(code(() -> service.submit(spec("LIGHT", TODAY, 120), "new class",
                caller("u-maker", null, LicenceClassService.SUBMIT)))).isEqualTo("CHANNEL_REQUIRED");
        LicenceClassVersion v = service.submit(spec("LIGHT", TODAY, 120), "new class", MAKER);
        assertThat(code(() -> service.approve(v.id(), null, MAKER))).isEqualTo("SCOPE_EXCEEDED");
        assertThat(code(() -> service.get(v.id(), caller("x", Channel.PORTAL)))).isEqualTo("SCOPE_EXCEEDED");
        assertThat(code(() -> service.get(UUID.randomUUID(), READER))).isEqualTo("CLASS_VERSION_NOT_FOUND");
        assertThat(service.versions("LIGHT", READER)).hasSize(1);
    }

    /** In-memory repository with the same semantics as the JDBC one. */
    static final class Classes implements LicenceClassRepository {

        private final Map<UUID, LicenceClassVersion> rows = new LinkedHashMap<>();

        @Override
        public int nextVersionNo(String classCode) {
            return (int) rows.values().stream().filter(v -> v.spec().classCode().equals(classCode)).count() + 1;
        }

        @Override
        public void lockClass(String classCode) {
        }

        @Override
        public void insert(LicenceClassVersion version) {
            rows.put(version.id(), version);
        }

        @Override
        public Optional<LicenceClassVersion> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<LicenceClassVersion> findForUpdate(UUID id) {
            return find(id);
        }

        @Override
        public List<LicenceClassVersion> byClass(String classCode) {
            return rows.values().stream().filter(v -> v.spec().classCode().equals(classCode))
                    .sorted(Comparator.comparingInt(LicenceClassVersion::versionNo)).toList();
        }

        @Override
        public List<LicenceClassVersion> approved() {
            return rows.values().stream().filter(v -> v.status() == LicenceClassVersion.Status.APPROVED)
                    .sorted(Comparator.comparing((LicenceClassVersion v) -> v.spec().classCode())
                            .thenComparingInt(LicenceClassVersion::versionNo)).toList();
        }

        @Override
        public void saveDecision(LicenceClassVersion version) {
        }

        @Override
        public void saveEnd(LicenceClassVersion version) {
        }
    }
}
