package gov.itms.audit.api;

import gov.itms.audit.application.AuditService;
import gov.itms.audit.application.AuditStore;
import gov.itms.audit.domain.AuditRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Timelines, search and integrity of {@code audit-service.v1.yaml} (CC-120..CC-122). */
@RestController
class AuditController {

    private final AuditService audit;
    private final Callers callers;

    AuditController(AuditService audit, Callers callers) {
        this.audit = audit;
        this.callers = callers;
    }

    /** Old and new values are JSON text as the owning service recorded them (already masked there, SEC-014). */
    record RecordDto(long seq, UUID auditId, Instant occurredAt, String service, String action, String entityType,
                     String entityId, String actorId, String actorUsername, List<String> actorRoles, String channel,
                     String ipAddress, String correlationId, String oldValue, String newValue, String reason,
                     String hash) {
        static RecordDto of(AuditRecord r) {
            return new RecordDto(r.seq(), r.auditId(), r.occurredAt(), r.service(), r.action(), r.entityType(),
                    r.entityId(), r.actorId(), r.actorUsername(), r.actorRoles(), r.channel(), r.ipAddress(),
                    r.correlationId(), r.oldValue(), r.newValue(), r.reason(), r.hash());
        }
    }

    record Page(List<RecordDto> items, Long nextAfterSeq) {
        static Page of(List<AuditRecord> records, int limit) {
            List<RecordDto> items = records.stream().map(RecordDto::of).toList();
            return new Page(items, items.size() == limit ? items.getLast().seq() : null);
        }
    }

    @GetMapping("/v1/timelines/{entityType}/{entityId}")
    @PreAuthorize("hasAuthority('audit.timeline.read') or hasAuthority('audit.read')")
    Page timeline(@PathVariable String entityType, @PathVariable String entityId,
                  @RequestParam(required = false) Long afterSeq, @RequestParam(defaultValue = "100") int limit) {
        return Page.of(audit.timeline(entityType, entityId, afterSeq, limit, callers.current()), limit);
    }

    @GetMapping("/v1/audit-records")
    @PreAuthorize("hasAuthority('audit.read')")
    Page search(@RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
                @RequestParam(required = false) String actorId, @RequestParam(required = false) String service,
                @RequestParam(required = false) String action, @RequestParam(required = false) Instant from,
                @RequestParam(required = false) Instant to, @RequestParam(required = false) Long afterSeq,
                @RequestParam(defaultValue = "100") int limit) {
        return Page.of(audit.search(new AuditStore.Query(entityType, entityId, actorId, service, action, from, to,
                afterSeq, limit), callers.current()), limit);
    }

    @GetMapping("/v1/audit-integrity")
    @PreAuthorize("hasAuthority('audit.integrity.verify')")
    AuditService.Verification verify(@RequestParam(required = false) Long fromSeq,
                                     @RequestParam(defaultValue = "10000") int limit) {
        return audit.verify(fromSeq, limit, callers.current());
    }
}
