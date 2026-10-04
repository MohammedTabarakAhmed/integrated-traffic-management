package gov.itms.transaction.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer transaction (CC-021, CC-022, CC-023, CC-027, CC-113; ADR-029). It is opened in Draft under the service
 * definition in force, and each command moves it one step along the lifecycle of {@link TransactionStatus}, recording
 * the change ({@link #lastChange()}) for the status history. Every rule here is enforced again by the database.
 *
 * <p>Payment, review, return-for-information, decision, fulfilment and expiry are commands of the services that are
 * built later (payments, workflow, scheduler); they are defined here so that the lifecycle has one owner.
 */
public final class Transaction {

    /** Actor recorded for automatic steps (LLD §2.1). */
    public static final Actor SYSTEM = new Actor("SYSTEM", "transaction-service");

    private final UUID id;
    private final TransactionReference reference;
    private final long serviceVersion;
    private final String module;
    private final String feeCode;
    private final boolean allowsConcurrentOpen;
    private final Subject subject;
    private final String applicantFileNumber;
    private final String homeRegion;
    private final Channel channel;
    private final Instant createdAt;
    private final Actor createdBy;
    private Instant submittedAt;
    private StatusChange lastChange;

    @SuppressWarnings("java:S107") // a stored transaction has this many attributes
    public Transaction(UUID id, TransactionReference reference, long serviceVersion, String module, String feeCode,
                       boolean allowsConcurrentOpen, Subject subject, String applicantFileNumber, String homeRegion,
                       Channel channel, Instant createdAt, Actor createdBy, Instant submittedAt,
                       StatusChange lastChange) {
        this.id = Objects.requireNonNull(id);
        this.reference = Objects.requireNonNull(reference);
        this.serviceVersion = serviceVersion;
        this.module = Codes.code("module", module);
        this.feeCode = Codes.optionalCode("feeCode", feeCode);
        this.allowsConcurrentOpen = allowsConcurrentOpen;
        this.subject = Objects.requireNonNull(subject);
        this.applicantFileNumber = Subject.businessNumber("applicantFileNumber", applicantFileNumber);
        this.homeRegion = Codes.code("homeRegion", homeRegion);
        this.channel = Objects.requireNonNull(channel);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.submittedAt = submittedAt;
        this.lastChange = Objects.requireNonNull(lastChange);
    }

    /**
     * Whether the service may be used in {@code region} on {@code availability}'s channel today by an applicant whose
     * file is in {@code homeRegion}: the service must be available there (CC-026, CC-112), and a service that is not
     * usable in any region (CC-113 exception) only in the home region.
     */
    public static void checkAccess(ServiceSpec service, Availability availability, String homeRegion) {
        Codes.code("homeRegion", homeRegion);
        if (!availability.available()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "SERVICE_NOT_AVAILABLE",
                    "service " + service.code() + " is not available in region " + availability.region() + " on "
                            + availability.channel() + " (" + availability.reason() + ")",
                    List.of("CC-026", "CC-112"), service.code(), availability.region(), availability.channel());
        }
        if (!service.anyRegion() && !availability.region().equals(homeRegion)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "HOME_REGION_ONLY",
                    "service " + service.code() + " can only be used in the region of the applicant's traffic file ("
                            + homeRegion + ")", List.of("CC-113"), service.code(), homeRegion);
        }
    }

    /** Opens a Draft (CC-022) under the definition in force, after {@link #checkAccess}. */
    @SuppressWarnings("java:S107")
    public static Transaction open(UUID id, TransactionReference reference, ServiceDefinition definition,
                                   Availability availability, Subject subject, String applicantFileNumber,
                                   String homeRegion, Actor actor, Instant now) {
        ServiceSpec service = definition.spec();
        if (!reference.serviceCode().equals(service.code()) || !reference.region().equals(availability.region())
                || !availability.serviceCode().equals(service.code())) {
            throw new IllegalArgumentException("reference, definition and availability must match");
        }
        checkAccess(service, availability, homeRegion);
        Channel channel = availability.channel();
        return new Transaction(id, reference, definition.version(), service.module(), service.feeCode(),
                service.allowsConcurrentOpen(), subject, applicantFileNumber, homeRegion, channel, now, actor, null,
                new StatusChange(1, null, TransactionStatus.DRAFT, StatusReason.CREATED, null, actor, channel, now));
    }

    // ---------------------------------------------------------------- commands (CC-022)

    /** Draft → Submitted. Follow with {@link #routePayment}. */
    public StatusChange submit(Actor actor, Channel via, Instant now) {
        StatusChange change = move(TransactionStatus.SUBMITTED, StatusReason.SUBMITTED, null, actor, via, now);
        submittedAt = now;
        return change;
    }

    /**
     * Submitted → Pending payment when the service has a fee, otherwise → In review with the skip recorded (Q-004).
     * Done by the system.
     */
    public StatusChange routePayment(Channel via, Instant now) {
        return paymentRequired()
                ? move(TransactionStatus.PENDING_PAYMENT, StatusReason.PAYMENT_REQUIRED, null, SYSTEM, via, now)
                : move(TransactionStatus.IN_REVIEW, StatusReason.ZERO_FEE_PAYMENT_SKIPPED, null, SYSTEM, via, now);
    }

    /** Pending payment → Paid, on PaymentConfirmed (payments service, FN-003). */
    public StatusChange confirmPayment(Actor actor, Channel via, Instant now) {
        return move(TransactionStatus.PAID, StatusReason.PAYMENT_CONFIRMED, null, actor, via, now);
    }

    /** Paid → In review (workflow service). */
    public StatusChange startReview(Actor actor, Channel via, Instant now) {
        return move(TransactionStatus.IN_REVIEW, StatusReason.REVIEW_STARTED, null, actor, via, now);
    }

    /** In review → Returned for information (CC-065); the note says what is needed. */
    public StatusChange returnForInfo(Actor actor, Channel via, String note, Instant now) {
        return move(TransactionStatus.RETURNED_FOR_INFO, StatusReason.RETURNED_FOR_INFO, Codes.reason(note), actor,
                via, now);
    }

    /** Returned for information → In review, when the customer responds. */
    public StatusChange provideInfo(Actor actor, Channel via, Instant now) {
        return move(TransactionStatus.IN_REVIEW, StatusReason.INFO_PROVIDED, null, actor, via, now);
    }

    public StatusChange approve(Actor actor, Channel via, Instant now) {
        return move(TransactionStatus.APPROVED, StatusReason.APPROVED, null, actor, via, now);
    }

    /** In review → Rejected; the reason is required. */
    public StatusChange reject(Actor actor, Channel via, String note, Instant now) {
        return move(TransactionStatus.REJECTED, StatusReason.REJECTED, Codes.reason(note), actor, via, now);
    }

    /** Approved → Completed, when fulfilment is done. */
    public StatusChange complete(Actor actor, Channel via, Instant now) {
        return move(TransactionStatus.COMPLETED, StatusReason.FULFILLED, null, actor, via, now);
    }

    /** Draft or Pending payment → Cancelled; the reason is required. */
    public StatusChange cancel(Actor actor, Channel via, String note, Instant now) {
        return move(TransactionStatus.CANCELLED, StatusReason.CANCELLED, Codes.reason(note), actor, via, now);
    }

    /** Draft, Pending payment or Returned for information → Expired, by the system (CC-024, next slice). */
    public StatusChange expire(Channel via, Instant now) {
        return move(TransactionStatus.EXPIRED, StatusReason.EXPIRED, null, SYSTEM, via, now);
    }

    private StatusChange move(TransactionStatus to, StatusReason reason, String note, Actor actor, Channel via,
                              Instant now) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(via);
        TransactionStatus from = status();
        if (!from.canMoveTo(to)) {
            throw new DomainException(DomainException.Kind.CONFLICT, "ILLEGAL_TRANSITION",
                    "a transaction in status " + from + " cannot move to " + to, List.of("CC-022"), from, to);
        }
        if (now.isBefore(lastChange.changedAt())) {
            throw new IllegalStateException("a status change cannot be earlier than the previous one");
        }
        lastChange = new StatusChange(lastChange.sequence() + 1, from, to, reason, note, actor, via, now);
        return lastChange;
    }

    // ---------------------------------------------------------------- state

    public UUID id() { return id; }
    public TransactionReference reference() { return reference; }
    public String serviceCode() { return reference.serviceCode(); }
    public String region() { return reference.region(); }
    public long serviceVersion() { return serviceVersion; }
    public String module() { return module; }
    public String feeCode() { return feeCode; }
    /** The service had a fee when the transaction was opened (Q-004, Q-049). */
    public boolean paymentRequired() { return feeCode != null; }
    public boolean allowsConcurrentOpen() { return allowsConcurrentOpen; }
    public Subject subject() { return subject; }
    public String applicantFileNumber() { return applicantFileNumber; }
    public String homeRegion() { return homeRegion; }
    /** Channel through which the transaction was opened. */
    public Channel channel() { return channel; }
    public Instant createdAt() { return createdAt; }
    public Actor createdBy() { return createdBy; }
    public Instant submittedAt() { return submittedAt; }
    public StatusChange lastChange() { return lastChange; }
    public TransactionStatus status() { return lastChange.status(); }
    /** Number of status changes so far, the creation included. */
    public long version() { return lastChange.sequence(); }
}
