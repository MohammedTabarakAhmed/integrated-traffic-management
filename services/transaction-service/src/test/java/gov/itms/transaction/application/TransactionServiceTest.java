package gov.itms.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.transaction.application.TransactionService.NewTransaction;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.AvailabilityRule;
import gov.itms.transaction.domain.AvailabilityRuleSpec;
import gov.itms.transaction.domain.BilingualName;
import gov.itms.transaction.domain.ChangeChannel;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.ServiceDefinition;
import gov.itms.transaction.domain.ServiceSpec;
import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.StatusReason;
import gov.itms.transaction.domain.Subject;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final Actor CLERK = new Actor("u-clerk", "clerk");
    private static final Subject CAR = new Subject(Subject.Type.VEHICLE, "VIN1");
    private static final Caller.Scopes NATIONAL = (permission, region, module) -> true;

    private InMemoryRepositories.Services services;
    private InMemoryRepositories.Rules rules;
    private InMemoryRepositories.Transactions transactions;
    private InMemoryRepositories.Audit audit;
    private MutableClock clock;
    private Eligibility eligibility;
    private TransactionService service;

    /** hold-eligibility-service as the port sees it: blocks per subject, every call recorded (CC-032). */
    static final class Eligibility implements EligibilityChecker {
        final Map<Subject, Block> blocked = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean unavailable;

        @Override
        public List<Block> check(String serviceCode, List<Subject> subjects, String transactionReference,
                                 Channel channel) {
            calls.add(serviceCode + " " + subjects + " " + transactionReference + " " + channel);
            if (unavailable) {
                throw new DomainException(DomainException.Kind.UNAVAILABLE, "ELIGIBILITY_UNAVAILABLE", "down",
                        List.of("CC-032"));
            }
            return subjects.stream().map(blocked::get).filter(Objects::nonNull).distinct().toList();
        }
    }

    /** A clock the test can move, for the yearly reset of the sequence. */
    static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        services = new InMemoryRepositories.Services();
        rules = new InMemoryRepositories.Rules();
        transactions = new InMemoryRepositories.Transactions();
        audit = new InMemoryRepositories.Audit();
        clock = new MutableClock();
        eligibility = new Eligibility();
        service = new TransactionService(services, rules, transactions, eligibility, audit, clock);
        define("VL_RENEW", "VL_RENEW_FEE", true, false);
        define("DL_FREE", null, true, false);
        define("VL_LOCAL", null, false, false);
        define("CERT", null, true, true);
    }

    private void define(String code, String feeCode, boolean anyRegion, boolean concurrent) {
        ServiceSpec spec = new ServiceSpec(code, new BilingualName("خدمة", "Service"), "VEHICLE",
                List.of("INDIVIDUAL"), List.of(Channel.BACKOFFICE, Channel.PORTAL), List.of(), feeCode, null, null,
                "P5D", List.of("R1", "R2"), anyRegion, concurrent);
        services.insert(ServiceDefinition.create(spec, UUID.randomUUID(), CLERK, NOW));
    }

    private static Caller caller(Channel channel) {
        return new Caller(CLERK, channel, NATIONAL);
    }

    private static Caller counter() {
        return caller(Channel.BACKOFFICE);
    }

    private Transaction create(String code, Subject subject, String homeRegion, String region) {
        return service.create(new NewTransaction(code, subject, "TF1", homeRegion, region), counter());
    }

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    @Test
    @Tag("CC-024")
    @Tag("CC-023")
    @Tag("CC-120")
    @DisplayName("CC-024: Drafts not submitted within the configured period expire, by the system, oldest first, in batches")
    void draftExpiry() {
        Duration period = Duration.ofDays(30);
        String old1 = create("CERT", CAR, "R1", null).reference().toString();
        clock.now = NOW.plusSeconds(10);
        String old2 = create("CERT", CAR, "R1", null).reference().toString();
        String submitted = create("CERT", CAR, "R1", null).reference().toString();
        service.submit(submitted, counter());
        clock.now = NOW.plus(Duration.ofDays(29));
        String young = create("CERT", CAR, "R1", null).reference().toString();
        audit.entries.clear();

        // Exactly 30 days after the first Draft was opened: only that one is due.
        clock.now = NOW.plus(period);
        assertThat(service.expireDrafts(period, 10)).isEqualTo(1);
        Transaction expired = service.get(old1, counter());
        assertThat(expired.status()).isEqualTo(TransactionStatus.EXPIRED);
        StatusChange change = expired.lastChange();
        assertThat(change.reason()).isEqualTo(StatusReason.EXPIRED);
        assertThat(change.actor()).isEqualTo(Transaction.SYSTEM);
        assertThat(change.channel()).isEqualTo(ChangeChannel.SYSTEM);
        assertThat(change.previousStatus()).isEqualTo(TransactionStatus.DRAFT);
        assertThat(change.note()).isEqualTo("not submitted within PT720H");
        assertThat(audit.actions()).containsExactly("TRANSACTION_EXPIRED", "TRANSACTION_VIEWED");
        assertThat(audit.entries.getFirst().reason()).isEqualTo("not submitted within PT720H");
        assertThat(audit.systemActors).singleElement().satisfies(a -> {
            assertThat(a.userId()).isEqualTo("SYSTEM");
            assertThat(a.channel()).isEqualTo("SYSTEM");
        });

        // A batch of one, then nothing left; the submitted and the young Draft stay as they are.
        clock.now = NOW.plus(period).plusSeconds(10);
        assertThat(service.expireDrafts(period, 1)).isEqualTo(1);
        assertThat(service.expireDrafts(period, 1)).isZero();
        assertThat(service.get(old2, counter()).status()).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(service.get(submitted, counter()).status()).isEqualTo(TransactionStatus.IN_REVIEW);
        assertThat(service.get(young, counter()).status()).isEqualTo(TransactionStatus.DRAFT);

        // A shorter configured period applies to every Draft from the next run on (Q-051).
        assertThat(service.expireDrafts(Duration.ofDays(1), 10)).isEqualTo(1);
        assertThat(service.get(young, counter()).status()).isEqualTo(TransactionStatus.EXPIRED);

        // An expired Draft cannot be submitted or cancelled any more.
        assertThatThrownBy(() -> service.submit(old1, counter())).satisfies(code("ILLEGAL_TRANSITION"));
        assertThatThrownBy(() -> service.cancel(old1, "too late", counter())).satisfies(code("ILLEGAL_TRANSITION"));
        assertThat(service.history(old1, counter())).extracting(StatusChange::status)
                .containsExactly(TransactionStatus.DRAFT, TransactionStatus.EXPIRED);

        assertThatThrownBy(() -> service.expireDrafts(Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.expireDrafts(period, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.expireDrafts(null, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("CC-032")
    @DisplayName("CC-032: holds on the subject or the applicant's file block submission; the Draft stays a Draft")
    void eligibilityBeforeSubmission() {
        String ref = create("VL_RENEW", CAR, "R1", null).reference().toString();
        Subject file = new Subject(Subject.Type.TRAFFIC_FILE, "TF1");
        EligibilityChecker.Block fines = new EligibilityChecker.Block("UNPAID_FINES", "PAY_FINES", "Pay the fines.");
        EligibilityChecker.Block masked = new EligibilityChecker.Block("RESTRICTED", "CONTACT_AUTHORITY",
                "Contact the Authority.");
        eligibility.blocked.put(file, fines);
        eligibility.blocked.put(CAR, masked);
        audit.entries.clear();

        assertThatThrownBy(() -> service.submit(ref, caller(Channel.PORTAL)))
                .isInstanceOfSatisfying(EligibilityBlockedException.class, e -> {
                    assertThat(e.code()).isEqualTo("ELIGIBILITY_BLOCKED");
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.VALIDATION);
                    assertThat(e.requirementRefs()).containsExactly("CC-032");
                    assertThat(e.blocks()).containsExactly(masked, fines);
                });
        assertThat(eligibility.calls).containsExactly("VL_RENEW " + List.of(CAR, file) + " " + ref + " PORTAL");
        assertThat(transactions.rows.get(ref).status()).isEqualTo(TransactionStatus.DRAFT);
        assertThat(audit.entries).isEmpty();

        eligibility.unavailable = true;
        assertThatThrownBy(() -> service.submit(ref, counter())).satisfies(code("ELIGIBILITY_UNAVAILABLE"));
        assertThat(transactions.rows.get(ref).status()).isEqualTo(TransactionStatus.DRAFT);

        eligibility.unavailable = false;
        eligibility.blocked.clear();
        assertThat(service.submit(ref, counter()).status()).isEqualTo(TransactionStatus.PENDING_PAYMENT);

        // A transaction about the applicant's own file is checked once, for that file.
        eligibility.calls.clear();
        String own = create("DL_FREE", file, "R1", null).reference().toString();
        service.submit(own, counter());
        assertThat(eligibility.calls).containsExactly("DL_FREE " + List.of(file) + " " + own + " BACKOFFICE");

        // Not a Draft: refused before hold-eligibility-service is asked.
        eligibility.calls.clear();
        assertThatThrownBy(() -> service.submit(own, counter())).satisfies(code("ILLEGAL_TRANSITION"));
        assertThat(eligibility.calls).isEmpty();
    }

    @Test
    @Tag("CC-021")
    @DisplayName("CC-021/Q-003: references count per service, region and year and start again each year")
    void references() {
        assertThat(create("VL_RENEW", CAR, "R1", null).reference()).hasToString("VL_RENEW-R1-2026-0000001");
        assertThat(create("VL_RENEW", new Subject(Subject.Type.VEHICLE, "VIN2"), "R1", null).reference())
                .hasToString("VL_RENEW-R1-2026-0000002");
        assertThat(create("VL_RENEW", new Subject(Subject.Type.VEHICLE, "VIN3"), "R1", "R2").reference())
                .hasToString("VL_RENEW-R2-2026-0000001");
        assertThat(create("DL_FREE", CAR, "R1", null).reference()).hasToString("DL_FREE-R1-2026-0000001");
        clock.now = Instant.parse("2027-01-01T00:00:00Z");
        assertThat(create("VL_RENEW", new Subject(Subject.Type.VEHICLE, "VIN4"), "R1", null).reference())
                .hasToString("VL_RENEW-R1-2027-0000001");
    }

    @Test
    @Tag("CC-022")
    @Tag("CC-023")
    @Tag("CC-120")
    @DisplayName("CC-022/CC-023/CC-120: open, submit (fee → Pending payment) and cancel, each change in the history and audit")
    void lifecycleWithFee() {
        Transaction created = create("VL_RENEW", CAR, "R1", null);
        assertThat(created.status()).isEqualTo(TransactionStatus.DRAFT);
        assertThat(created.channel()).isEqualTo(Channel.BACKOFFICE);
        assertThat(created.serviceVersion()).isEqualTo(1);
        String ref = created.reference().toString();

        Transaction submitted = service.submit(ref, caller(Channel.PORTAL));
        assertThat(submitted.status()).isEqualTo(TransactionStatus.PENDING_PAYMENT);
        assertThat(submitted.submittedAt()).isEqualTo(NOW);
        Transaction cancelled = service.cancel(ref, "customer withdrew", counter());
        assertThat(cancelled.status()).isEqualTo(TransactionStatus.CANCELLED);

        List<StatusChange> history = service.history(ref, counter());
        assertThat(history).extracting(StatusChange::status).containsExactly(TransactionStatus.DRAFT,
                TransactionStatus.SUBMITTED, TransactionStatus.PENDING_PAYMENT, TransactionStatus.CANCELLED);
        assertThat(history).extracting(StatusChange::previousStatus).containsExactly(null, TransactionStatus.DRAFT,
                TransactionStatus.SUBMITTED, TransactionStatus.PENDING_PAYMENT);
        assertThat(history.get(1).channel()).isEqualTo(ChangeChannel.PORTAL);
        assertThat(history.get(2).actor()).isEqualTo(Transaction.SYSTEM);
        assertThat(history.get(3).note()).isEqualTo("customer withdrew");
        assertThat(service.get(ref, counter()).version()).isEqualTo(4);
        assertThat(audit.actions()).containsExactly("TRANSACTION_CREATED", "TRANSACTION_SUBMITTED",
                "TRANSACTION_CANCELLED", "TRANSACTION_HISTORY_VIEWED", "TRANSACTION_VIEWED");

        assertThatThrownBy(() -> service.submit(ref, counter())).satisfies(code("ILLEGAL_TRANSITION"));
    }

    @Test
    @Tag("CC-022")
    @DisplayName("CC-022/Q-004: a service without a fee goes to In review with the skip recorded")
    void zeroFee() {
        String ref = create("DL_FREE", CAR, "R1", null).reference().toString();
        assertThat(service.submit(ref, counter()).status()).isEqualTo(TransactionStatus.IN_REVIEW);
        StatusChange skip = service.history(ref, counter()).get(2);
        assertThat(skip.reason()).isEqualTo(StatusReason.ZERO_FEE_PAYMENT_SKIPPED);
        assertThat(skip.actor()).isEqualTo(Transaction.SYSTEM);
        assertThatThrownBy(() -> service.cancel(ref, "too late now", counter())).satisfies(code("ILLEGAL_TRANSITION"));
    }

    @Test
    @Tag("CC-027")
    @DisplayName("CC-027: one open transaction of a service per subject, unless the service allows several")
    void duplicates() {
        String first = create("VL_RENEW", CAR, "R1", null).reference().toString();
        assertThatThrownBy(() -> create("VL_RENEW", CAR, "R2", "R2")).satisfies(code("DUPLICATE_OPEN_TRANSACTION"))
                .satisfies(t -> assertThat(((DomainException) t).args()).containsExactly("VL_RENEW",
                        " (" + first + ")"));
        // Another subject type with the same number, or another service, is not a duplicate.
        create("VL_RENEW", new Subject(Subject.Type.PLATE, "VIN1"), "R1", null);
        create("DL_FREE", CAR, "R1", null);
        // A caller who may not read the open one is not told its reference.
        Caller narrow = new Caller(CLERK, Channel.BACKOFFICE, (p, region, m) -> !p.equals(TransactionService.READ));
        assertThatThrownBy(() -> service.create(new NewTransaction("VL_RENEW", CAR, "TF1", "R1", null), narrow))
                .satisfies(t -> assertThat(((DomainException) t).args()).containsExactly("VL_RENEW", ""));
        // Once the open one is final, a new one may be opened.
        service.cancel(first, "opened by mistake", counter());
        assertThat(create("VL_RENEW", CAR, "R1", null).status()).isEqualTo(TransactionStatus.DRAFT);
        // A service that allows several.
        create("CERT", CAR, "R1", null);
        assertThat(create("CERT", CAR, "R1", null).reference()).hasToString("CERT-R1-2026-0000002");
    }

    @Test
    @Tag("CC-113")
    @Tag("CC-026")
    @Tag("CC-112")
    @DisplayName("CC-113/CC-026/CC-112: offered region and channel, availability rules and the home-region exception")
    void accessRules() {
        assertThat(create("VL_RENEW", CAR, "R1", "R2").region()).isEqualTo("R2");
        assertThatThrownBy(() -> create("VL_LOCAL", CAR, "R1", "R2")).satisfies(code("HOME_REGION_ONLY"));
        assertThat(create("VL_LOCAL", CAR, "R1", null).region()).isEqualTo("R1");
        assertThatThrownBy(() -> create("DL_FREE", CAR, "R9", null)).satisfies(code("SERVICE_NOT_AVAILABLE"));
        assertThatThrownBy(() -> service.create(new NewTransaction("DL_FREE", CAR, "TF1", "R1", null),
                caller(Channel.KIOSK))).satisfies(code("SERVICE_NOT_AVAILABLE"));
        assertThatThrownBy(() -> create("NOPE", CAR, "R1", null)).satisfies(code("UNKNOWN_SERVICE"));
        assertThatThrownBy(() -> service.create(new NewTransaction("DL_FREE", CAR, "TF1", "R1", null), caller(null)))
                .satisfies(code("CHANNEL_REQUIRED"));

        // Disabled after the Draft was opened: the submission is refused (Q-049).
        String ref = create("DL_FREE", new Subject(Subject.Type.TRAFFIC_FILE, "TF1"), "R1", null).reference()
                .toString();
        rules.insert(AvailabilityRule.add("DL_FREE", new AvailabilityRuleSpec("R1", Channel.BACKOFFICE, false,
                LocalDate.of(2026, 10, 5), null), UUID.randomUUID()), CLERK, NOW);
        assertThatThrownBy(() -> service.submit(ref, counter())).satisfies(code("SERVICE_NOT_AVAILABLE"));
        assertThat(service.get(ref, counter()).status()).isEqualTo(TransactionStatus.DRAFT);
        assertThat(service.submit(ref, caller(Channel.PORTAL)).status()).isEqualTo(TransactionStatus.IN_REVIEW);
    }

    @Test
    @Tag("CC-025")
    @Tag("CC-120")
    @DisplayName("CC-025/CC-120: an applicant's transactions, newest first, page by page, in scope only, view audited")
    void tracking() {
        List<String> refs = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            clock.now = NOW.plusSeconds(i);
            refs.add(service.create(new NewTransaction("CERT", CAR, "TF7", "R1", i == 3 ? "R2" : null), counter())
                    .reference().toString());
        }
        service.create(new NewTransaction("CERT", CAR, "TF8", "R1", null), counter());
        List<String> newestFirst = refs.reversed();

        TransactionService.TransactionPage first = service.byApplicant("TF7", null, null, 2, counter());
        assertThat(first.items()).extracting(t -> t.reference().toString()).containsExactlyElementsOf(
                newestFirst.subList(0, 2));
        TransactionService.TransactionPage second = service.byApplicant("TF7", null, first.nextCursor(), 2, counter());
        assertThat(second.items()).extracting(t -> t.reference().toString())
                .containsExactlyElementsOf(newestFirst.subList(2, 4));
        TransactionService.TransactionPage last = service.byApplicant("TF7", null, second.nextCursor(), 2, counter());
        assertThat(last.items()).extracting(t -> t.reference().toString()).containsExactly(newestFirst.get(4));
        assertThat(last.nextCursor()).isNull();
        assertThat(service.byApplicant("TF7", null, null, null, counter()).items()).hasSize(5);

        // Out of scope (R2) is left out, but paging goes on past it.
        Caller r1Only = new Caller(CLERK, Channel.BACKOFFICE, (p, region, module) -> "R1".equals(region));
        TransactionService.TransactionPage scoped = service.byApplicant("TF7", null, first.nextCursor(), 2, r1Only);
        assertThat(scoped.items()).extracting(t -> t.reference().toString()).containsExactly(newestFirst.get(3));
        assertThat(scoped.nextCursor()).isNotNull();

        // Status filter; tracking works whatever channel the transaction was opened through.
        service.submit(refs.get(0), caller(Channel.PORTAL));
        assertThat(service.byApplicant("TF7", TransactionStatus.IN_REVIEW, null, 10, caller(null)).items())
                .extracting(t -> t.reference().toString()).containsExactly(refs.get(0));
        assertThat(audit.entries.getLast().action()).isEqualTo("TRANSACTION_LIST_VIEWED");
        assertThat(audit.entries.getLast().entityId()).isEqualTo("TF7");
        assertThat(audit.entries.getLast().newValue()).isEqualTo(java.util.Map.of("references", List.of(refs.get(0))));

        assertThatThrownBy(() -> service.byApplicant("TF7", null, null, 0, counter())).satisfies(code("LIMIT_INVALID"));
        assertThatThrownBy(() -> service.byApplicant("TF7", null, null, 201, counter()))
                .satisfies(code("LIMIT_INVALID"));
        assertThatThrownBy(() -> service.byApplicant("tf7", null, null, 10, counter()))
                .satisfies(code("BUSINESS_NUMBER_INVALID"));
        assertThatThrownBy(() -> service.byApplicant("TF7", null, "junk", 10, counter()))
                .satisfies(code("CURSOR_INVALID"));
        assertThat(service.byApplicant("TF9", null, null, 10, counter()).items()).isEmpty();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: each permission must reach the region and module; otherwise the transaction is not found")
    void scopes() {
        Caller r1Only = new Caller(CLERK, Channel.BACKOFFICE, (p, region, module) -> "R1".equals(region));
        assertThatThrownBy(() -> service.create(new NewTransaction("VL_RENEW", CAR, "TF1", "R1", "R2"), r1Only))
                .satisfies(code("SCOPE_EXCEEDED"));
        String ref = create("VL_RENEW", CAR, "R1", "R2").reference().toString();
        assertThatThrownBy(() -> service.get(ref, r1Only)).satisfies(code("TRANSACTION_NOT_FOUND"));
        assertThatThrownBy(() -> service.history(ref, r1Only)).satisfies(code("TRANSACTION_NOT_FOUND"));
        assertThatThrownBy(() -> service.submit(ref, r1Only)).satisfies(code("TRANSACTION_NOT_FOUND"));
        assertThatThrownBy(() -> service.cancel(ref, "not mine", r1Only)).satisfies(code("TRANSACTION_NOT_FOUND"));

        Set<String> readOnly = Set.of(TransactionService.READ);
        Caller reader = new Caller(CLERK, Channel.BACKOFFICE, (p, region, module) -> readOnly.contains(p));
        assertThat(service.get(ref, reader).reference()).hasToString(ref);
        assertThatThrownBy(() -> service.cancel(ref, "no right", reader)).satisfies(code("TRANSACTION_NOT_FOUND"));
        assertThatThrownBy(() -> service.get("VL_RENEW-R2-2026-0000099", counter()))
                .satisfies(code("TRANSACTION_NOT_FOUND"));
        assertThatThrownBy(() -> service.get("not-a-reference", counter())).satisfies(code("TRANSACTION_NOT_FOUND"));
    }
}
