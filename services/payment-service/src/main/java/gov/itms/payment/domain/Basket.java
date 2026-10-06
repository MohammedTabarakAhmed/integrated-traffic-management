package gov.itms.payment.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Several payables paid together (FN-002; ADR-054): transactions and violations of one or more traffic files, in one
 * currency. The total is fixed when the basket is made. OPEN until a payment of it is confirmed (PAID).
 */
public record Basket(UUID id, List<UUID> payableIds, String region, String currency, long totalMinor,
                     String createdBy, Instant createdAt, Status status) {

    public enum Status { OPEN, PAID }

    public Basket {
        Objects.requireNonNull(id);
        payableIds = List.copyOf(payableIds);
        if (payableIds.isEmpty() || payableIds.size() > 100 || new HashSet<>(payableIds).size() != payableIds.size()) {
            throw DomainException.validation("BASKET_INVALID", "a basket has 1 to 100 different items",
                    List.of("FN-002"));
        }
        Objects.requireNonNull(region);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(createdBy);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(status);
    }

    /** Builds a basket from open payables of one currency; the region is given or taken from the first item. */
    public static Basket of(UUID id, List<Payable> items, String region, String createdBy, Instant now) {
        if (items.isEmpty()) {
            throw DomainException.validation("BASKET_INVALID", "a basket has 1 to 100 different items",
                    List.of("FN-002"));
        }
        String currency = items.getFirst().currency();
        long total = 0;
        for (Payable p : items) {
            p.requireOpen();
            if (!p.currency().equals(currency)) {
                throw DomainException.validation("CURRENCY_MIXED", "a basket is paid in one currency",
                        List.of("FN-002"));
            }
            total = Math.addExact(total, p.total());
        }
        return new Basket(id, items.stream().map(Payable::id).toList(),
                region == null ? items.getFirst().region() : Payable.code("region", region), currency, total,
                createdBy, now, Status.OPEN);
    }

    public Basket paid() {
        return new Basket(id, payableIds, region, currency, totalMinor, createdBy, createdAt, Status.PAID);
    }

    public void requireOpen() {
        if (status != Status.OPEN) {
            throw DomainException.conflict("BASKET_PAID", "this basket is already paid", List.of("FN-002"));
        }
    }
}
