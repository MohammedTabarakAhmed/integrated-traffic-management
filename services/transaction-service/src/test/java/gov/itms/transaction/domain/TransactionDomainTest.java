package gov.itms.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TransactionDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Actor CLERK = new Actor("u-clerk", "clerk");
    private static final Subject VEHICLE = new Subject(Subject.Type.VEHICLE, "VIN123");

    private static ServiceSpec spec(String feeCode, boolean anyRegion) {
        return new ServiceSpec("VL_RENEW", new BilingualName("تجديد", "Renewal"), "VEHICLE", List.of("INDIVIDUAL"),
                List.of(Channel.BACKOFFICE, Channel.PORTAL), List.of(), feeCode, null, null, "P5D",
                List.of("R1", "R2"), anyRegion, false);
    }

    private static Transaction open(ServiceSpec spec, String region, String homeRegion) {
        ServiceDefinition definition = ServiceDefinition.create(spec, UUID.randomUUID(), CLERK, NOW);
        Availability availability = Availability.of(spec, List.of(), region, Channel.BACKOFFICE, TODAY);
        return Transaction.open(UUID.randomUUID(), new TransactionReference(spec.code(), region, 2026, 1),
                definition, availability, VEHICLE, "TF100", homeRegion, CLERK, NOW);
    }

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    @Test
    @Tag("CC-021")
    @DisplayName("CC-021: reference <ServiceCode>-<Region>-<YYYY>-<Sequence> with a 7-digit sequence (Q-003)")
    void referenceFormat() {
        TransactionReference reference = new TransactionReference("VL_RENEW", "R1", 2026, 42);
        assertThat(reference).hasToString("VL_RENEW-R1-2026-0000042");
        assertThat(TransactionReference.parse("VL_RENEW-R1-2026-0000042")).isEqualTo(reference);
        assertThat(new TransactionReference("VL_RENEW", "R1", 2026, TransactionReference.MAX_SEQUENCE))
                .hasToString("VL_RENEW-R1-2026-9999999");
        assertThatThrownBy(() -> new TransactionReference("VL_RENEW", "R1", 2026, 10_000_000))
                .satisfies(code("REFERENCE_SEQUENCE_EXHAUSTED"));
        for (String bad : new String[] {"VL_RENEW-R1-2026-42", "vl_renew-R1-2026-0000042", "VL_RENEW-R1-26-0000042",
                "VL_RENEW-R1-2026-0000000", "VL_RENEW-R-1-2026-0000001", "VL_RENEW-R1-0000-0000001", ""}) {
            assertThatThrownBy(() -> TransactionReference.parse(bad)).as(bad)
                    .satisfies(code("TRANSACTION_NOT_FOUND"));
        }
        assertThatThrownBy(() -> TransactionReference.parse(null)).satisfies(code("TRANSACTION_NOT_FOUND"));
        assertThatThrownBy(() -> new TransactionReference("VL_RENEW", "R1", 2026, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionReference("VL_RENEW", "R1", 10_000, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("CC-022")
    @DisplayName("CC-022: the lifecycle allows exactly the transitions of LLD §2.1")
    void lifecycleTransitions() {
        Map<TransactionStatus, Set<TransactionStatus>> expected = Map.ofEntries(
                Map.entry(TransactionStatus.DRAFT, EnumSet.of(TransactionStatus.SUBMITTED, TransactionStatus.EXPIRED,
                        TransactionStatus.CANCELLED)),
                Map.entry(TransactionStatus.SUBMITTED, EnumSet.of(TransactionStatus.PENDING_PAYMENT,
                        TransactionStatus.IN_REVIEW)),
                Map.entry(TransactionStatus.PENDING_PAYMENT, EnumSet.of(TransactionStatus.PAID,
                        TransactionStatus.EXPIRED, TransactionStatus.CANCELLED)),
                Map.entry(TransactionStatus.PAID, EnumSet.of(TransactionStatus.IN_REVIEW)),
                Map.entry(TransactionStatus.IN_REVIEW, EnumSet.of(TransactionStatus.RETURNED_FOR_INFO,
                        TransactionStatus.APPROVED, TransactionStatus.REJECTED)),
                Map.entry(TransactionStatus.RETURNED_FOR_INFO, EnumSet.of(TransactionStatus.IN_REVIEW,
                        TransactionStatus.EXPIRED)),
                Map.entry(TransactionStatus.APPROVED, EnumSet.of(TransactionStatus.COMPLETED)),
                Map.entry(TransactionStatus.REJECTED, Set.of()),
                Map.entry(TransactionStatus.COMPLETED, Set.of()),
                Map.entry(TransactionStatus.CANCELLED, Set.of()),
                Map.entry(TransactionStatus.EXPIRED, Set.of()));
        for (TransactionStatus from : TransactionStatus.values()) {
            assertThat(from.next()).as(from.name()).isEqualTo(expected.get(from));
            for (TransactionStatus to : TransactionStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected.get(from).contains(to));
            }
            assertThat(from.isOpen()).as(from.name()).isEqualTo(!expected.get(from).isEmpty());
        }
    }

    @Test
    @Tag("CC-022")
    @Tag("CC-023")
    @DisplayName("CC-022/CC-023: a service with a fee goes through every step; each change records who, how, when, why and from what")
    void fullPathWithFee() {
        Transaction t = open(spec("VL_RENEW_FEE", true), "R1", "R1");
        assertThat(t.status()).isEqualTo(TransactionStatus.DRAFT);
        assertThat(t.lastChange()).isEqualTo(new StatusChange(1, null, TransactionStatus.DRAFT, StatusReason.CREATED,
                null, CLERK, Channel.BACKOFFICE, NOW));
        assertThat(t.paymentRequired()).isTrue();

        Instant later = NOW.plusSeconds(60);
        StatusChange submitted = t.submit(CLERK, Channel.PORTAL, later);
        assertThat(submitted).isEqualTo(new StatusChange(2, TransactionStatus.DRAFT, TransactionStatus.SUBMITTED,
                StatusReason.SUBMITTED, null, CLERK, Channel.PORTAL, later));
        assertThat(t.submittedAt()).isEqualTo(later);
        StatusChange routed = t.routePayment(Channel.PORTAL, later);
        assertThat(routed.status()).isEqualTo(TransactionStatus.PENDING_PAYMENT);
        assertThat(routed.reason()).isEqualTo(StatusReason.PAYMENT_REQUIRED);
        assertThat(routed.actor()).isEqualTo(Transaction.SYSTEM);

        t.confirmPayment(Transaction.SYSTEM, Channel.PORTAL, later);
        t.startReview(CLERK, Channel.BACKOFFICE, later);
        StatusChange returned = t.returnForInfo(CLERK, Channel.BACKOFFICE, "  insurance copy missing ", later);
        assertThat(returned.note()).isEqualTo("insurance copy missing");
        t.provideInfo(CLERK, Channel.PORTAL, later);
        t.approve(CLERK, Channel.BACKOFFICE, later);
        StatusChange completed = t.complete(CLERK, Channel.BACKOFFICE, later);
        assertThat(completed.previousStatus()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(t.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(t.version()).isEqualTo(9);
        assertThat(t.status().isOpen()).isFalse();
    }

    @Test
    @Tag("CC-022")
    @DisplayName("CC-022/Q-004: a service without a fee skips the payment steps, and the skip is recorded")
    void zeroFeeSkipsPayment() {
        Transaction t = open(spec(null, true), "R1", "R1");
        assertThat(t.paymentRequired()).isFalse();
        t.submit(CLERK, Channel.BACKOFFICE, NOW);
        StatusChange skip = t.routePayment(Channel.BACKOFFICE, NOW);
        assertThat(skip.previousStatus()).isEqualTo(TransactionStatus.SUBMITTED);
        assertThat(skip.status()).isEqualTo(TransactionStatus.IN_REVIEW);
        assertThat(skip.reason()).isEqualTo(StatusReason.ZERO_FEE_PAYMENT_SKIPPED);
        assertThat(skip.actor()).isEqualTo(Transaction.SYSTEM);
        assertThatThrownBy(() -> t.confirmPayment(CLERK, Channel.BACKOFFICE, NOW)).satisfies(code("ILLEGAL_TRANSITION"));
        StatusChange rejected = t.reject(CLERK, Channel.BACKOFFICE, "documents not valid", NOW);
        assertThat(rejected.status()).isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    @Tag("CC-022")
    @DisplayName("CC-022: illegal transitions are refused; cancel, reject and return need a reason")
    void illegalTransitionsAndReasons() {
        Transaction t = open(spec("FEE", true), "R1", "R1");
        assertThatThrownBy(() -> t.approve(CLERK, Channel.BACKOFFICE, NOW)).satisfies(code("ILLEGAL_TRANSITION"));
        assertThatThrownBy(() -> t.routePayment(Channel.BACKOFFICE, NOW)).satisfies(code("ILLEGAL_TRANSITION"));
        assertThatThrownBy(() -> t.cancel(CLERK, Channel.BACKOFFICE, " x ", NOW)).satisfies(code("REASON_REQUIRED"));
        assertThatThrownBy(() -> t.submit(CLERK, Channel.BACKOFFICE, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(t.status()).isEqualTo(TransactionStatus.DRAFT);

        t.submit(CLERK, Channel.BACKOFFICE, NOW);
        assertThatThrownBy(() -> t.cancel(CLERK, Channel.BACKOFFICE, "changed my mind", NOW))
                .satisfies(code("ILLEGAL_TRANSITION"));
        t.routePayment(Channel.BACKOFFICE, NOW);
        StatusChange cancelled = t.cancel(CLERK, Channel.BACKOFFICE, "changed my mind", NOW);
        assertThat(cancelled.note()).isEqualTo("changed my mind");
        assertThat(t.status().isOpen()).isFalse();
        assertThatThrownBy(() -> t.expire(Channel.BACKOFFICE, NOW)).satisfies(code("ILLEGAL_TRANSITION"));

        Transaction draft = open(spec(null, true), "R1", "R1");
        assertThat(draft.expire(Channel.BACKOFFICE, NOW).actor()).isEqualTo(Transaction.SYSTEM);
        Transaction review = open(spec(null, true), "R1", "R1");
        review.submit(CLERK, Channel.BACKOFFICE, NOW);
        review.routePayment(Channel.BACKOFFICE, NOW);
        assertThatThrownBy(() -> review.returnForInfo(CLERK, Channel.BACKOFFICE, null, NOW))
                .satisfies(code("REASON_REQUIRED"));
        assertThatThrownBy(() -> review.reject(CLERK, Channel.BACKOFFICE, "", NOW)).satisfies(code("REASON_REQUIRED"));
    }

    @Test
    @Tag("CC-113")
    @Tag("CC-026")
    @DisplayName("CC-113/CC-026: only where available; a home-region service only in the applicant's home region")
    void access() {
        assertThat(open(spec(null, true), "R2", "R1").homeRegion()).isEqualTo("R1");
        assertThatThrownBy(() -> open(spec(null, false), "R2", "R1")).satisfies(code("HOME_REGION_ONLY"));
        assertThat(open(spec(null, false), "R1", "R1").region()).isEqualTo("R1");
        assertThatThrownBy(() -> open(spec(null, true), "R3", "R3")).satisfies(code("SERVICE_NOT_AVAILABLE"));
        assertThatThrownBy(() -> open(spec(null, true), "R1", "r1")).satisfies(code("INVALID_CODE"));

        ServiceSpec spec = spec(null, true);
        Availability kiosk = Availability.of(spec, List.of(), "R1", Channel.KIOSK, TODAY);
        assertThatThrownBy(() -> Transaction.checkAccess(spec, kiosk, "R1")).satisfies(code("SERVICE_NOT_AVAILABLE"));

        ServiceDefinition definition = ServiceDefinition.create(spec, UUID.randomUUID(), CLERK, NOW);
        Availability r1 = Availability.of(spec, List.of(), "R1", Channel.BACKOFFICE, TODAY);
        assertThatThrownBy(() -> Transaction.open(UUID.randomUUID(), new TransactionReference("VL_RENEW", "R2", 2026,
                1), definition, r1, VEHICLE, "TF1", "R1", CLERK, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("CC-027")
    @DisplayName("CC-027: subjects and applicants use one upper-case spelling")
    void subjects() {
        assertThat(new Subject(Subject.Type.PLATE, "A-12345").id()).isEqualTo("A-12345");
        for (String bad : new String[] {"vin123", "-A1", "", "A".repeat(41), "A 1", "A_1"}) {
            assertThatThrownBy(() -> new Subject(Subject.Type.VEHICLE, bad)).as(bad)
                    .satisfies(code("BUSINESS_NUMBER_INVALID"));
        }
        assertThatThrownBy(() -> new Subject(null, "A1")).satisfies(code("BUSINESS_NUMBER_INVALID"));
        assertThatThrownBy(() -> new Subject(Subject.Type.VEHICLE, null)).satisfies(code("BUSINESS_NUMBER_INVALID"));
        assertThatThrownBy(() -> new StatusChange(2, null, TransactionStatus.SUBMITTED, StatusReason.SUBMITTED, null,
                CLERK, Channel.APP, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
