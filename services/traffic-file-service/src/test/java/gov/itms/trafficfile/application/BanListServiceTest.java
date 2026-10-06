package gov.itms.trafficfile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.trafficfile.domain.Actor;
import gov.itms.trafficfile.domain.BanEntry;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.PersonName;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Use cases of {@link BanListService}, and the ban check when opening a file (DL-013, Q-059). */
@Tag("DL-013")
class BanListServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);
    private static final Identity.Passport PASSPORT = new Identity.Passport("AB12345", "GBR");

    private final FakeBans bans = new FakeBans();
    private final TrafficFileServiceTest.FakeFiles files = new TrafficFileServiceTest.FakeFiles();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final BanListService service = new BanListService(bans, files, auditRecorder(), eventRecorder(), CLOCK);

    private Caller caller(String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor("u-1", "officer"), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    private Caller officer() {
        return caller(BanListService.PLACE, BanListService.LIFT, BanListService.READ, TrafficFileService.CREATE);
    }

    private static BanEntry.Spec spec(String sourceRef) {
        return new BanEntry.Spec(PASSPORT, "court order", "COURT", sourceRef, null, null);
    }

    @Test
    @DisplayName("placing links the person's file, audits and publishes without the identity number")
    void place() {
        TrafficFile file = TrafficFile.openIndividual(UUID.randomUUID(), files.nextFileNumber(),
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB",
                new Identity(PASSPORT, new PersonName(null, null, "A", "B"), "GBR", LocalDate.of(1980, 1, 1),
                        "MALE", null, null), TrafficFile.IdentitySource.DOCUMENT, null, null,
                new Actor("u-0", "seed"), Channel.BACKOFFICE, CLOCK.instant(), LocalDate.of(2026, 10, 6));
        files.insert(file);
        BanEntry ban = service.place(spec("CO-1"), officer());
        assertThat(ban.fileNumber()).isEqualTo(file.fileNumber());
        assertThat(audit).containsExactly("BAN_PLACED:" + ban.id());
        assertThat(events).containsExactly("itms.ban.placed:" + ban.id());
        assertThatThrownBy(() -> service.place(spec("CO-1"), officer()))
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_SOURCE_REF");
    }

    @Test
    @DisplayName("lifting, reading and searching need their own permissions and are audited")
    void liftReadSearch() {
        BanEntry ban = service.place(spec(null), officer());
        assertThatThrownBy(() -> service.lift(ban.id(), "order revoked", caller(BanListService.READ)))
                .hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
        audit.clear();
        BanEntry lifted = service.lift(ban.id(), "order revoked", officer());
        assertThat(lifted.status(LocalDate.of(2026, 10, 6))).isEqualTo(BanEntry.Status.LIFTED);
        assertThat(service.get(ban.id(), caller(BanListService.READ)).id()).isEqualTo(ban.id());
        assertThat(service.byDocument(PASSPORT, caller(BanListService.READ))).hasSize(1);
        assertThat(service.byDocument(new Identity.Passport("ZZ99999", "GBR"), caller(BanListService.READ)))
                .isEmpty();
        assertThat(audit).containsExactly("BAN_LIFTED:" + ban.id(), "BAN_VIEWED:" + ban.id(),
                "BAN_LIST_SEARCHED:" + ban.id(), "BAN_LIST_SEARCHED:-");
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), caller(BanListService.READ)))
                .hasFieldOrPropertyWithValue("code", "BAN_NOT_FOUND");
        assertThatThrownBy(() -> service.get(ban.id(), caller()))
                .hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
    }

    @Test
    @DisplayName("a person with a ban in force cannot be given a traffic file; once lifted they can (Q-059)")
    void bannedPersonCannotOpenAFile() {
        BanEntry ban = service.place(spec(null), officer());
        assertThat(service.barred(PASSPORT)).isTrue();
        TrafficFileService files = new TrafficFileService(this.files, bans, nationalId -> java.util.Optional.empty(),
                auditRecorder(), eventRecorder(), CLOCK, Pattern.compile("^[0-9]{6,20}$"));
        TrafficFileService.OpenIndividual open = new TrafficFileService.OpenIndividual(PASSPORT,
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB",
                new TrafficFileService.VisitorIdentity(new PersonName(null, null, "A", "B"), "GBR",
                        LocalDate.of(1980, 1, 1), "MALE"), null);
        assertThatThrownBy(() -> files.openIndividual(open, officer()))
                .hasFieldOrPropertyWithValue("code", "PERSON_BANNED");
        service.lift(ban.id(), "order revoked", officer());
        assertThat(service.barred(PASSPORT)).isFalse();
        assertThat(files.openIndividual(open, officer()).fileNumber()).isNotBlank();
    }

    private AuditRecorder auditRecorder() {
        return new AuditRecorder() {
            @Override
            public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                               String reason) {
                audit.add(action + ":" + entityId);
            }

            @Override
            public void recordAs(CurrentActor actor, String action, String entityType, String entityId,
                                 Object oldValue, Object newValue, String reason) {
                audit.add(action + ":" + entityId);
            }
        };
    }

    private DomainEventRecorder eventRecorder() {
        return new DomainEventRecorder() {
            @Override
            public void record(String eventType, String subject, Object data) {
                events.add(eventType + ":" + subject);
            }

            @Override
            public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
                events.add(eventType + ":" + subject);
            }
        };
    }
}
