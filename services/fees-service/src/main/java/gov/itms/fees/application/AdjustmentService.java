package gov.itms.fees.application;

import gov.itms.fees.domain.Channel;
import gov.itms.fees.domain.DomainException;
import gov.itms.fees.domain.FeeAdjustment;
import gov.itms.fees.domain.FeeVersion;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exemptions, discounts and levies (CC-043, CC-045; ADR-047), under the same maker-checker and permissions as fee
 * versions (CC-046): submitted PENDING, decided once by someone else, never starting in the past. An adjustment code
 * is used once per service. Every submission and decision is audited (CC-120).
 */
public class AdjustmentService {

    static final String ENTITY = "FeeAdjustment";

    private final FeeRepository fees;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public AdjustmentService(FeeRepository fees, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.fees = fees;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public FeeAdjustment submit(FeeAdjustment.Spec spec, String reason, Caller caller) {
        Channel channel = FeeService.channelOf(caller);
        FeeService.require(caller, FeeService.SUBMIT);
        fees.lockFee("adjustment:" + spec.serviceCode());
        boolean taken = fees.adjustments(spec.serviceCode(), null).stream()
                .anyMatch(a -> a.spec().code().equals(spec.code()) && a.status() != FeeVersion.Status.REJECTED);
        if (taken) {
            throw DomainException.conflict("ADJUSTMENT_CODE_TAKEN",
                    "adjustment " + spec.code() + " already exists for this service", List.of("CC-043"),
                    spec.code());
        }
        Instant now = clock.instant();
        FeeAdjustment adjustment = FeeAdjustment.submit(UUID.randomUUID(), spec, reason, caller.actor(), channel, now,
                LocalDate.ofInstant(now, clock.getZone()));
        fees.insertAdjustment(adjustment);
        audit.record("FEE_ADJUSTMENT_SUBMITTED", ENTITY, adjustment.id().toString(), null, snapshot(adjustment),
                adjustment.submitted().reason());
        return adjustment;
    }

    @Transactional
    public FeeAdjustment approve(UUID id, String reason, Caller caller) {
        Channel channel = FeeService.channelOf(caller);
        FeeService.require(caller, FeeService.APPROVE);
        FeeAdjustment adjustment = fees.findAdjustmentForUpdate(id).orElseThrow(() -> notFound(id));
        Instant now = clock.instant();
        adjustment.approve(caller.actor(), channel, reason, now, LocalDate.ofInstant(now, clock.getZone()));
        fees.saveAdjustmentDecision(adjustment);
        audit.record("FEE_ADJUSTMENT_APPROVED", ENTITY, id.toString(), null, snapshot(adjustment), reason);
        events.record("itms.fee.adjustment-approved", adjustment.spec().serviceCode(), snapshot(adjustment));
        return adjustment;
    }

    @Transactional
    public FeeAdjustment reject(UUID id, String reason, Caller caller) {
        Channel channel = FeeService.channelOf(caller);
        FeeAdjustment found = fees.findAdjustment(id).orElseThrow(() -> notFound(id));
        boolean withdrawal = found.submitted().actor().id().equals(caller.actor().id());
        if (!withdrawal) {
            FeeService.require(caller, FeeService.APPROVE);
        }
        FeeAdjustment adjustment = fees.findAdjustmentForUpdate(id).orElseThrow(() -> notFound(id));
        adjustment.reject(caller.actor(), channel, reason, clock.instant());
        fees.saveAdjustmentDecision(adjustment);
        audit.record(withdrawal ? "FEE_ADJUSTMENT_WITHDRAWN" : "FEE_ADJUSTMENT_REJECTED", ENTITY, id.toString(), null,
                snapshot(adjustment), adjustment.decided().reason());
        return adjustment;
    }

    @Transactional(readOnly = true)
    public FeeAdjustment get(UUID id, Caller caller) {
        FeeService.require(caller, FeeService.READ);
        return fees.findAdjustment(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public List<FeeAdjustment> byService(String serviceCode, FeeVersion.Status status, Caller caller) {
        FeeService.require(caller, FeeService.READ);
        return fees.adjustments(serviceCode, status);
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "FEE_ADJUSTMENT_NOT_FOUND",
                "fee adjustment " + id + " does not exist", List.of("CC-043"));
    }

    private static Map<String, Object> snapshot(FeeAdjustment a) {
        FeeAdjustment.Spec s = a.spec();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.code());
        m.put("kind", s.kind().name());
        m.put("serviceCode", s.serviceCode());
        m.put("feeCodes", s.feeCodes());
        m.put("customerCategory", s.customerCategory());
        m.put("campaignCode", s.campaignCode());
        m.put("percent", s.percent() == null ? null : s.percent().toPlainString());
        m.put("amount", s.amount());
        m.put("financialItem", s.financialItem());
        m.put("beneficiary", s.beneficiary());
        m.put("effectiveFrom", s.effectiveFrom().toString());
        m.put("effectiveTo", s.effectiveTo() == null ? null : s.effectiveTo().toString());
        m.put("status", a.status().name());
        return m;
    }
}
