package gov.itms.reporting.infrastructure;

import gov.itms.reporting.application.Ports;
import gov.itms.reporting.domain.KpiSetting;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** KPI targets and thresholds (RPT-012) in the {@code report_kpi_setting} table (V2), one row per KPI. */
class JdbcKpiSettings implements Ports.KpiSettings {

    private final JdbcClient jdbc;

    JdbcKpiSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(KpiSetting s) {
        jdbc.sql("""
                        insert into report_kpi_setting (code, target, red_threshold, amber_threshold, higher_is_better,
                                                       updated_by, updated_at)
                        values (:code, :target, :red, :amber, :higher, :by, :at)
                        on conflict (code) do update set
                            target = excluded.target, red_threshold = excluded.red_threshold,
                            amber_threshold = excluded.amber_threshold, higher_is_better = excluded.higher_is_better,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("code", s.code())
                .param("target", s.target())
                .param("red", s.red())
                .param("amber", s.amber())
                .param("higher", s.higherIsBetter())
                .param("by", s.updatedBy())
                .param("at", Timestamp.from(s.updatedAt()))
                .update();
    }

    @Override
    public Optional<KpiSetting> find(String code) {
        return jdbc.sql("""
                        select code, target, red_threshold, amber_threshold, higher_is_better, updated_by, updated_at
                        from report_kpi_setting where code = :code""")
                .param("code", code)
                .query(JdbcKpiSettings::row)
                .optional();
    }

    @Override
    public List<KpiSetting> all() {
        return jdbc.sql("""
                        select code, target, red_threshold, amber_threshold, higher_is_better, updated_by, updated_at
                        from report_kpi_setting order by code""")
                .query(JdbcKpiSettings::row)
                .list();
    }

    private static KpiSetting row(ResultSet rs, int n) throws java.sql.SQLException {
        return new KpiSetting(rs.getString("code"), rs.getLong("target"), rs.getLong("red_threshold"),
                rs.getLong("amber_threshold"), rs.getBoolean("higher_is_better"), rs.getString("updated_by"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());
    }
}
