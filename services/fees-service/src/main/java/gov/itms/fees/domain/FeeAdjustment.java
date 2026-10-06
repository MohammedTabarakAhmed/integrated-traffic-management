package gov.itms.fees.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An adjustment to a service's fees (ADR-047), under the same maker-checker as fee versions (CC-046):
 * <ul>
 *   <li>EXEMPTION (CC-043): the fee is waived for a customer category or a campaign;</li>
 *   <li>DISCOUNT (CC-043): a percentage or a fixed amount off, for a customer category or a campaign (for example a
 *       time-limited fine discount);</li>
 *   <li>LEVY (CC-045): a percentage added on top of the (discounted) fees, such as value-added tax, paid to its own
 *       financial item and beneficiary.</li>
 * </ul>
 * An adjustment applies to some fee codes of the service, or to all of them when none are listed, between its
 * effective dates.
 */
public final class FeeAdjustment {

    public enum Kind { EXEMPTION, DISCOUNT, LEVY }

    /** What a maker proposes. */
    public record Spec(String code, Kind kind, String serviceCode, String nameNl, String nameEn, List<String> feeCodes,
                       String customerCategory, String campaignCode, BigDecimal percent, Long amount,
                       String financialItem, String beneficiary, LocalDate effectiveFrom, LocalDate effectiveTo) {
        public Spec {
            code = Codes.code("code", code);
            Objects.requireNonNull(kind, "kind");
            serviceCode = Codes.serviceCode(serviceCode);
            nameNl = Codes.name("nameNl", nameNl, true);
            nameEn = Codes.name("nameEn", nameEn, true);
            feeCodes = feeCodes == null ? List.of() : List.copyOf(feeCodes);
            if (feeCodes.size() > 50 || feeCodes.stream().distinct().count() != feeCodes.size()) {
                throw Codes.invalid("feeCodes");
            }
            feeCodes.forEach(c -> Codes.code("feeCodes", c));
            customerCategory = Codes.optionalCode("customerCategory", customerCategory);
            campaignCode = Codes.optionalCode("campaignCode", campaignCode);
            if (percent != null && (percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0
                    || percent.scale() > 2)) {
                throw Codes.invalid("percent");
            }
            if (amount != null) {
                Codes.amount("amount", amount);
            }
            financialItem = Codes.optionalCode("financialItem", financialItem);
            beneficiary = Codes.optionalCode("beneficiary", beneficiary);
            if (effectiveFrom == null) {
                throw Codes.invalid("effectiveFrom");
            }
            if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
                throw DomainException.validation("FEE_DATES_INVALID", "the end date is before the start date",
                        List.of("CC-043"));
            }
            switch (kind) {
                case EXEMPTION, DISCOUNT -> {
                    if (customerCategory == null && campaignCode == null) {
                        throw DomainException.validation("ADJUSTMENT_TARGET_REQUIRED",
                                "an exemption or discount is for a customer category or a campaign",
                                List.of("CC-043"));
                    }
                    if (financialItem != null || beneficiary != null) {
                        throw Codes.invalid("financialItem");
                    }
                    if (kind == Kind.EXEMPTION && (percent != null || amount != null)) {
                        throw Codes.invalid("percent");
                    }
                    if (kind == Kind.DISCOUNT && (percent == null) == (amount == null)) {
                        throw DomainException.validation("DISCOUNT_VALUE_REQUIRED",
                                "a discount is either a percentage or an amount", List.of("CC-043"));
                    }
                }
                case LEVY -> {
                    if (percent == null || amount != null || financialItem == null || beneficiary == null) {
                        throw DomainException.validation("LEVY_INVALID",
                                "a levy has a percentage, a financial item and a beneficiary", List.of("CC-045"));
                    }
                }
            }
        }

