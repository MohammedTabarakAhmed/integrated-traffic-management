package gov.itms.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.audit.domain.Actor;
import gov.itms.audit.domain.AuditRecord;
import gov.itms.audit.domain.AuditRecordTest;
import gov.itms.audit.domain.Channel;
import gov.itms.audit.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-120")
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    private final Store store = new Store();
    private final List<String> audit = new ArrayList<>();
    private final AuditService service = new AuditService(store, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action + " " + entityType + ":" + entityId);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Caller caller(String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor("u-auditor", "auditor"), Channel.BACKOFFICE, new Caller.Grants() {
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

    private static final Caller AUDITOR = caller(AuditService.READ, AuditService.VERIFY);

    @Test
    @Tag("CC-122")
    @DisplayName("CC-122: a record is ingested once and chained to the previous one from the genesis hash")
    void ingestOnceAndChain() {
        AuditRecord first = AuditRecordTest.record(UUID.randomUUID(), "VIN-1");
        assertThat(service.ingest(first)).isTrue();
        assertThat(service.ingest(first)).as("redelivery").isFalse();
        assertThat(service.ingest(AuditRecordTest.record(UUID.randomUUID(), "VIN-2"))).isTrue();
        assertThat(store.records).extracting(AuditRecord::seq).containsExactly(1L, 2L);
        assertThat(store.records.get(0).prevHash()).isEqualTo(AuditRecord.GENESIS);
        assertThat(store.records.get(1).prevHash()).isEqualTo(store.records.get(0).hash());
        assertThat(store.records.get(0).receivedAt()).isEqualTo(NOW);
    }

    @Test
    @Tag("CC-122")
    @DisplayName("CC-122: verification passes an intact chain and names the first tampered or relinked record")
    void verifyFindsTampering() {
        for (int i = 0; i < 5; i++) {
            service.ingest(AuditRecordTest.record(UUID.randomUUID(), "VIN-" + i));
        }
        AuditService.Verification ok = service.verify(null, 100, AUDITOR);
        assertThat(ok.intact()).isTrue();
        assertThat(ok.checked()).isEqualTo(5);
        assertThat(ok.firstSeq()).isEqualTo(1);
        assertThat(ok.lastSeq()).isEqualTo(5);

        AuditRecord third = store.records.get(2);
        store.records.set(2, new AuditRecord(third.seq(), third.auditId(), third.occurredAt(), third.service(),
                third.action(), third.entityType(), "VIN-CHANGED", third.actorId(), third.actorUsername(),
                third.actorRoles(), third.channel(), third.ipAddress(), third.correlationId(), third.oldValue(),
                third.newValue(), third.reason(), third.receivedAt(), third.prevHash(), third.hash()));
        AuditService.Verification broken = service.verify(null, 100, AUDITOR);
        assertThat(broken.intact()).isFalse();
        assertThat(broken.brokenAtSeq()).isEqualTo(3);
        assertThat(service.verify(4L, 100, AUDITOR).intact()).as("a range after the change still links").isTrue();

        // Removing a record breaks the link of its successor.
        store.records.set(2, third);
        store.records.remove(3);
        assertThat(service.verify(null, 100, AUDITOR).brokenAtSeq()).isEqualTo(5);
        assertThat(audit).contains("AUDIT_CHAIN_VERIFIED AuditTrail:chain");
        assertThatThrownBy(() -> service.verify(null, 0, AUDITOR)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("FIELD_INVALID");
    }

    @Test
    @Tag("CC-121")
    @DisplayName("CC-120, CC-121: search needs a filter besides time; timelines and searches are themselves audited")
    void filterRequiredAndReadsAudited() {
        service.ingest(AuditRecordTest.record(UUID.randomUUID(), "VIN-1"));
        assertThatThrownBy(() -> service.search(new AuditStore.Query(null, null, null, null, null, NOW, null, null,
                10), AUDITOR)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FILTER_REQUIRED");
        assertThatThrownBy(() -> service.search(new AuditStore.Query(null, null, "u-officer", null, null, NOW,
                NOW.minusSeconds(1), null, 10), AUDITOR)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
        assertThat(service.search(new AuditStore.Query(null, null, "u-officer", null, null, null, null, null, 10),
                AUDITOR)).hasSize(1);
        assertThat(service.timeline("Vehicle", "VIN-1", null, 10, caller(AuditService.TIMELINE_READ))).hasSize(1);
        assertThat(audit).contains("AUDIT_SEARCHED AuditTrail:actor:u-officer", "AUDIT_TIMELINE_VIEWED Vehicle:VIN-1");
        assertThatThrownBy(() -> service.timeline("Vehicle", "VIN-1", null, 10, caller()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.search(new AuditStore.Query("Vehicle", "VIN-1", null, null, null, null, null,
                null, 10), caller(AuditService.TIMELINE_READ))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.timeline("Vehicle", "VIN-1", null, 501, AUDITOR))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }

    @Test
    @Tag("CC-122")
    @DisplayName("CC-122: purge passes 'now minus retention' as the cut-off")
    void purgeCutOff() {
        service.purge(Duration.ofDays(2922), 100);
        assertThat(store.purgedBefore).isEqualTo(NOW.minus(Duration.ofDays(2922)));
    }

    private static final class Store implements AuditStore {
        final List<AuditRecord> records = new ArrayList<>();
        Instant purgedBefore;

        @Override
        public void lockChain() {
        }

        @Override
        public boolean exists(UUID auditId) {
            return records.stream().anyMatch(r -> r.auditId().equals(auditId));
        }

        @Override
        public Optional<AuditRecord> last() {
            return records.isEmpty() ? Optional.empty() : Optional.of(records.getLast());
        }

        @Override
        public void append(AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditRecord> search(Query q) {
            return records.stream()
                    .filter(r -> q.entityType() == null || q.entityType().equals(r.entityType()))
                    .filter(r -> q.entityId() == null || q.entityId().equals(r.entityId()))
                    .filter(r -> q.actorId() == null || q.actorId().equals(r.actorId()))
                    .filter(r -> q.afterSeq() == null || r.seq() > q.afterSeq())
                    .limit(q.limit()).toList();
        }

        @Override
        public List<AuditRecord> chain(long fromSeq, int limit) {
            return records.stream().filter(r -> r.seq() >= fromSeq).limit(limit).toList();
        }

        @Override
        public Optional<AuditRecord> first() {
            return records.isEmpty() ? Optional.empty() : Optional.of(records.getFirst());
        }

        @Override
        public int purge(Instant before, int limit) {
            purgedBefore = before;
            return 0;
        }
    }
}
