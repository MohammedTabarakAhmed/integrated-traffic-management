package gov.itms.reporting.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reporting settings, {@code itms.reporting.*} (NFR-042).
 *
 * @param pseudonymKey     the RPT-022 key, at least 32 bytes, from a secret file (SEC-004); never a plain setting in
 *                         production
 * @param personalFields   event fields that identify a person and are pseudonymised on intake (RPT-022)
 * @param maxDays          the longest date range answered at once, 1-3660 (RPT-005)
 * @param reports          the report definitions (RPT-001)
 */
@ConfigurationProperties("itms.reporting")
public record ReportingProperties(String pseudonymKey, List<String> personalFields,
                                  @DefaultValue("366") int maxDays, List<ReportSettings> reports) {

    public ReportingProperties {
        if (pseudonymKey == null || pseudonymKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("itms.reporting.pseudonym-key must be a secret of at least 32 bytes "
                    + "(RPT-022, SEC-004)");
        }
        personalFields = personalFields == null ? List.of() : List.copyOf(personalFields);
        if (maxDays < 1 || maxDays > 3660) {
            throw new IllegalArgumentException("itms.reporting.max-days must be 1-3660 (RPT-005)");
        }
        reports = reports == null ? List.of() : List.copyOf(reports);
        if (new HashSet<>(reports.stream().map(ReportSettings::code).toList()).size() != reports.size()) {
            throw new IllegalArgumentException("itms.reporting.reports: codes must be distinct");
        }
    }

    /** One report: code, bilingual title, event types counted and the optional amount field summed. */
    public record ReportSettings(String code, String titleNl, String titleEn, List<String> eventTypes,
                                 String amountField) {
        public ReportSettings {
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        }
    }

    @Override
    public String toString() {
        return "ReportingProperties[personalFields=" + personalFields + ", maxDays=" + maxDays + ", reports="
                + reports.size() + "]";
    }
}
