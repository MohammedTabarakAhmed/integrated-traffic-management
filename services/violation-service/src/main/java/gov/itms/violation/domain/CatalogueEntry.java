package gov.itms.violation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One version of a violation catalogue entry (VF-001..VF-004; ADR-056): legal article, bilingual description, fine,
 * black points, impound days, suspension days, court referral, what it applies to, effective dates and an optional
 * region (a regional variant). Speed violations carry bands, each with its own fine and points (VF-004). A repeat
 * within the configured days uses the repeat fine (VF-003). A violation always uses the version in force on its date
 * (VF-002). Versions never change.
 */
public record CatalogueEntry(String code, int versionNo, String region, String legalArticle, String descriptionNl,
                             String descriptionEn, long fineMinor, int blackPoints, int impoundDays,
                             int suspensionDays, boolean courtReferral, AppliesTo appliesTo, List<SpeedBand> speedBands,
                             Integer repeatWithinDays, Long repeatFineMinor, String financialItem,
                             String beneficiary, LocalDate effectiveFrom, LocalDate effectiveTo, String publishedBy,
                             Instant publishedAt) {

    public enum AppliesTo { DRIVER, VEHICLE, BOTH }

    public static final String CODE = "^[A-Z0-9][A-Z0-9_]{0,39}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);

    /** A speed band: from (exclusive) and up to (inclusive) km/h over the limit; {@code toKmh} null = open-ended. */
    public record SpeedBand(int fromKmh, Integer toKmh, long fineMinor, int blackPoints) {
        public SpeedBand {
            if (fromKmh < 0 || (toKmh != null && toKmh <= fromKmh) || fineMinor < 0 || blackPoints < 0
                    || blackPoints > 100) {
                throw invalid("speedBands");
            }
        }

        boolean covers(int overKmh) {
            return overKmh > fromKmh && (toKmh == null || overKmh <= toKmh);
        }
    }

    /** What a violation of this entry costs. */
    public record Charge(long fineMinor, int blackPoints, boolean repeat) {
    }

    public CatalogueEntry {
        code("code", code);
        if (region != null) {
            code("region", region);
        }
        if (legalArticle == null || legalArticle.isBlank() || legalArticle.length() > 100) {
            throw invalid("legalArticle");
        }
        text("descriptionNl", descriptionNl);
        text("descriptionEn", descriptionEn);
        if (fineMinor < 0 || fineMinor > 1_000_000_000L || blackPoints < 0 || blackPoints > 100 || impoundDays < 0
                || impoundDays > 3650 || suspensionDays < 0 || suspensionDays > 3650) {
            throw invalid("amounts");
        }
        Objects.requireNonNull(appliesTo, "appliesTo");
        List<SpeedBand> bands = new ArrayList<>(speedBands == null ? List.of() : speedBands);
        bands.sort(Comparator.comparingInt(SpeedBand::fromKmh));
        for (int i = 1; i < bands.size(); i++) {
            SpeedBand prev = bands.get(i - 1);
            if (prev.toKmh() == null || prev.toKmh() != bands.get(i).fromKmh()) {
                throw DomainException.validation("SPEED_BANDS_INVALID", "speed bands must follow each other",
                        List.of("VF-004"));
            }
        }
        speedBands = List.copyOf(bands);
        if ((repeatWithinDays == null) != (repeatFineMinor == null)
                || (repeatWithinDays != null && (repeatWithinDays < 1 || repeatWithinDays > 3650 || repeatFineMinor < 0))) {
            throw DomainException.validation("REPEAT_RULE_INVALID", "a repeat rule has a period and a fine",
                    List.of("VF-003"));
        }
        code("financialItem", financialItem);
        code("beneficiary", beneficiary);
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw invalid("effectiveTo");
        }
    }

    public boolean inForce(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /**
     * The fine and points of a violation (VF-003, VF-004): the speed band of {@code overKmh} when the entry has bands;
     * the repeat fine when {@code repeat}.
     */
    public Charge charge(Integer overKmh, boolean repeat) {
        long fine = fineMinor;
        int points = blackPoints;
        if (!speedBands.isEmpty()) {
            if (overKmh == null) {
                throw DomainException.validation("SPEED_REQUIRED", "this violation needs the speed over the limit",
                        List.of("VF-004"));
            }
            SpeedBand band = speedBands.stream().filter(b -> b.covers(overKmh)).findFirst()
                    .orElseThrow(() -> DomainException.validation("SPEED_NOT_IN_BANDS",
                            "the speed is in no band of this violation", List.of("VF-004"), overKmh));
            fine = band.fineMinor();
            points = band.blackPoints();
        }
        boolean repeated = repeat && repeatFineMinor != null;
        if (repeated) {
            fine = Math.max(fine, repeatFineMinor);
        }
        return new Charge(fine, points, repeated);
    }

    static String code(String field, String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    private static void text(String field, String value) {
        if (value == null || value.isBlank() || value.length() > 500) {
            throw invalid(field);
        }
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("VF-001"),
                field);
    }
}
