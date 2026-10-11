package gov.itms.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.reporting.domain.DomainException;
import gov.itms.reporting.domain.Report;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("RPT-001")
class ReportingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final byte[] KEY = "a-test-key-of-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8);

    private final Map<String, Report.Fact> facts = new LinkedHashMap<>();
    private final List<String> audit = new ArrayList<>();
    private List<Report.Row> rows = List.of();

    private final Ports.Warehouse warehouse = new Ports.Warehouse() {
        @Override
        public boolean insert(Report.Fact fact) {
            return facts.putIfAbsent(fact.eventId(), fact) == null;
        }

        @Override
        public List<Report.Row> aggregate(List<String> types, String amountField, Instant from, Instant to,
                                          Report.Period period, String channel, String status) {
            assertThat(from).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
            assertThat(to).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
            return rows;
        }
    };

    private final ReportingService service = new ReportingService(warehouse,
            new Pseudonymiser(KEY, Set.of("trafficFileNumber", "plate", "subject")), new AuditRecorder() {
                @Override
                public void record(String action, String entityType, String entityId, Object oldValue,
                                   Object newValue, String reason) {
                    audit.add(action + " " + entityId);
                }

                @Override
                public void recordAs(CurrentActor actor, String action, String entityType, String entityId,
                                     Object oldValue, Object newValue, String reason) {
                    record(action, entityType, entityId, oldValue, newValue, reason);
                }
            }, new ReportingService.Settings(List.of(new Report.Definition("VIOLATIONS_RECORDED", "المخالفات",
                    "Violations", List.of("itms.violation.recorded"), "fine")), 31),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Ports.Caller caller(boolean national, String... regions) {
        Set<String> reach = Set.of(regions);
        return new Ports.Caller("u-1", new Ports.Grants() {
            @Override
            public boolean allowsInRegion(String region) {
                return national || reach.contains(region);
            }

            @Override
            public boolean national() {
                return national;
            }
        });
    }

    @Test
    @Tag("RPT-020")
    @Tag("RPT-022")
    @DisplayName("RPT-020, RPT-022: an event is kept once; personal identifiers are pseudonymised with the key, "
            + "consistently; nested values are dropped")
    void intake() {
        Map<String, Object> data = new HashMap<>();
        data.put("trafficFileNumber", "TF-1");
        data.put("plate", "S 6");
        data.put("region", "DXB");
        data.put("channel", "BACKOFFICE");
        data.put("fine", 30000);
        data.put("items", List.of(Map.of("a", 1)));
        data.put("empty", null);
        assertThat(service.ingest("e-1", "itms.violation.recorded", "V-1", null, data)).isTrue();
        assertThat(service.ingest("e-1", "itms.violation.recorded", "V-1", null, data)).as("redelivery").isFalse();
        assertThat(service.ingest(null, "x", null, null, data)).isFalse();
        Report.Fact f = facts.get("e-1");
        assertThat(f.region()).isEqualTo("DXB");
        assertThat(f.channel()).isEqualTo("BACKOFFICE");
        assertThat(f.subject()).as("subject named personal").isNull();
        assertThat(f.occurredAt()).isEqualTo(NOW);
        assertThat(f.data()).containsEntry("fine", "30000").doesNotContainKeys("items", "empty");
        assertThat(f.data().get("trafficFileNumber")).matches("P-[0-9a-f]{24}").isNotEqualTo("TF-1");
        assertThat(f.data().values()).doesNotContain("TF-1", "S 6");
        Pseudonymiser same = new Pseudonymiser(KEY, Set.of());
        assertThat(f.data().get("trafficFileNumber")).isEqualTo(same.pseudonym("TF-1"));
        Pseudonymiser other = new Pseudonymiser("another-key-that-is-32-bytes-long!".getBytes(StandardCharsets.UTF_8),
                Set.of());
        assertThat(other.pseudonym("TF-1")).isNotEqualTo(same.pseudonym("TF-1"));
        assertThatThrownBy(() -> new Pseudonymiser(new byte[8], Set.of())).isInstanceOf(IllegalArgumentException.class);
        service.ingest("e-2", "itms.traffic-file.opened", "F-1", NOW.minusSeconds(60), Map.of("regionOpened", "AUH"));
        assertThat(facts.get("e-2").region()).isEqualTo("AUH");
    }

    @Test
    @Tag("RPT-004")
    @DisplayName("RPT-004: rows outside the caller's regions are left out; regionless rows need a national grant; "
            + "asking for another region is refused; runs are audited")
    void scope() {
        LocalDate oct = LocalDate.of(2026, 10, 1);
        rows = List.of(new Report.Row(oct, "DXB", "itms.violation.recorded", 3, 90000L),
                new Report.Row(oct, "AUH", "itms.violation.recorded", 2, 60000L),
                new Report.Row(oct, null, "itms.violation.recorded", 1, null));
        Report.Request all = new Report.Request("VIOLATIONS_RECORDED", oct, LocalDate.of(2026, 10, 10), null, null,
                null, null);

        assertThat(service.run(all, caller(false, "DXB")).rows()).extracting(Report.Row::region).containsExactly("DXB");
        assertThat(service.run(all, caller(true)).rows()).hasSize(3);
        Report.Request auh = new Report.Request("VIOLATIONS_RECORDED", oct, LocalDate.of(2026, 10, 10),
                Report.Period.MONTH, Set.of("AUH"), null, null);
        assertThat(service.run(auh, caller(true)).rows()).extracting(Report.Row::region).containsExactly("AUH");
        assertThatThrownBy(() -> service.run(auh, caller(false, "DXB"))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("REGION_OUT_OF_SCOPE");
        assertThat(audit).containsOnly("REPORT_RUN VIOLATIONS_RECORDED").hasSize(3);
    }

    @Test
    @Tag("RPT-005")
    @DisplayName("RPT-001, RPT-005: unknown report 404, reversed or too long a period refused")
    void requests() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        assertThatThrownBy(() -> service.run(new Report.Request("NOPE", d, d, null, null, null, null), caller(true)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("REPORT_NOT_FOUND");
        assertThatThrownBy(() -> new Report.Request("X", d, d.minusDays(1), null, null, null, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("PERIOD_INVALID");
        assertThatThrownBy(() -> service.run(new Report.Request("VIOLATIONS_RECORDED", d, d.plusDays(31), null, null,
                null, null), caller(true))).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("PERIOD_TOO_LONG");
        assertThatThrownBy(() -> new Report.Definition("bad", "a", "b", List.of("t"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.definitions()).extracting(Report.Definition::code).containsExactly("VIOLATIONS_RECORDED");
    }
}
