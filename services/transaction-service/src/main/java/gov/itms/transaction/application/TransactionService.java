package gov.itms.transaction.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.transaction.domain.Availability;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.ServiceDefinition;
import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.Subject;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionCursor;
import gov.itms.transaction.domain.TransactionReference;
import gov.itms.transaction.domain.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction use cases (CC-021, CC-022, CC-023, CC-027, CC-113; ADR-029):
 * <ul>
 *   <li>opening a Draft needs {@value #CREATE} in a grant that reaches the transaction's region and the service's
 *       module (CC-001); the service must be available there on the caller's channel today (CC-026), usable outside
 *       the home region unless the service says otherwise (CC-113), and the subject may not already have an open
 *       transaction of the service unless the service allows it (CC-027);</li>
 *   <li>submitting needs {@value #CREATE}, cancelling {@value #CANCEL}, reading {@value #READ}, each reaching the
 *       transaction's region and module; a transaction outside the caller's scope answers as if it did not exist;</li>
 *   <li>every change and every view is audited in the same database transaction (CC-120); status changes also go to
 *       the status history, written by the database (CC-023).</li>
 * </ul>
 */
public class TransactionService {

    public static final String CREATE = "transaction.create";
    public static final String CANCEL = "transaction.cancel";
    public static final String READ = "transaction.read";
    static final String ENTITY = "Transaction";
    /** Audit entity of a list view: the applicant whose transactions were shown. */
    static final String ENTITY_LIST = "ApplicantTransactions";
    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 200;

    private final ServiceDefinitionRepository services;
    private final AvailabilityRuleRepository rules;
    private final TransactionRepository transactions;
    private final AuditRecorder audit;
    private final Clock clock;

    public TransactionService(ServiceDefinitionRepository services, AvailabilityRuleRepository rules,
                              TransactionRepository transactions, AuditRecorder audit, Clock clock) {
        this.services = services;
        this.rules = rules;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /** What the caller asks to open. {@code region} {@code null} means the home region (Q-003). */
    public record NewTransaction(String serviceCode, Subject subject, String applicantFileNumber, String homeRegion,
                                 String region) {
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public Transaction create(NewTransaction request, Caller caller) {
        Channel channel = channelOf(caller);
        ServiceDefinition definition = services.find(request.serviceCode()).orElseThrow(() ->
                new DomainException(DomainException.Kind.VALIDATION, "UNKNOWN_SERVICE",
                        "service " + request.serviceCode() + " does not exist", List.of("CC-020"),
                        request.serviceCode()));
        String region = request.region() != null ? request.region() : request.homeRegion();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        Availability availability = Availability.of(definition.spec(), rules.findByService(definition.code()), region,
                channel, today);
        if (!caller.scopes().allows(CREATE, region, definition.spec().module())) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you can only open transactions in your own regions and modules", List.of("CC-001"));
        }
        Transaction.checkAccess(definition.spec(), availability, request.homeRegion());
        if (!definition.spec().allowsConcurrentOpen()) {
            transactions.findOpen(definition.code(), request.subject()).ifPresent(open -> {
                throw duplicate(definition.code(), open, caller);
            });
        }
        TransactionReference reference = new TransactionReference(definition.code(), region, today.getYear(),
                transactions.nextSequence(definition.code(), region, today.getYear()));
        Transaction created = Transaction.open(UUID.randomUUID(), reference, definition, availability,
                request.subject(), request.applicantFileNumber(), request.homeRegion(), caller.actor(), now);
        transactions.insert(created);
        audit.record("TRANSACTION_CREATED", ENTITY, reference.toString(), null, snapshot(created), null);
        return created;
    }

    /**
     * Draft → Submitted, then Pending payment or (no fee) In review with the skip recorded (CC-022, Q-004). The
     * service must still be available and usable from the home-region rule's point of view (Q-049).
     */
    @Transactional
    public Transaction submit(String reference, Caller caller) {
        Channel channel = channelOf(caller);
        Transaction transaction = forUpdate(reference, CREATE, caller);
        Map<String, Object> before = snapshot(transaction);
        Instant now = clock.instant();
        transaction.submit(caller.actor(), channel, now);
        ServiceDefinition definition = services.find(transaction.serviceCode()).orElseThrow();
        Transaction.checkAccess(definition.spec(), Availability.of(definition.spec(),
                rules.findByService(definition.code()), transaction.region(), channel,
                LocalDate.ofInstant(now, clock.getZone())), transaction.homeRegion());
        transactions.saveStatus(transaction);
        transaction.routePayment(channel, now);
        transactions.saveStatus(transaction);
        audit.record("TRANSACTION_SUBMITTED", ENTITY, reference, before, snapshot(transaction), null);
        return transaction;
    }

    /** Draft or Pending payment → Cancelled, with a reason (CC-022). */
    @Transactional
    public Transaction cancel(String reference, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        Transaction transaction = forUpdate(reference, CANCEL, caller);
        Map<String, Object> before = snapshot(transaction);
        transaction.cancel(caller.actor(), channel, reason, clock.instant());
        transactions.saveStatus(transaction);
        audit.record("TRANSACTION_CANCELLED", ENTITY, reference, before, snapshot(transaction),
                transaction.lastChange().note());
        return transaction;
    }

    // ---------------------------------------------------------------- queries (views are audited, CC-120)

    @Transactional
    public Transaction get(String reference, Caller caller) {
        Transaction transaction = visible(transactions.find(TransactionReference.parse(reference)), READ, caller);
        audit.record("TRANSACTION_VIEWED", ENTITY, reference, null, null, null);
        return transaction;
    }

    /** One page of an applicant's transactions; {@code nextCursor} is {@code null} on the last page. */
    public record TransactionPage(List<Transaction> items, String nextCursor) {
    }

    /**
     * An applicant's transactions, newest first, whatever channel they were opened through (CC-025; staff-assisted,
     * Q-050). Transactions outside the caller's scopes are left out of the page; the transactions shown are audited
     * as one view (CC-120).
     */
    @Transactional
    public TransactionPage byApplicant(String applicantFileNumber, TransactionStatus status, String cursor,
                                       Integer limit, Caller caller) {
        String applicant = Subject.businessNumber("applicantFileNumber", applicantFileNumber);
        int size = limit == null ? DEFAULT_PAGE : limit;
        if (size < 1 || size > MAX_PAGE) {
            throw new DomainException(DomainException.Kind.VALIDATION, "LIMIT_INVALID",
                    "limit must be between 1 and " + MAX_PAGE, List.of("CC-025"), MAX_PAGE);
        }
        TransactionCursor after = cursor == null ? null : TransactionCursor.decode(cursor);
        List<Transaction> rows = transactions.findByApplicant(applicant, status, after, size + 1);
        List<Transaction> scanned = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size ? TransactionCursor.after(scanned.get(size - 1)).encode() : null;
        List<Transaction> visible = scanned.stream()
                .filter(t -> caller.scopes().allows(READ, t.region(), t.module())).toList();
        audit.record("TRANSACTION_LIST_VIEWED", ENTITY_LIST, applicant, null,
                Map.of("references", visible.stream().map(t -> t.reference().toString()).toList()), null);
        return new TransactionPage(visible, next);
    }

    /** Every status change, oldest first (CC-023). */
    @Transactional
    public List<StatusChange> history(String reference, Caller caller) {
        Transaction transaction = visible(transactions.find(TransactionReference.parse(reference)), READ, caller);
        audit.record("TRANSACTION_HISTORY_VIEWED", ENTITY, reference, null, null, null);
        return transactions.history(transaction.id());
    }

    // ---------------------------------------------------------------- internals

    private Transaction forUpdate(String reference, String permission, Caller caller) {
        return visible(transactions.findForUpdate(TransactionReference.parse(reference)), permission, caller);
    }

    /** Out of scope answers as not found, so references cannot be probed across regions (CC-001). */
    private static Transaction visible(Optional<Transaction> found, String permission, Caller caller) {
        return found.filter(t -> caller.scopes().allows(permission, t.region(), t.module()))
                .orElseThrow(TransactionService::notFound);
    }

    private static Channel channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CHANNEL_REQUIRED",
                    "the request must say through which channel it was made", List.of("CC-023"));
        }
        return caller.channel();
    }

    /** CC-027. The open transaction's reference is shown only to a caller who may read it. */
    private static DomainException duplicate(String serviceCode, Transaction open, Caller caller) {
        String shown = caller.scopes().allows(READ, open.region(), open.module()) ? " (" + open.reference() + ")" : "";
        return new DomainException(DomainException.Kind.CONFLICT, "DUPLICATE_OPEN_TRANSACTION",
                "the subject already has an open transaction of service " + serviceCode + shown, List.of("CC-027"),
                serviceCode, shown);
    }

    private static DomainException notFound() {
        return new DomainException(DomainException.Kind.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                "there is no transaction with this reference", List.of("CC-021"));
    }

    static Map<String, Object> snapshot(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", t.reference().toString());
        m.put("serviceCode", t.serviceCode());
        m.put("serviceVersion", t.serviceVersion());
        m.put("subjectType", t.subject().type().name());
        m.put("subjectId", t.subject().id());
        m.put("applicantFileNumber", t.applicantFileNumber());
        m.put("homeRegion", t.homeRegion());
        m.put("region", t.region());
        m.put("status", t.status().name());
        m.put("statusReason", t.lastChange().reason().name());
        m.put("statusActor", t.lastChange().actor().id());
        m.put("version", t.version());
        return m;
    }
}
