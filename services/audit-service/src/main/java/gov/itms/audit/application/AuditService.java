package gov.itms.audit.application;

import gov.itms.audit.domain.AuditRecord;
import gov.itms.audit.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * The central audit trail (CC-120..CC-122; ADR-053).
 * <ul>
 *   <li>every service's audit records arrive through the broker (topic {@code itms.audit.v1}, from each service's
 *       transactional outbox) and are appended once, in a SHA-256 hash chain (tamper evidence, CC-122);</li>
 *   <li>users with {@value #TIMELINE_READ} see the full history of any record as a timeline (CC-121); auditors with
 *       {@value #READ} search by actor, service, action and time; both reads are themselves audited;</li>
 *   <li>{@value #VERIFY} recomputes the chain and reports the first broken link;</li>
 *   <li>a retention job deletes records older than the configured period (default 8 years: 1 online + 7 archive,
 *       BRD section 18.4), the only deletion the database allows.</li>
 * </ul>
 */
public class AuditService {

    public static final String READ = "audit.read";
    public static final String TIMELINE_READ = "audit.timeline.read";
    public static final String VERIFY = "audit.integrity.verify";
    private static final int MAX_LIMIT = 500;

    /** Outcome of an integrity check. */
    public record Verification(long checked, Long firstSeq, Long lastSeq, boolean intact, Long brokenAtSeq) {
    }

    private final AuditStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public AuditService(AuditStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /** Appends one record unless it is already stored (redelivery). Returns true when appended. */
    @Transactional
    public boolean ingest(AuditRecord record) {
        store.lockChain();
        if (store.exists(record.auditId())) {
            return false;
        }
        AuditRecord last = store.last().orElse(null);
        long seq = last == null ? 1 : last.seq() + 1;
        String prev = last == null ? AuditRecord.GENESIS : last.hash();
        store.append(record.chained(seq, prev, clock.instant()));
        return true;
    }

    /** The history of one record, oldest first (CC-121). */
    @Transactional
    public List<AuditRecord> timeline(String entityType, String entityId, Long afterSeq, int limit, Caller caller) {
        require(caller.grants().holds(TIMELINE_READ) || caller.grants().holds(READ));
        checkLimit(limit);
        List<AuditRecord> found = store.search(new AuditStore.Query(entityType, entityId, null, null, null, null,
                null, afterSeq, limit));
        audit.record("AUDIT_TIMELINE_VIEWED", entityType, entityId, null, Map.of("records", found.size()), null);
        return found;
    }

    /** Auditor search (CC-120); at least one filter besides time is required. */
    @Transactional
    public List<AuditRecord> search(AuditStore.Query query, Caller caller) {
        require(caller.grants().holds(READ));
        checkLimit(query.limit());
        if (query.entityType() == null && query.actorId() == null && query.service() == null
                && query.action() == null) {
            throw DomainException.validation("FILTER_REQUIRED", "give an entity, actor, service or action",
                    List.of("CC-120"));
        }
        if (query.from() != null && query.to() != null && query.to().isBefore(query.from())) {
            throw DomainException.validation("FIELD_INVALID", "to is before from", List.of("CC-120"), "to");
        }
        List<AuditRecord> found = store.search(query);
        audit.record("AUDIT_SEARCHED", "AuditTrail", query.actorId() != null ? "actor:" + query.actorId()
                        : query.entityType() != null ? query.entityType() + ":" + query.entityId()
                        : "service:" + query.service(), null, Map.of("records", found.size()), null);
        return found;
    }

    /** Recomputes up to {@code limit} links of the chain from {@code fromSeq} (CC-122). */
    @Transactional
    public Verification verify(Long fromSeq, int limit, Caller caller) {
        require(caller.grants().holds(VERIFY));
        if (limit < 1 || limit > 100_000) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..100000", List.of("CC-122"), "limit");
        }
        long start = fromSeq != null ? fromSeq : store.first().map(AuditRecord::seq).orElse(1L);
        List<AuditRecord> records = store.chain(Math.max(1, start - 1), limit + 1);
        String expectedPrev = null;
        long checked = 0;
        Long first = null;
        Long lastSeq = null;
        Long broken = null;
        for (AuditRecord r : records) {
            if (r.seq() < start) {
                expectedPrev = r.hash();
                continue;
            }
            boolean linkOk = expectedPrev == null || expectedPrev.equals(r.prevHash());
            boolean hashOk = r.hash().equals(r.computeHash());
            if ((!linkOk || !hashOk) && broken == null) {
                broken = r.seq();
            }
            if (first == null) {
                first = r.seq();
            }
            lastSeq = r.seq();
            expectedPrev = r.hash();
            checked++;
        }
        Verification v = new Verification(checked, first, lastSeq, broken == null, broken);
        audit.record("AUDIT_CHAIN_VERIFIED", "AuditTrail", "chain", null,
                Map.of("checked", checked, "intact", v.intact()), null);
        return v;
    }

    /** Deletes records past retention; run by the scheduled job. */
    @Transactional
    public int purge(Duration retention, int limit) {
        return store.purge(clock.instant().minus(retention), limit);
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1.." + MAX_LIMIT, List.of("CC-121"),
                    "limit");
        }
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not read the audit trail", List.of("CC-121"));
        }
    }
}