        boolean coversFee(String feeCode) {
            return feeCodes.isEmpty() || feeCodes.contains(feeCode);
        }
    }

    private final UUID id;
    private final Spec spec;
    private final FeeVersion.Decision submitted;
    private FeeVersion.Status status;
    private FeeVersion.Decision decided;

    private FeeAdjustment(UUID id, Spec spec, FeeVersion.Decision submitted, FeeVersion.Status status,
                          FeeVersion.Decision decided) {
        this.id = Objects.requireNonNull(id);
        this.spec = Objects.requireNonNull(spec);
        this.submitted = Objects.requireNonNull(submitted);
        this.status = Objects.requireNonNull(status);
        this.decided = decided;
    }

    public static FeeAdjustment submit(UUID id, Spec spec, String reason, Actor maker, Channel channel, Instant now,
                                       LocalDate today) {
        if (spec.effectiveFrom().isBefore(today)) {
            throw DomainException.validation("FEE_START_IN_PAST", "an adjustment cannot start in the past",
                    List.of("CC-046"));
        }
        return new FeeAdjustment(id, spec, new FeeVersion.Decision(maker, channel, now, FeeVersion.reason(reason)),
                FeeVersion.Status.PENDING, null);
    }

    public static FeeAdjustment restore(UUID id, Spec spec, FeeVersion.Decision submitted, FeeVersion.Status status,
                                        FeeVersion.Decision decided) {
        return new FeeAdjustment(id, spec, submitted, status, decided);
    }

    public void approve(Actor checker, Channel channel, String reason, Instant now, LocalDate today) {
        requirePending();
        if (checker.id().equals(submitted.actor().id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                    "the person who submitted a fee change cannot approve it", List.of("CC-046", "CC-002"));
        }
        if (spec.effectiveFrom().isBefore(today)) {
            throw DomainException.validation("FEE_START_IN_PAST", "an adjustment cannot start in the past",
                    List.of("CC-046"));
        }
        status = FeeVersion.Status.APPROVED;
        decided = new FeeVersion.Decision(checker, channel, now, reason == null ? null : FeeVersion.reason(reason));
    }

    public void reject(Actor checker, Channel channel, String reason, Instant now) {
        requirePending();
        status = FeeVersion.Status.REJECTED;
        decided = new FeeVersion.Decision(checker, channel, now, FeeVersion.reason(reason));
    }

    private void requirePending() {
        if (status != FeeVersion.Status.PENDING) {
            throw DomainException.conflict("FEE_VERSION_DECIDED", "this adjustment is already " + status,
                    List.of("CC-046"), status.name());
        }
    }

    /** True if approved, in force on the request date and matching its customer category or campaign. */
    public boolean appliesTo(Quote.Request request) {
        if (status != FeeVersion.Status.APPROVED || !spec.serviceCode().equals(request.serviceCode())
                || spec.effectiveFrom().isAfter(request.date())
                || (spec.effectiveTo() != null && spec.effectiveTo().isBefore(request.date()))) {
            return false;
        }
        if (spec.kind() == Kind.LEVY) {
            return true;
        }
        return (spec.customerCategory() != null && spec.customerCategory().equals(request.customerCategory()))
                || (spec.campaignCode() != null && spec.campaignCode().equals(request.campaignCode()));
    }

    /** How much this exemption or discount takes off a line of {@code gross} minor units (never more than gross). */
    public long reduction(long gross) {
        return switch (spec.kind()) {
            case EXEMPTION -> gross;
            case DISCOUNT -> spec.percent() != null ? percentOf(gross, spec.percent()) : Math.min(gross, spec.amount());
            case LEVY -> 0;
        };
    }

    /** The levy on a net amount (CC-045). */
    public long levyOn(long net) {
        return spec.kind() == Kind.LEVY ? percentOf(net, spec.percent()) : 0;
    }

    static long percentOf(long amount, BigDecimal percent) {
        return BigDecimal.valueOf(amount).multiply(percent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public UUID id() {
        return id;
    }

    public Spec spec() {
        return spec;
    }

    public FeeVersion.Decision submitted() {
        return submitted;
    }

    public FeeVersion.Status status() {
        return status;
    }

    public FeeVersion.Decision decided() {
        return decided;
    }
}
