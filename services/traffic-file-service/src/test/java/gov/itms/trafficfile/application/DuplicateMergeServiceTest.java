package gov.itms.trafficfile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.trafficfile.domain.Actor;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Probable duplicates and merges (DL-009). */
@Tag("DL-009")
class DuplicateMergeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate BORN = LocalDate.of(1990, 4, 12);

    private final TrafficFileServiceTest.FakeFiles files = new TrafficFileServiceTest.FakeFiles();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final DuplicateMergeService service = new DuplicateMergeService(files, auditRecorder(), eventRecorder(),
            CLOCK, 0.88);

    private TrafficFile person(String passport, String given, String family, LocalDate born, String nationality) {
        TrafficFile file = TrafficFile.openIndividual(UUID.randomUUID(), files.nextFileNumber(),
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB",
                new Identity(new Identity.Passport(passport, nationality), new PersonName(null, null, given, family),
                        nationality, born, "MALE", null, null), TrafficFile.IdentitySource.DOCUMENT, null, null,
                new Actor("u-0", "seed"), Channel.BACKOFFICE, CLOCK.instant(), LocalDate.of(2026, 10, 6));
        files.insert(file);
        return file;
    }

    private Caller caller(String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor("u-sup", "supervisor"), Channel.BACKOFFICE, new Caller.Grants() {
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

    @Test
    @DisplayName("probable duplicates: same birth date and nationality, similar name; most similar first")
    void probableDuplicates() {
        TrafficFile ahmed = person("AA11111", "Ahmed", "Al-Mansoori", BORN, "ARE");
        TrafficFile close = person("BB22222", "Ahmad", "Al Mansouri", BORN, "ARE");
        TrafficFile exact = person("CC33333", "Ahmed", "Al Mansoori", BORN, "ARE");
        person("DD44444", "Maria", "Santos", BORN, "ARE");
        person("EE55555", "Ahmed", "Al Mansoori", BORN.plusDays(1), "ARE");
        person("FF66666", "Ahmed", "Al Mansoori", BORN, "OMN");

        var candidates = service.probableDuplicates(ahmed.fileNumber(), caller(TrafficFileService.READ));
        assertThat(candidates).extracting(c -> c.file().fileNumber())
                .containsExactly(exact.fileNumber(), close.fileNumber());
        assertThat(candidates.getFirst().nameSimilarity()).isEqualTo(1.0);
        assertThat(audit).containsExactly("TRAFFIC_FILE_DUPLICATES_VIEWED:" + ahmed.fileNumber());
    }

    @Test
    @DisplayName("a merge marks the merged file, records it, audits both files and publishes the event")
    void merge() {
        TrafficFile survivor = person("AA11111", "Ahmed", "Al Mansoori", BORN, "ARE");
        TrafficFile duplicate = person("BB22222", "Ahmad", "Al Mansouri", BORN, "ARE");
        var merge = service.merge(survivor.fileNumber(), duplicate.fileNumber(), "same person, two passports",
                caller(DuplicateMergeService.MERGE));
        TrafficFile merged = files.findByNumber(duplicate.fileNumber()).orElseThrow();
        assertThat(merged.status()).isEqualTo(TrafficFile.Status.MERGED);
        assertThat(merged.survivingFileNumber()).isEqualTo(survivor.fileNumber());
        assertThat(merged.version()).isEqualTo(2);
        assertThat(files.merges).containsExactly(merge);
        assertThat(audit).containsExactly("TRAFFIC_FILE_MERGED:" + duplicate.fileNumber(),
                "TRAFFIC_FILE_MERGE_RECEIVED:" + survivor.fileNumber());
        assertThat(events).containsExactly("itms.traffic-file.merged:" + duplicate.fileNumber());

        assertThatThrownBy(() -> service.merge(survivor.fileNumber(), duplicate.fileNumber(), "again",
                caller(DuplicateMergeService.MERGE))).hasFieldOrPropertyWithValue("code", "FILE_NOT_ACTIVE");
        assertThat(service.probableDuplicates(survivor.fileNumber(), caller(TrafficFileService.READ)))
                .as("a merged file is no longer a candidate").isEmpty();
    }

    @Test
    @DisplayName("merging needs the permission, a reason, two different files of the same kind")
    void mergeRules() {
        TrafficFile a = person("AA11111", "Ahmed", "Al Mansoori", BORN, "ARE");
        TrafficFile b = person("BB22222", "Ahmad", "Al Mansouri", BORN, "ARE");
        assertThatThrownBy(() -> service.merge(a.fileNumber(), b.fileNumber(), "same person",
                caller(TrafficFileService.READ))).hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.merge(a.fileNumber(), b.fileNumber(), " ",
                caller(DuplicateMergeService.MERGE))).hasFieldOrPropertyWithValue("code", "REASON_INVALID");
        assertThatThrownBy(() -> service.merge(a.fileNumber(), a.fileNumber(), "same person",
                caller(DuplicateMergeService.MERGE))).hasFieldOrPropertyWithValue("code", "MERGE_SAME_FILE");
        assertThatThrownBy(() -> service.merge(a.fileNumber(), "99999999990", "same person",
                caller(DuplicateMergeService.MERGE))).hasFieldOrPropertyWithValue("code", "FILE_NOT_FOUND");
        assertThatThrownBy(() -> new DuplicateMergeService(files, auditRecorder(), eventRecorder(), CLOCK, 0))
                .isInstanceOf(IllegalArgumentException.class);
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
