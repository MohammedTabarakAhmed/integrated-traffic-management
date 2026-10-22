package gov.itms.reporting.application;

import gov.itms.reporting.domain.KpiSetting;
import gov.itms.reporting.domain.Report;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** What reporting needs: its own warehouse and the caller's data scope. */
public final class Ports {

    private Ports() {
    }

    /** The warehouse of domain events (RPT-020), in reporting-service's own database. */
    public interface Warehouse {

        /** Keeps a fact once; false when its event id is already there (redelivery). */
        boolean insert(Report.Fact fact);

        /**
         * Counts (and sums {@code amountField}) the facts of {@code types} in [{@code from}, {@code to}), grouped by
         * period, region and type; {@code channel} and {@code status} filter when not null.
         */
        List<Report.Row> aggregate(List<String> types, String amountField, Instant from, Instant to,
                                   Report.Period period, String channel, String status);
    }

    /** Who asks for a report and which regions their {@code report.read} grants reach (CC-001, RPT-004). */
    public record Caller(String actorId, Grants grants) {
    }

    public interface Grants {

        boolean allowsInRegion(String region);

        /** A grant with national scope: may also see facts that carry no region. */
        boolean national();
    }

    /** KPI targets and red/amber/green thresholds (RPT-012), kept beside the warehouse, one row per KPI. */
    public interface KpiSettings {

        /** Writes the setting; a KPI already configured is replaced in place (business users may re-set it). */
        void upsert(KpiSetting setting);

        Optional<KpiSetting> find(String code);

        List<KpiSetting> all();
    }
}
