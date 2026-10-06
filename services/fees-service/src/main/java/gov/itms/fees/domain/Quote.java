package gov.itms.fees.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An itemised fee breakdown the customer sees before paying (CC-047), frozen when it is made: each line names the fee
 * version, financial item and beneficiary (CC-042) and its amount. A later fee change never alters a quote (CC-046).
 */
public record Quote(UUID id, Request request, List<Line> lines, long total, String currency, Instant createdAt,
                    Instant expiresAt, String createdBy) {

    /** What the fee depends on (CC-040): service, region, customer and subject category, quantities, date. */
    public record Request(String serviceCode, String region, String customerCategory, String subjectCategory,
                          Map<String, BigDecimal> quantities, LocalDate date) {
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
        }
    }

    /** One itemised fee line. */
    public record Line(String feeCode, UUID feeVersionId, int versionNo, String nameNl, String nameEn,
                       String financialItem, String beneficiary, long amount) {
    }

    public Quote {
        lines = List.copyOf(lines);
    }

    /**
     * Calculates the lines of a quote (CC-040, CC-041): for each fee of the service, the most specific approved
     * version in force on the date and matching the request; equally specific versions are ambiguous configuration.
     * A service without applicable fees gives an empty quote (zero-fee services, Q-004).
     */
    public static List<Line> calculate(List<FeeVersion> versions, Request request) {
        Map<String, List<FeeVersion>> byFee = versions.stream()
                .filter(v -> v.spec().serviceCode().equals(request.serviceCode()))
                .filter(v -> v.inForce(request.date()))
                .filter(v -> v.spec().applicability().matches(request.region(), request.customerCategory(),
                        request.subjectCategory()))
                .collect(Collectors.groupingBy(v -> v.spec().feeCode()));
        return byFee.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> line(choose(e.getKey(), e.getValue()), request))
                .toList();
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

    private static Line line(FeeVersion v, Request request) {
        return new Line(v.spec().feeCode(), v.id(), v.versionNo(), v.spec().nameNl(), v.spec().nameEn(),
                v.spec().financialItem(), v.spec().beneficiary(), v.spec().method().amount(request.quantities()));
    }
}
