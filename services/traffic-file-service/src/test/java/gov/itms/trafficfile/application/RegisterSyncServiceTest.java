package gov.itms.trafficfile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.platform.security.CurrentActor;
import gov.itms.trafficfile.application.IdentityRegister.RegisterPerson;
import gov.itms.trafficfile.application.RegisterSyncService.ChangeType;
import gov.itms.trafficfile.application.RegisterSyncService.Notification;
import gov.itms.trafficfile.application.RegisterSyncService.Outcome;
import gov.itms.trafficfile.domain.Actor;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.PersonName;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Identity register change notifications (DL-010). */
@Tag("DL-010")
class RegisterSyncServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);
    private static final String ID = "784198812345672";
    private static final PersonName MARIA = new PersonName("ماريا", "سانتوس", "Maria", "Santos");

    private final TrafficFileServiceTest.FakeFiles files = new TrafficFileServiceTest.FakeFiles();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private Optional<RegisterPerson> person = Optional.of(new RegisterPerson(ID, MARIA, "PHL",
            LocalDate.of(1988, 9, 3), "FEMALE", "RESIDENT", LocalDate.of(2028, 1, 31), false));
    private boolean unavailable;
    private RegisterSyncService service;
    private TrafficFile file;

    @BeforeEach
    void setUp() {
        ProcessedEvents processed = new ProcessedEvents(null, CLOCK) {
            @Override
            public boolean firstDelivery(String consumer, String eventId) {
                return seen.add(consumer + "/" + eventId);
            }
        };
        IdentityRegister register = id -> {
            if (unavailable) {
                throw new IdentityRegister.RegisterUnavailableException("down", null);
            }
            return person;
        };
        service = new RegisterSyncService(files, register, processed, auditRecorder(), eventRecorder(), CLOCK,
                Pattern.compile("^[0-9]{6,20}$"));
        file = TrafficFile.openIndividual(UUID.randomUUID(), files.nextFileNumber(),
                new TrafficFile.Classification("INDIVIDUAL", "RESIDENT"), "DXB",
                new Identity(new Identity.NationalId(ID), MARIA, "PHL", LocalDate.of(1988, 9, 3), "FEMALE",
                        "RESIDENT", LocalDate.of(2028, 1, 31)),
                TrafficFile.IdentitySource.REGISTER, CLOCK.instant().minusSeconds(86_400), null,
                new Actor("u-0", "seed"), Channel.BACKOFFICE, CLOCK.instant().minusSeconds(86_400),
                LocalDate.of(2026, 10, 5));
        files.insert(file);
    }

    private Caller feed(String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor("svc-register", "register-adapter"), Channel.PARTNER, new Caller.Grants() {
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

    private static Notification notice(String eventId, ChangeType type) {
        return new Notification(eventId, ID, type, LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("a residency change is re-read from the register and applied once")
    void residencyChange() {
        person = Optional.of(new RegisterPerson(ID, MARIA, "PHL", LocalDate.of(1988, 9, 3), "FEMALE", "RESIDENT",
                LocalDate.of(2030, 1, 31), false));
        var result = service.notify(notice("ev-1", ChangeType.RESIDENCY), feed(RegisterSyncService.NOTIFY));
        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        TrafficFile updated = files.findByNumber(file.fileNumber()).orElseThrow();
        assertThat(updated.identity().residencyExpiry()).isEqualTo(LocalDate.of(2030, 1, 31));
        assertThat(updated.registerSyncedAt()).isEqualTo(CLOCK.instant());
        assertThat(updated.version()).isEqualTo(2);
        assertThat(events).containsExactly("itms.traffic-file.identity-updated:" + file.fileNumber());

        assertThat(service.notify(notice("ev-1", ChangeType.RESIDENCY), feed(RegisterSyncService.NOTIFY)).outcome())
                .isEqualTo(Outcome.DUPLICATE);
        assertThat(files.findByNumber(file.fileNumber()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    @DisplayName("identical data is recorded as a sync without an event; a death makes the file DECEASED")
    void unchangedAndDeath() {
        assertThat(service.notify(notice("ev-2", ChangeType.NAME), feed(RegisterSyncService.NOTIFY)).outcome())
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(events).isEmpty();
        person = Optional.of(new RegisterPerson(ID, MARIA, "PHL", LocalDate.of(1988, 9, 3), "FEMALE", "RESIDENT",
                LocalDate.of(2028, 1, 31), true));
        assertThat(service.notify(notice("ev-3", ChangeType.DEATH), feed(RegisterSyncService.NOTIFY)).outcome())
                .isEqualTo(Outcome.APPLIED);
        assertThat(files.findByNumber(file.fileNumber()).orElseThrow().status())
                .isEqualTo(TrafficFile.Status.DECEASED);
        assertThat(events).containsExactly("itms.traffic-file.identity-updated:" + file.fileNumber(),
                "itms.traffic-file.deceased:" + file.fileNumber());
        assertThat(service.notify(notice("ev-4", ChangeType.NAME), feed(RegisterSyncService.NOTIFY)).outcome())
                .isEqualTo(Outcome.FILE_NOT_ACTIVE);
    }

    @Test
    @DisplayName("no file, unknown in the register, register down, wrong caller, malformed notice")
    void otherOutcomes() {
        assertThat(service.notify(new Notification("ev-5", "123456789", ChangeType.NAME, null),
                feed(RegisterSyncService.NOTIFY)).outcome()).isEqualTo(Outcome.NO_FILE);
        person = Optional.empty();
        assertThat(service.notify(notice("ev-6", ChangeType.NAME), feed(RegisterSyncService.NOTIFY)).outcome())
                .isEqualTo(Outcome.NOT_IN_REGISTER);
        unavailable = true;
        assertThatThrownBy(() -> service.notify(notice("ev-7", ChangeType.NAME), feed(RegisterSyncService.NOTIFY)))
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAVAILABLE);
        assertThatThrownBy(() -> service.notify(notice("ev-8", ChangeType.NAME), feed(TrafficFileService.READ)))
                .hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.notify(new Notification("bad id!", ID, ChangeType.NAME, null),
                feed(RegisterSyncService.NOTIFY))).hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
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
