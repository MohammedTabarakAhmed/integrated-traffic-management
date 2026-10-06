package gov.itms.fees.application;

import gov.itms.fees.domain.Channel;
import gov.itms.fees.domain.DomainException;
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
 * Fee versions with maker-checker (CC-040..CC-042, CC-046; ADR-046):
 * <ul>
 *   <li>{@value #SUBMIT} submits a version (a new fee or a change), PENDING; numbered per fee code;</li>
 *   <li>{@value #APPROVE} approves or rejects it; the approver is never the submitter. On approval, an approved
 *       version of the same fee and applicability that started earlier is ended the day before; one that starts on or
 *       after the new start date is an overlap and refused;</li>
 *   <li>{@value #READ} reads versions. Fees are national configuration with regional variants (CC-112), so the
 *       permissions apply in any grant;</li>
 *   <li>every submission and decision is audited (CC-120) and published ({@code itms.fee.version-approved}).</li>
 * </ul>
 */
public class FeeService {

    public static final String SUBMIT = "fees.version.submit";
    public static final String APPROVE = "fees.version.approve";
    public static final String READ = "fees.version.read";
    static final String ENTITY = "FeeVersion";

    private final FeeRepository fees;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final String currency;

    public FeeService(FeeRepository fees, AuditRecorder audit, DomainEventRecorder events, Clock clock,
                      String currency) {
        this.fees = fees;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.currency = currency;
    }

    @Transactional
    public FeeVersion submit(FeeVersion.Spec spec, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        require(caller, SUBMIT);
        fees.lockFee(spec.feeCode());
        List<FeeVersion> existing = fees.byFee(spec.feeCode());
        if (existing.stream().anyMatch(v -> !v.spec().serviceCode().equals(spec.serviceCode()))) {
            throw DomainException.validation("FEE_SERVICE_MISMATCH",
                    "fee " + spec.feeCode() + " belongs to another service", List.of("CC-040"), spec.feeCode());
        }
        Instant now = clock.instant();
        FeeVersion version = FeeVersion.submit(UUID.randomUUID(), fees.nextVersionNo(spec.feeCode()), spec, currency,
                reason, caller.actor(), channel, now, today(now));
        fees.insert(version);
        audit.record("FEE_VERSION_SUBMITTED", ENTITY, version.id().toString(), null, snapshot(version),
                version.submitted().reason());
        return version;
    }

    @Transactional
    public FeeVersion approve(UUID id, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        require(caller, APPROVE);
        FeeVersion version = fees.find(id).orElseThrow(() -> notFound(id));
        fees.lockFee(version.spec().feeCode());
        version = fees.findForUpdate(id).orElseThrow(() -> notFound(id));
        Instant now = clock.instant();
        LocalDate today = today(now);
        version.approve(caller.actor(), channel, reason, now, today);

        FeeVersion approved = version;
        for (FeeVersion other : fees.byFee(version.spec().feeCode())) {
            boolean sameScope = other.status() == FeeVersion.Status.APPROVED
                    && other.spec().applicability().equals(approved.spec().applicability());
            if (!sameScope || !other.overlaps(approved.spec().effectiveFrom(), approved.effectiveTo())) {
                continue;
            }
            if (!other.spec().effectiveFrom().isBefore(approved.spec().effectiveFrom())) {
                throw DomainException.conflict("FEE_VERSION_OVERLAP",
                        "an approved version (v" + other.versionNo() + ") already covers this period",
                        List.of("CC-040", "CC-046"), other.versionNo());
            }
            Map<String, Object> before = snapshot(other);
            other.endBefore(approved.spec().effectiveFrom());
            fees.saveEnd(other);
            audit.record("FEE_VERSION_ENDED", ENTITY, other.id().toString(), before, snapshot(other),
                    "superseded by v" + approved.versionNo());
        }
        fees.saveDecision(approved);
        audit.record("FEE_VERSION_APPROVED", ENTITY, id.toString(), null, snapshot(approved), reason);
        events.record("itms.fee.version-approved", approved.spec().feeCode(), snapshot(approved));
        return approved;
    }

    @Transactional
    public FeeVersion reject(UUID id, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        FeeVersion found = fees.find(id).orElseThrow(() -> notFound(id));
        boolean withdrawal = found.submitted().actor().id().equals(caller.actor().id());
        if (!withdrawal) {
            require(caller, APPROVE);
        }
        FeeVersion version = fees.findForUpdate(id).orElseThrow(() -> notFound(id));
        version.reject(caller.actor(), channel, reason, clock.instant());
        fees.saveDecision(version);
        audit.record(withdrawal ? "FEE_VERSION_WITHDRAWN" : "FEE_VERSION_REJECTED", ENTITY, id.toString(), null,
                snapshot(version), version.decided().reason());
        return version;
    }

    @Transactional(readOnly = true)
    public FeeVersion get(UUID id, Caller caller) {
        require(caller, READ);
        return fees.find(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public List<FeeVersion> byFee(String feeCode, Caller caller) {
        require(caller, READ);
        return fees.byFee(feeCode);
    }

    @Transactional(readOnly = true)
    public List<FeeVersion> byService(String serviceCode, FeeVersion.Status status, Caller caller) {
        require(caller, READ);
        return fees.byService(serviceCode, status);
    }

    private LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, clock.getZone());
    }

    static void require(Caller caller, String permission) {
        if (!caller.grants().holds(permission)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with fees", List.of("CC-001"));
        }
    }

    static Channel channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
        return caller.channel();
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "FEE_VERSION_NOT_FOUND",
                "fee version " + id + " does not exist", List.of("CC-040"));
    }

    static Map<String, Object> snapshot(FeeVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feeCode", v.spec().feeCode());
        m.put("versionNo", v.versionNo());
        m.put("serviceCode", v.spec().serviceCode());
        m.put("region", v.spec().applicability().region());
        m.put("customerCategory", v.spec().applicability().customerCategory());
        m.put("subjectCategory", v.spec().applicability().subjectCategory());
        m.put("financialItem", v.spec().financialItem());
        m.put("beneficiary", v.spec().beneficiary());
        m.put("method", v.spec().method().toString());
        m.put("currency", v.currency());
        m.put("effectiveFrom", v.spec().effectiveFrom().toString());
        m.put("effectiveTo", v.effectiveTo() == null ? null : v.effectiveTo().toString());
        m.put("status", v.status().name());
        return m;
    }
}
