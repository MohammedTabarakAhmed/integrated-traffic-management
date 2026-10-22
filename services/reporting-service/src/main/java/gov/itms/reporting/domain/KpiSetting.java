package gov.itms.reporting.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A KPI's target and red/amber/green thresholds, set by a business user (RPT-012). The thresholds are absolute
 * values on the KPI's own scale: when the KPI counts towards the goal ({@code higherIsBetter}, for example paid
 * violations), a value below the red threshold is RED, below the amber threshold AMBER, otherwise GREEN; when the
 * KPI counts against the goal, the comparison is reversed. The target is the goal the KPI aims at, recorded with
 * the thresholds. One row per KPI, authority-wide (CC-001 applies to reporting's data, not to its targets); every
 * change is audited. RPT-012 replaces no other record: a later see-the-status slice keeps the evaluation here,
 * so no rule is invented anywhere else.
 */
public record KpiSetting(String code, long target, long red, long amber, boolean higherIsBetter,
                         String updatedBy, Instant updatedAt) {

    public KpiSetting {
        Objects.requireNonNull(code);
        Objects.requireNonNull(updatedBy);
        Objects.requireNonNull(updatedAt);
        if (!code.matches("^[A-Z][A-Z0-9_]{1,39}$")) {
            throw new DomainException(DomainException.Kind.VALIDATION, "KPI_INVALID",
                    "the KPI code must be 2 to 40 characters using A-Z, 0-9 or underscore, starting with a letter",
                    List.of("RPT-012"));
        }
        if (higherIsBetter ? red > amber : red < amber) {
            throw new DomainException(DomainException.Kind.VALIDATION, "KPI_LIMITS_INVALID",
                    "the red and amber thresholds are in the wrong order for the KPI's direction", List.of("RPT-012"));
        }
    }

    /** A setting a business user sets on {@code code}. */
    public static KpiSetting set(String code, long target, long red, long amber, boolean higherIsBetter,
                                 String by, Instant at) {
        return new KpiSetting(code, target, red, amber, higherIsBetter, by, at);
    }

    /** RED, AMBER or GREEN for an actual value of the KPI, by the direction and thresholds of this setting. */
    public String statusOf(long actual) {
        if (higherIsBetter) {
            return actual < red ? "RED" : actual < amber ? "AMBER" : "GREEN";
        }
        return actual > red ? "RED" : actual > amber ? "AMBER" : "GREEN";
    }
}
