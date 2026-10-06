package gov.itms.payment.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The electronic receipt of a confirmed payment (FN-003, FN-010, FN-011, FN-022; ADR-054): a unique number from a
 * gapless sequence per region, channel and year, a type, and lines itemised by financial item. A receipt may be
 * cancelled only on its business day, by a supervisor, with a reason; later reversals are refunds.
 */
public final class Receipt {

    public enum Type { SERVICE, VIOLATION, GENERAL, DEPOSIT }

    public enum Status { ISSUED, CANCELLED }

    /** One itemised line: which payable, which financial item and beneficiary, how much. */
    public record Line(UUID payableId, Payable.ItemType itemType, String itemRef, String trafficFileNumber,
                       String financialItem, String beneficiary, String description, long amountMinor) {
    }

    private final UUID id;
    private final String number;
    private final UUID paymentId;
    private final Type type;
    private final String region;
    private final String channel;
    private final Payment.Method method;
    private final String currency;
    private final long totalMinor;
    private final List<Line> lines;
    private final String payerId;
    private final Instant issuedAt;
    private final LocalDate businessDay;
    private Status status;
    private Instant cancelledAt;
    private String cancelledBy;
    private String cancellationReason;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Receipt(UUID id, String number, UUID paymentId, Type type, String region, String channel,
                   Payment.Method method, String currency, long totalMinor, List<Line> lines, String payerId,
                   Instant issuedAt, LocalDate businessDay, Status status, Instant cancelledAt, String cancelledBy,
                   String cancellationReason) {
        this.id = Objects.requireNonNull(id);
        this.number = Objects.requireNonNull(number);
        this.paymentId = Objects.requireNonNull(paymentId);
        this.type = Objects.requireNonNull(type);
        this.region = region;
        this.channel = channel;
        this.method = method;
        this.currency = currency;
        this.totalMinor = totalMinor;
        this.lines = List.copyOf(lines);
        this.payerId = payerId;
        this.issuedAt = issuedAt;
        this.businessDay = businessDay;
        this.status = status;
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.cancellationReason = cancellationReason;
    }

    /** The receipt type from what was paid (FN-010): all violations → VIOLATION, deposits → DEPOSIT, etc. */
    public static Type typeOf(List<Payable> items) {
        if (items.stream().allMatch(p -> p.itemType() == Payable.ItemType.VIOLATION)) {
            return Type.VIOLATION;
        }
        if (items.stream().allMatch(p -> p.itemType() == Payable.ItemType.DEPOSIT)) {
            return Type.DEPOSIT;
        }
        if (items.stream().allMatch(p -> p.itemType() == Payable.ItemType.GENERAL)) {
            return Type.GENERAL;
        }
        return Type.SERVICE;
    }

    /** The receipt number: {@code R-<region>-<channel>-<year>-<8 digits>} from a gapless sequence (FN-022). */
    public static String number(String region, String channel, int year, long sequence) {
        return "R-" + region + "-" + channel + "-" + year + "-" + String.format("%08d", sequence);
    }

    /** Same business day, by a supervisor, with a reason (FN-011). */
    public void cancel(String supervisorId, String reason, Instant now, ZoneId businessZone) {
        if (status != Status.ISSUED) {
            throw DomainException.conflict("RECEIPT_CANCELLED", "the receipt is already cancelled",
                    List.of("FN-011"));
        }
        if (!LocalDate.ofInstant(now, businessZone).equals(businessDay)) {
            throw DomainException.conflict("RECEIPT_NOT_SAME_DAY",
                    "a receipt can be cancelled only on its business day; use a refund", List.of("FN-011"),
                    businessDay.toString());
        }
        status = Status.CANCELLED;
        cancelledAt = now;
        cancelledBy = supervisorId;
        cancellationReason = reason;
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public Type type() {
        return type;
    }

    public String region() {
        return region;
    }

    public String channel() {
        return channel;
    }

    public Payment.Method method() {
        return method;
    }

    public String currency() {
        return currency;
    }

    public long totalMinor() {
        return totalMinor;
    }

    public List<Line> lines() {
        return lines;
    }

    public String payerId() {
        return payerId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public LocalDate businessDay() {
        return businessDay;
    }

    public Status status() {
        return status;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public String cancelledBy() {
        return cancelledBy;
    }

    public String cancellationReason() {
        return cancellationReason;
    }
}
