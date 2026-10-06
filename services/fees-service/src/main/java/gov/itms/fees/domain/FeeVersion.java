package gov.itms.fees.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One version of a fee (CC-040, CC-041, CC-042, CC-046; ADR-046): which service it belongs to, where and to whom it
 * applies, how it is calculated, which financial item and beneficiary entity it is paid to, and from when to when.
 *
 * <p>A version is submitted PENDING by a maker and decided once by a different checker (CC-046, CC-002): APPROVED or
 * REJECTED. It never changes after that, except that approving a successor ends it the day before the successor
 * starts. A fee never applies retroactively: a version must start today or later, also on the day it is approved.
 * Quotes copy the amounts, so a later version never changes a fee already quoted or paid (CC-046).
 */
public final class FeeVersion {

    public enum Status { PENDING, APPROVED, REJECTED }

    /**
     * Where and to whom the version applies (CC-040). {@code null} means "any". The most specific applicable version
     * of a fee wins in a quote.
     */
    public record Applicability(String region, String customerCategory, String subjectCategory) {
        public static final Applicability ANY = new Applicability(null, null, null);

        public Applicability {
            region = Codes.optionalCode("region", region);
            customerCategory = Codes.optionalCode("customerCategory", customerCategory);
            subjectCategory = Codes.optionalCode("subjectCategory", subjectCategory);
        }

        public int specificity() {
            return (region == null ? 0 : 1) + (customerCategory == null ? 0 : 1) + (subjectCategory == null ? 0 : 1);
        }

        public boolean matches(String region, String customerCategory, String subjectCategory) {
            return (this.region == null || this.region.equals(region))
                    && (this.customerCategory == null || this.customerCategory.equals(customerCategory))
                    && (this.subjectCategory == null || this.subjectCategory.equals(subjectCategory));
        }
    }

    /** What a maker proposes. */
    public record Spec(String feeCode, String serviceCode, String nameNl, String nameEn, Applicability applicability,
                       String financialItem, String beneficiary, FeeMethod method, LocalDate effectiveFrom,
                       LocalDate effectiveTo) {
        public Spec {
            feeCode = Codes.code("feeCode", feeCode);
            serviceCode = Codes.serviceCode(serviceCode);
            nameNl = Codes.name("nameNl", nameNl, true);
            nameEn = Codes.name("nameEn", nameEn, true);
            applicability = applicability == null ? Applicability.ANY : applicability;
            financialItem = Codes.code("financialItem", financialItem);
            beneficiary = Codes.code("beneficiary", beneficiary);
            Objects.requireNonNull(method, "method");
            if (effectiveFrom == null) {
                throw Codes.invalid("effectiveFrom");
            }
            if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
                throw DomainException.validation("FEE_DATES_INVALID", "the end date is before the start date",
                        List.of("CC-040"));
            }
        }
    }

    /** Who submitted or decided, when, through which channel, and why. */
    public record Decision(Actor actor, Channel channel, Instant at, String reason) {
        public Decision {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(channel);
            Objects.requireNonNull(at);
        }
    }

    private final UUID id;
    private final int versionNo;
    private final Spec spec;
    private final String currency;
    private final Decision submitted;
    private Status status;
    private Decision decided;
    private LocalDate effectiveTo;

    private FeeVersion(UUID id, int versionNo, Spec spec, String currency, Decision submitted, Status status,
                       Decision decided, LocalDate effectiveTo) {
        this.id = Objects.requireNonNull(id);
        this.versionNo = versionNo;
        this.spec = Objects.requireNonNull(spec);
        this.currency = Objects.requireNonNull(currency);
        this.submitted = Objects.requireNonNull(submitted);
        this.status = Objects.requireNonNull(status);
        this.decided = decided;
        this.effectiveTo = effectiveTo;
    }

    public static FeeVersion submit(UUID id, int versionNo, Spec spec, String currency, String reason, Actor maker,
                                    Channel channel, Instant now, LocalDate today) {
        requireNotPast(spec.effectiveFrom(), today);
        return new FeeVersion(id, versionNo, spec, currency, new Decision(maker, channel, now, reason(reason)),
                Status.PENDING, null, spec.effectiveTo());
    }

    public static FeeVersion restore(UUID id, int versionNo, Spec spec, String currency, Decision submitted,
                                     Status status, Decision decided, LocalDate effectiveTo) {
        return new FeeVersion(id, versionNo, spec, currency, submitted, status, decided, effectiveTo);
    }

    /** Approves the version; the checker must not be the maker (CC-046, CC-002). */
    public void approve(Actor checker, Channel channel, String reason, Instant now, LocalDate today) {
        requirePending();
        if (checker.id().equals(submitted.actor().id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                    "the person who submitted a fee change cannot approve it", List.of("CC-046", "CC-002"));
        }
        requireNotPast(spec.effectiveFrom(), today);
        this.status = Status.APPROVED;
        this.decided = new Decision(checker, channel, now, reason == null ? null : reason(reason));
    }

    /** Rejects the version with a reason; the maker may withdraw their own change this way. */
    public void reject(Actor checker, Channel channel, String reason, Instant now) {
        requirePending();
        this.status = Status.REJECTED;
        this.decided = new Decision(checker, channel, now, reason(reason));
    }

    /** Ends an approved version the day before {@code successorStart} (a successor was approved). */
    public void endBefore(LocalDate successorStart) {
        if (status != Status.APPROVED || !spec.effectiveFrom().isBefore(successorStart)) {
            throw new IllegalStateException("only an approved version that started earlier can be ended");
        }
        LocalDate end = successorStart.minusDays(1);
        if (effectiveTo == null || effectiveTo.isAfter(end)) {
            effectiveTo = end;
        }
    }

    /** True if approved and in force on {@code day}. */
    public boolean inForce(LocalDate day) {
        return status == Status.APPROVED && !spec.effectiveFrom().isAfter(day)
                && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    /** True if both cover a common day (approved ranges of the same fee and applicability must not overlap). */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return (effectiveTo == null || !effectiveTo.isBefore(from)) && (to == null || !spec.effectiveFrom().isAfter(to));
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw DomainException.conflict("FEE_VERSION_DECIDED", "this fee version is already " + status,
                    List.of("CC-046"), status.name());
        }
    }

    private static void requireNotPast(LocalDate from, LocalDate today) {
        if (from.isBefore(today)) {
            throw DomainException.validation("FEE_START_IN_PAST",
                    "a fee version cannot start in the past (fees never change retroactively)", List.of("CC-046"));
        }
    }

    static String reason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("CC-046"));
        }
        return v;
    }

    public UUID id() {
        return id;
    }

    public int versionNo() {
        return versionNo;
    }

    public Spec spec() {
        return spec;
    }

    public String currency() {
        return currency;
    }

    public Decision submitted() {
        return submitted;
    }

    public Status status() {
        return status;
    }

    public Decision decided() {
        return decided;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }
}
