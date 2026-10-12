package gov.itms.payment.application;

import gov.itms.payment.domain.Basket;
import gov.itms.payment.domain.DomainException;
import gov.itms.payment.domain.Payable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in customer pays their own items (ADR-067 d): they see each open item with its itemised lines before
 * paying (CC-047) and put one, several or all of them in one basket (VF-070), which they then pay online through
 * {@code POST /v1/baskets/{id}/payments} like any basket they created. "Their own" means the traffic file
 * traffic-file-service finds for the national id in the customer's token; another file's item answers as not found.
 */
public class CustomerPayments {

    /** Held only through the implicit CUSTOMER role (iam V59). */
    public static final String CUSTOMER_PAY = "customer.payment.make";
    /** At most this many items in one basket (FN-002). */
    static final int MAX_ITEMS = 100;

    private final Ports.PaymentRepository repo;
    private final Ports.OwnFile ownFile;
    private final PaymentService payments;
    private final gov.itms.platform.audit.AuditRecorder audit;

    public CustomerPayments(Ports.PaymentRepository repo, Ports.OwnFile ownFile, PaymentService payments,
                            gov.itms.platform.audit.AuditRecorder audit) {
        this.repo = repo;
        this.ownFile = ownFile;
        this.payments = payments;
        this.audit = audit;
    }

    /** An open item and whether it is paused (under objection, VF-052), so it cannot be paid now. */
    public record Item(Payable payable, boolean paused) {
    }

    /** The customer's items, newest first; {@code status} {@code null} = all. Audited (CC-120). */
    @Transactional
    public List<Item> items(Payable.Status status, int limit, Caller caller) {
        require(caller);
        if (limit < 1 || limit > 500) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..500", List.of("FN-002"), "limit");
        }
        String file = fileNumber();
        List<Item> items = repo.payablesOfFile(file, status, limit).stream()
                .map(p -> new Item(p, p.status() == Payable.Status.OPEN && repo.payableHeld(p.id()))).toList();
        audit.record("PAYABLES_VIEWED", "TrafficFile", file, null, Map.of("viewer", "OWNER", "items", items.size()),
                null);
        return items;
    }

    /**
     * A basket of the customer's own open items (VF-070): those named, or every open item not paused when
     * {@code payableIds} is empty. Naming another file's item answers 404 {@code PAYABLE_NOT_FOUND}.
     */
    @Transactional
    public Basket basket(List<UUID> payableIds, Caller caller) {
        require(caller);
        String file = fileNumber();
        List<UUID> ids;
        if (payableIds == null || payableIds.isEmpty()) {
            ids = repo.payablesOfFile(file, Payable.Status.OPEN, MAX_ITEMS + 1).stream()
                    .filter(p -> !repo.payableHeld(p.id())).map(Payable::id).toList();
            if (ids.isEmpty()) {
                throw DomainException.validation("NOTHING_TO_PAY", "you have nothing open to pay", List.of("VF-070"));
            }
        } else {
            ids = List.copyOf(new LinkedHashSet<>(payableIds));
            for (UUID id : ids) {
                repo.findPayable(id).filter(p -> file.equals(p.trafficFileNumber()))
                        .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "PAYABLE_NOT_FOUND",
                                "payable " + id + " not found", List.of("FN-002")));
            }
        }
        if (ids.size() > MAX_ITEMS) {
            throw DomainException.validation("BASKET_INVALID", "a basket has 1 to 100 different items",
                    List.of("FN-002"));
        }
        return payments.basket(ids, null, caller);
    }

    private String fileNumber() {
        return ownFile.fileNumber().orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "OWN_FILE_NOT_FOUND", "you have no traffic file yet", List.of("DL-001")));
    }

    private static void require(Caller caller) {
        if (!caller.grants().holds(CUSTOMER_PAY)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not pay your items here", List.of("CC-001"));
        }
    }
}
