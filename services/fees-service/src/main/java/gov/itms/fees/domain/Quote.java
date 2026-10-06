package gov.itms.fees.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An itemised fee breakdown the customer sees before paying (CC-047), frozen when it is made: each line names the fee
 * version, financial item and beneficiary (CC-042), its gross amount, any exemption or discount (CC-043) and the net
 * amount; levies (CC-045) follow as their own lines. A later fee change never alters a quote (CC-046).
 */
public record Quote(UUID id, Request request, List<Line> lines, long total, String currency, Instant createdAt,
                    Instant expiresAt, String createdBy) {

    /**
     * What the fee depends on (CC-040): service, region, customer and subject category, quantities, date; a campaign
     * code for campaign discounts (CC-043) and the subject's expiry date for late fees (CC-044).
     */
    public record Request(String serviceCode, String region, String customerCategory, String subjectCategory,
                          Map<String, BigDecimal> quantities, LocalDate date, String campaignCode,
                          LocalDate expiryDate) {
        public Request {
            serviceCode = Codes.serviceCode(serviceCode);
            region = Codes.code("region", region);
            customerCategory = Codes.code("customerCategory", customerCategory);
            subjectCategory = Codes.optionalCode("subjectCategory", subjectCategory);
            Map<String, BigDecimal> q = new LinkedHashMap<>();
            if (quantities != null) {
                if (quantities.size() > 20) {
                    throw Codes.invalid("quantities");
                }
                quantities.forEach((unit, value) -> q.put(Codes.code("quantities", unit),
                        Codes.decimal("quantities." + unit, value)));
            }
            quantities = Map.copyOf(q);
            Objects.requireNonNull(date, "date");
            campaignCode = Codes.optionalCode("campaignCode", campaignCode);
        }

        public Request(String serviceCode, String region, String customerCategory, String subjectCategory,
                       Map<String, BigDecimal> quantities, LocalDate date) {
            this(serviceCode, region, customerCategory, subjectCategory, quantities, date, null, null);
        }

        FeeMethod.Inputs inputs() {
            return new FeeMethod.Inputs(quantities, expiryDate, date);
        }
    }

    public enum LineKind { FEE, LEVY }

    /**
     * One itemised line. For a FEE line, {@code gross - discount = amount}, with the exemption or discount that applied
     * ({@code adjustmentCode}); for a LEVY line, {@code adjustmentCode} is the levy and {@code amount} the levy due.
     */
    public record Line(LineKind kind, String feeCode, UUID feeVersionId, int versionNo, String nameNl, String nameEn,
                       String financialItem, String beneficiary, long gross, long discount, String adjustmentCode,
                       long amount) {
    }

    public Quote {
        lines = List.copyOf(lines);
    }

    /** Lines without adjustments (no exemptions, discounts or levies apply). */
    public static List<Line> calculate(List<FeeVersion> versions, Request request) {
        return calculate(versions, List.of(), request);
    }

    /**
     * Calculates the lines of a quote (CC-040..CC-045): for each fee of the service, the most specific approved
     * version in force on the date and matching the request (equally specific versions are ambiguous configuration);
     * a late fee that is not due is left out. On each fee line the most favourable applicable exemption or discount
     * is applied (they do not stack, Q-063). Then each applicable levy adds a line on the net amount of the fees it
     * covers. A service without applicable fees gives an empty quote (zero-fee services, Q-004).
     */
    public static List<Line> calculate(List<FeeVersion> versions, List<FeeAdjustment> adjustments, Request request) {
        Map<String, List<FeeVersion>> byFee = versions.stream()
                .filter(v -> v.spec().serviceCode().equals(request.serviceCode()))
                .filter(v -> v.inForce(request.date()))
                .filter(v -> v.spec().applicability().matches(request.region(), request.customerCategory(),
                        request.subjectCategory()))
                .collect(Collectors.groupingBy(v -> v.spec().feeCode()));
        List<FeeAdjustment> applicable = adjustments.stream().filter(a -> a.appliesTo(request)).toList();
        List<Line> lines = new ArrayList<>();
        byFee.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            FeeVersion v = choose(e.getKey(), e.getValue());
            long gross = v.spec().method().amount(request.inputs());
            if (gross == 0 && v.spec().method() instanceof FeeMethod.Late) {
                return;
            }
            FeeAdjustment best = null;
            long discount = 0;
            for (FeeAdjustment a : applicable) {
                if (a.spec().kind() != FeeAdjustment.Kind.LEVY && a.spec().coversFee(v.spec().feeCode())) {
                    long r = a.reduction(gross);
                    if (r > discount) {
                        discount = r;
                        best = a;
                    }
                }
            }
            lines.add(new Line(LineKind.FEE, v.spec().feeCode(), v.id(), v.versionNo(), v.spec().nameNl(),
                    v.spec().nameEn(), v.spec().financialItem(), v.spec().beneficiary(), gross, discount,
                    best == null ? null : best.spec().code(), gross - discount));
        });
        List<Line> levies = new ArrayList<>();
        applicable.stream().filter(a -> a.spec().kind() == FeeAdjustment.Kind.LEVY)
                .sorted(Comparator.comparing(a -> a.spec().code()))
                .forEach(levy -> {
                    long base = lines.stream().filter(l -> levy.spec().coversFee(l.feeCode()))
                            .mapToLong(Line::amount).sum();
                    long due = levy.levyOn(base);
                    if (due > 0) {
                        levies.add(new Line(LineKind.LEVY, levy.spec().code(), levy.id(), 0, levy.spec().nameNl(),
                                levy.spec().nameEn(), levy.spec().financialItem(), levy.spec().beneficiary(), due, 0,
                                levy.spec().code(), due));
                    }
                });
        lines.addAll(levies);
        return lines;
    }

    private static FeeVersion choose(String feeCode, List<FeeVersion> candidates) {
        int best = candidates.stream().mapToInt(v -> v.spec().applicability().specificity()).max().orElseThrow();
        List<FeeVersion> top = candidates.stream().filter(v -> v.spec().applicability().specificity() == best)
                .sorted(Comparator.comparing((FeeVersion v) -> v.spec().effectiveFrom()).reversed()).toList();
        if (top.size() > 1) {
            throw DomainException.conflict("FEE_AMBIGUOUS",
                    "fee " + feeCode + " has several equally specific versions for this request", List.of("CC-040"),
                    feeCode);
        }
        return top.getFirst();
    }
}
