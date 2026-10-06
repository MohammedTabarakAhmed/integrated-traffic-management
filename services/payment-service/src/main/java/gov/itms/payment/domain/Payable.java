package gov.itms.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Something owed (FN-002; ADR-054): registered by the service that owns it (a transaction's fees, a violation's fine,
 * a general income item), itemised by financial item and beneficiary, in minor units of one currency. Payment-service
 * never prices anything itself; it collects what the owner registered.
 *
 * <p>OPEN → PAID (a confirmed payment covers it) → OPEN again only if its receipt is cancelled; or CANCELLED by the
 * owner while still OPEN.
 */
public final class Payable {

    public enum ItemType { TRANSACTION, VIOLATION, GENERAL, DEPOSIT }

    public enum Status { OPEN, PAID, CANCELLED }

    public static final String CODE = "^[A-Z0-9][A-Z0-9_]{1,39}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern REF = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,59}$");

    /** One amount for one financial item and beneficiary (FN-003, FN-013). */
    public record Line(String financialItem, String beneficiary, String description, long amountMinor) {
        public Line {
            code("financialItem", financialItem);
            code("beneficiary", beneficiary);
            if (description != null && (description.isBlank() || description.length() > 200)) {
                throw invalid("description");
            }
            if (amountMinor < 0 || amountMinor > 1_000_000_000_000L) {
                throw invalid("amountMinor");
            }
        }
    }

    private final UUID id;
    private final ItemType itemType;
    private final String itemRef;
    private final String trafficFileNumber;
    private final String region;
    private final String currency;
    private final List<Line> lines;
    private final String createdBy;
    private final Instant createdAt;
    private Status status;

    private Payable(UUID id, ItemType itemType, String itemRef, String trafficFileNumber, String region,
                    String currency, List<Line> lines, String createdBy, Instant createdAt, Status status) {
        this.id = Objects.requireNonNull(id);
        this.itemType = Objects.requireNonNull(itemType);
        if (itemRef == null || !REF.matcher(itemRef).matches()) {
            throw invalid("itemRef");
        }
        this.itemRef = itemRef;
        if (trafficFileNumber != null && !REF.matcher(trafficFileNumber).matches()) {
            throw invalid("trafficFileNumber");
        }
        this.trafficFileNumber = trafficFileNumber;
        this.region = code("region", region);
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw invalid("currency");
        }
        this.currency = currency;
        this.lines = List.copyOf(lines);
        if (this.lines.isEmpty() || this.lines.size() > 50) {
            throw invalid("lines");
        }
        if (total() <= 0) {
            throw DomainException.validation("AMOUNT_INVALID", "a payable is for a positive amount",
                    List.of("FN-002"));
        }
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = Objects.requireNonNull(status);
    }

    public static Payable register(UUID id, ItemType type, String itemRef, String trafficFileNumber, String region,
                                   String currency, List<Line> lines, String createdBy, Instant now) {
        return new Payable(id, type, itemRef, trafficFileNumber, region, currency, lines, createdBy, now, Status.OPEN);
    }

    public static Payable restore(UUID id, ItemType type, String itemRef, String trafficFileNumber, String region,
                                  String currency, List<Line> lines, String createdBy, Instant createdAt,
                                  Status status) {
        return new Payable(id, type, itemRef, trafficFileNumber, region, currency, lines, createdBy, createdAt,
                status);
    }

    public long total() {
        long sum = 0;
        for (Line l : lines) {
            sum = Math.addExact(sum, l.amountMinor());
        }
        return sum;
    }

    public void requireOpen() {
        if (status != Status.OPEN) {
            throw DomainException.conflict("PAYABLE_NOT_OPEN", "item " + itemRef + " is " + status,
                    List.of("FN-002"), itemRef, status.name());
        }
    }

    public void paid() {
        requireOpen();
        status = Status.PAID;
    }

    /** Back to OPEN when its receipt is cancelled (FN-011). */
    public void reopen() {
        if (status != Status.PAID) {
            throw new IllegalStateException("only a paid item reopens");
        }
        status = Status.OPEN;
    }

    public void cancel() {
        requireOpen();
        status = Status.CANCELLED;
    }

    static String code(String field, String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("FN-002"),
                field);
    }

    public UUID id() {
        return id;
    }

    public ItemType itemType() {
        return itemType;
    }

    public String itemRef() {
        return itemRef;
    }

    public String trafficFileNumber() {
        return trafficFileNumber;
    }

    public String region() {
        return region;
    }

    public String currency() {
        return currency;
    }

    public List<Line> lines() {
        return lines;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }
}
