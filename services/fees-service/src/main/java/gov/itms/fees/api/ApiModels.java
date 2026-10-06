package gov.itms.fees.api;

import gov.itms.fees.domain.Channel;
import gov.itms.fees.domain.Codes;
import gov.itms.fees.domain.FeeAdjustment;
import gov.itms.fees.domain.FeeMethod;
import gov.itms.fees.domain.FeeVersion;
import gov.itms.fees.domain.Quote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of {@code fees-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    enum MethodType { FIXED, PER_UNIT, TIERED, FORMULA, LATE }

    record TierDto(@Pattern(regexp = Codes.DECIMAL) String upTo, @NotNull @Pattern(regexp = Codes.DECIMAL) String rate) {
    }

    /** The method's fields by type; decimals are strings so that nothing is rounded on the way in. */
    record MethodDto(
            @NotNull MethodType type,
            Long amount,
            @Pattern(regexp = Codes.CODE) String unit,
            @Pattern(regexp = Codes.DECIMAL) String rate,
            Long minimum,
            Long maximum,
            FeeMethod.Tiered.Mode mode,
            @Size(max = 20) List<@NotNull @Valid TierDto> tiers,
            @Size(max = 500) String expression,
            FeeMethod.Late.Period period,
            Integer graceDays) {

        FeeMethod toDomain() {
            return switch (type) {
                case FIXED -> new FeeMethod.Fixed(required(amount, "amount"));
                case PER_UNIT -> new FeeMethod.PerUnit(unit, decimal(rate, "rate"), minimum, maximum);
                case TIERED -> new FeeMethod.Tiered(unit, required(mode, "mode"),
                        required(tiers, "tiers").stream().map(t -> new FeeMethod.Tiered.Tier(
                                t.upTo() == null ? null : new BigDecimal(t.upTo()), decimal(t.rate(), "rate")))
                                .toList());
                case FORMULA -> new FeeMethod.Formula(required(expression, "expression"));
                case LATE -> new FeeMethod.Late(required(period, "period"), required(amount, "amount"),
                        graceDays == null ? 0 : graceDays, maximum);
            };
        }

        static MethodDto of(FeeMethod m) {
            return switch (m) {
                case FeeMethod.Fixed f -> new MethodDto(MethodType.FIXED, f.amount(), null, null, null, null, null,
                        null, null, null, null);
                case FeeMethod.PerUnit p -> new MethodDto(MethodType.PER_UNIT, null, p.unit(),
                        p.rate().toPlainString(), p.minimum(), p.maximum(), null, null, null, null, null);
                case FeeMethod.Tiered t -> new MethodDto(MethodType.TIERED, null, t.unit(), null, null, null,
                        t.mode(), t.tiers().stream().map(x -> new TierDto(
                                x.upTo() == null ? null : x.upTo().toPlainString(), x.rate().toPlainString()))
                        .toList(), null, null, null);
                case FeeMethod.Formula f -> new MethodDto(MethodType.FORMULA, null, null, null, null, null, null,
                        null, f.expression(), null, null);
                case FeeMethod.Late l -> new MethodDto(MethodType.LATE, l.rate(), null, null, null, l.maximum(),
                        null, null, null, l.period(), l.graceDays());
            };
        }

        private static <T> T required(T value, String field) {
            if (value == null) {
                throw Codes.invalid("method." + field);
            }
            return value;
        }

        private static BigDecimal decimal(String value, String field) {
            return new BigDecimal(required(value, field));
        }
    }

    record SubmitVersionRequest(
            @NotNull @Pattern(regexp = Codes.CODE) String feeCode,
            @NotNull @Pattern(regexp = Codes.SERVICE_CODE) String serviceCode,
            @NotBlank @Size(max = 200) String nameNl,
            @NotBlank @Size(max = 200) String nameEn,
            @Pattern(regexp = Codes.CODE) String region,
            @Pattern(regexp = Codes.CODE) String customerCategory,
            @Pattern(regexp = Codes.CODE) String subjectCategory,
            @NotNull @Pattern(regexp = Codes.CODE) String financialItem,
            @NotNull @Pattern(regexp = Codes.CODE) String beneficiary,
            @NotNull @Valid MethodDto method,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 3, max = 500) String reason) {

        FeeVersion.Spec toDomain() {
            return new FeeVersion.Spec(feeCode, serviceCode, nameNl, nameEn,
                    new FeeVersion.Applicability(region, customerCategory, subjectCategory), financialItem,
                    beneficiary, method.toDomain(), effectiveFrom, effectiveTo);
        }
    }

    record DecisionRequest(@Size(min = 3, max = 500) String reason) {
    }

    record RejectRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record DecisionDto(String actorId, String actorUsername, Channel channel, Instant at, String reason) {
        static DecisionDto of(FeeVersion.Decision d) {
            return d == null ? null : new DecisionDto(d.actor().id(), d.actor().username(), d.channel(), d.at(),
                    d.reason());
        }
    }

    record FeeVersionDto(UUID id, String feeCode, int versionNo, String serviceCode, String nameNl, String nameEn,
                         String region, String customerCategory, String subjectCategory, String financialItem,
                         String beneficiary, MethodDto method, List<String> units, String currency,
                         LocalDate effectiveFrom, LocalDate effectiveTo, FeeVersion.Status status,
                         DecisionDto submitted, DecisionDto decided) {
        static FeeVersionDto of(FeeVersion v) {
            FeeVersion.Spec s = v.spec();
            return new FeeVersionDto(v.id(), s.feeCode(), v.versionNo(), s.serviceCode(), s.nameNl(), s.nameEn(),
                    s.applicability().region(), s.applicability().customerCategory(),
                    s.applicability().subjectCategory(), s.financialItem(), s.beneficiary(), MethodDto.of(s.method()),
                    s.method().units(), v.currency(), s.effectiveFrom(), v.effectiveTo(), v.status(),
                    DecisionDto.of(v.submitted()), DecisionDto.of(v.decided()));
        }
    }

    record QuoteRequest(
            @NotNull @Pattern(regexp = Codes.SERVICE_CODE) String serviceCode,
            @NotNull @Pattern(regexp = Codes.CODE) String region,
            @NotNull @Pattern(regexp = Codes.CODE) String customerCategory,
            @Pattern(regexp = Codes.CODE) String subjectCategory,
            @Size(max = 20) Map<@Pattern(regexp = Codes.CODE) String, @NotNull @Pattern(regexp = Codes.DECIMAL) String>
                    quantities,
            LocalDate date,
            @Pattern(regexp = Codes.CODE) String campaignCode,
            LocalDate expiryDate) {

        Quote.Request toDomain(LocalDate today) {
            Map<String, BigDecimal> q = new LinkedHashMap<>();
            if (quantities != null) {
                quantities.forEach((k, v) -> q.put(k, new BigDecimal(v)));
            }
            return new Quote.Request(serviceCode, region, customerCategory, subjectCategory, q,
                    date == null ? today : date, campaignCode, expiryDate);
        }
    }

    record QuoteLineDto(Quote.LineKind kind, String feeCode, UUID feeVersionId, int versionNo, String nameNl,
                        String nameEn, String financialItem, String beneficiary, long gross, long discount,
                        String adjustmentCode, long amount) {
        static QuoteLineDto of(Quote.Line l) {
            return new QuoteLineDto(l.kind(), l.feeCode(), l.feeVersionId(), l.versionNo(), l.nameNl(), l.nameEn(),
                    l.financialItem(), l.beneficiary(), l.gross(), l.discount(), l.adjustmentCode(), l.amount());
        }
    }

    record QuoteDto(UUID id, String serviceCode, String region, String customerCategory, String subjectCategory,
                    Map<String, String> quantities, LocalDate date, String campaignCode, LocalDate expiryDate,
                    List<QuoteLineDto> lines, long total, String currency, Instant createdAt, Instant expiresAt,
                    boolean expired) {
        static QuoteDto of(Quote q, Instant now) {
            Map<String, String> quantities = new LinkedHashMap<>();
            q.request().quantities().forEach((k, v) -> quantities.put(k, v.toPlainString()));
            return new QuoteDto(q.id(), q.request().serviceCode(), q.request().region(),
                    q.request().customerCategory(), q.request().subjectCategory(), quantities, q.request().date(),
                    q.request().campaignCode(), q.request().expiryDate(), q.lines().stream().map(QuoteLineDto::of).toList(), q.total(), q.currency(), q.createdAt(),
                    q.expiresAt(), !now.isBefore(q.expiresAt()));
        }
    }

    record SubmitAdjustmentRequest(
            @NotNull @Pattern(regexp = Codes.CODE) String code,
            @NotNull FeeAdjustment.Kind kind,
            @NotNull @Pattern(regexp = Codes.SERVICE_CODE) String serviceCode,
            @NotBlank @Size(max = 200) String nameNl,
            @NotBlank @Size(max = 200) String nameEn,
            @Size(max = 50) List<@NotNull @Pattern(regexp = Codes.CODE) String> feeCodes,
            @Pattern(regexp = Codes.CODE) String customerCategory,
            @Pattern(regexp = Codes.CODE) String campaignCode,
            @Pattern(regexp = "^[0-9]{1,3}(\\.[0-9]{1,2})?$") String percent,
            Long amount,
            @Pattern(regexp = Codes.CODE) String financialItem,
            @Pattern(regexp = Codes.CODE) String beneficiary,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 3, max = 500) String reason) {

        FeeAdjustment.Spec toDomain() {
            return new FeeAdjustment.Spec(code, kind, serviceCode, nameNl, nameEn, feeCodes, customerCategory,
                    campaignCode, percent == null ? null : new BigDecimal(percent), amount, financialItem,
                    beneficiary, effectiveFrom, effectiveTo);
        }
    }

    record AdjustmentDto(UUID id, String code, FeeAdjustment.Kind kind, String serviceCode, String nameNl,
                         String nameEn, List<String> feeCodes, String customerCategory, String campaignCode,
                         String percent, Long amount, String financialItem, String beneficiary,
                         LocalDate effectiveFrom, LocalDate effectiveTo, FeeVersion.Status status,
                         DecisionDto submitted, DecisionDto decided) {
        static AdjustmentDto of(FeeAdjustment a) {
            FeeAdjustment.Spec s = a.spec();
            return new AdjustmentDto(a.id(), s.code(), s.kind(), s.serviceCode(), s.nameNl(), s.nameEn(),
                    s.feeCodes(), s.customerCategory(), s.campaignCode(),
                    s.percent() == null ? null : s.percent().toPlainString(), s.amount(), s.financialItem(),
                    s.beneficiary(), s.effectiveFrom(), s.effectiveTo(), a.status(), DecisionDto.of(a.submitted()),
                    DecisionDto.of(a.decided()));
        }
    }

    record Collection<T>(List<T> items) {
    }
}
