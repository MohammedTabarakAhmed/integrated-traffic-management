package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.PreservationOrder;
import gov.itms.violation.domain.Violation;
import gov.itms.violation.domain.ViolationPledge;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records kept on violations that do not change the violation itself: the driver's signed undertakings
 * ({@link ViolationPledge}, VF-046) and administrative preservation orders with their references
 * ({@link PreservationOrder}, VF-047). Both are append-only and every recording is audited (CC-120). CC-001: the
 * recorder must reach the violation's region; reading needs the violation.read grant (VF-016).
 */
public class ViolationOrders {

    public static final String PLEDGE_CREATE = "violation.pledge.create";
    public static final String PRESERVATION_CREATE = "violation.preservation.create";
    public static final String READ = "violation.read";

    private final Ports.Pledges pledges;
    private final Ports.PreservationOrders orders;
    private final ViolationService violations;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public ViolationOrders(Ports.Pledges pledges, Ports.PreservationOrders orders, ViolationService violations,
                           AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.pledges = Objects.requireNonNull(pledges);
        this.orders = Objects.requireNonNull(orders);
        this.audit = Objects.requireNonNull(audit);
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
        this.violations = Objects.requireNonNull(violations);
    }

    /**
     * Records the driver's signed undertaking on a violation (VF-046); only a grant may reach the violation's region,
     * and a violation keeps one pledge. The signing already lies in the past: the future cannot be signed yet.
     */
    @Transactional
    public ViolationPledge pledge(String number, String signedBy, Instant signedAt, String note,
                                  List<String> documentIds, Caller caller) {
        Violation v = forCreation(PLEDGE_CREATE, "PLEDGE_CREATE_FORBIDDEN", "cannot record a pledge", number, caller);
        Objects.requireNonNull(signedAt, "signedAt");
        if (signedAt.isAfter(clock.instant())) {
            throw DomainException.validation("PLEDGE_SIGNED_IN_FUTURE", "the signing time is in the future",
                    List.of("VF-046"));
        }
        ViolationPledge pledge = ViolationPledge.recorded(UUID.randomUUID(), v.id(), number, signedBy, signedAt,
                note, documentIds, caller.actor().id(), clock.instant());
        if (!pledges.insert(pledge)) {
            throw DomainException.conflict("PLEDGE_EXISTS", "the violation already has a pledge", List.of("VF-046"));
        }
        audit.record("VIOLATION_PLEDGE_RECORDED", "Violation", number, null,
                Map.of("pledgeId", pledge.id().toString()), null);
        events.record("itms.violation.pledge-recorded", number,
                Map.of("violationNumber", number, "pledgeId", pledge.id().toString()));
        return pledge;
    }

    /**
     * Records an administrative preservation order with its reference on a violation (VF-047); only a grant may reach
     * the violation's region, and a violation keeps no order reference twice.
     */
    @Transactional
    public PreservationOrder preservationOrder(String number, String reference, String source, Instant startAt,
                                               Instant endAt, String note, Caller caller) {
        Violation v = forCreation(PRESERVATION_CREATE, "ORDER_CREATE_FORBIDDEN",
                "cannot record a preservation order", number, caller);
        Objects.requireNonNull(startAt, "startAt");
        PreservationOrder order = PreservationOrder.recorded(UUID.randomUUID(), v.id(), number, reference, source,
                startAt, endAt, note, caller.actor().id(), clock.instant());
        if (!orders.insert(order)) {
            throw DomainException.conflict("ORDER_REFERENCE_RECORDED",
                    "the violation already carries this order reference", List.of("VF-047"));
        }
        audit.record("VIOLATION_PRESERVATION_ORDER_RECORDED", "Violation", number, null,
                Map.of("orderId", order.id().toString(), "reference", order.reference(), "source", source), null);
        events.record("itms.violation.preservation-order-recorded", number,
                Map.of("violationNumber", number, "orderId", order.id().toString(), "reference", order.reference()));
        return order;
    }

    /** The pledges of a violation, newest first (VF-046); the reader must reach its region like any read (VF-016). */
    @Transactional
    public List<ViolationPledge> pledges(String number, Caller caller) {
        Violation v = violations.get(number, caller);
        return pledges.pledgesOf(v.number());
    }

    /** The preservation orders of a violation, newest first (VF-047); the reader must reach its region (CC-001). */
    @Transactional
    public List<PreservationOrder> preservationOrders(String number, Caller caller) {
        Violation v = violations.get(number, caller);
        return orders.ordersOf(v.number());
    }

    private Violation forCreation(String permission, String code, String message, String number, Caller caller) {
        if (!caller.grants().holds(permission)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, code, message, List.of("CC-001"));
        }
        return violations.get(number, caller);
    }
}
