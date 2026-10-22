package gov.itms.reporting.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.reporting.domain.DomainException;
import gov.itms.reporting.domain.KpiSetting;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * KPI targets and red/amber/green thresholds that business users set (RPT-012). A setting is one row per KPI,
 * authority-wide: whoever holds {@value #MANAGE} may set it, reading needs {@code report.read} like every report.
 * Setting and reading are audited; a change replaces the previous thresholds in place (the ledger of reports keeps
 * the history through the audit record). No KPI values are computed here: the warehouse aggregates stay untouched,
 * and the status of an actual value is evaluated by the thresholds, nothing downstream.
 */
public class KpiSettingsService {

    public static final String MANAGE = "report.kpi.manage";
    public static final String READ = "report.read";

    private final Ports.KpiSettings settings;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public KpiSettingsService(Ports.KpiSettings settings, AuditRecorder audit, DomainEventRecorder events,
                              Clock clock) {
        this.settings = Objects.requireNonNull(settings);
        this.audit = Objects.requireNonNull(audit);
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Sets (or re-sets) the target and thresholds of a KPI (RPT-012); audited, with a domain event. */
    @Transactional
    public KpiSetting set(String code, long target, long red, long amber, boolean higherIsBetter, String actorId) {
        KpiSetting s = KpiSetting.set(code, target, red, amber, higherIsBetter, actorId, clock.instant());
        settings.upsert(s);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", s.code());
        detail.put("target", s.target());
        detail.put("red", s.red());
        detail.put("amber", s.amber());
        detail.put("higherIsBetter", s.higherIsBetter());
        audit.record("REPORT_KPI_SETTING_SET", "KpiSetting", s.code(), null, detail, null);
        events.record("itms.reporting.kpi-setting-set", s.code(), Map.of("code", s.code()));
        return s;
    }

    /** Every KPI setting, by code (RPT-012); readable by anyone who may read reports. */
    @Transactional
    public List<KpiSetting> all() {
        return settings.all();
    }

    /** The setting of one KPI (RPT-012); a KPI never configured is KPI_NOT_FOUND. */
    @Transactional
    public KpiSetting get(String code) {
        return settings.find(code).orElseThrow(this::notFound);
    }

    /** RED, AMBER or GREEN of {@code actual} by the KPI's thresholds (RPT-012). */
    @Transactional
    public String status(String code, long actual) {
        return get(code).statusOf(actual);
    }

    private DomainException notFound() {
        return new DomainException(DomainException.Kind.NOT_FOUND, "KPI_NOT_FOUND",
                "no KPI is configured under this code", List.of("RPT-012"));
    }
}
