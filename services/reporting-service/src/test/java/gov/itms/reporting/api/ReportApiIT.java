package gov.itms.reporting.api;

import static gov.itms.reporting.api.ContractClient.regional;
import static gov.itms.reporting.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import gov.itms.platform.security.AuthorisationResolver;
import gov.itms.reporting.application.ReportingService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Warehouse intake and reports through HTTP and PostgreSQL; contract-checked against reporting-service.v1.yaml. */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.reporting.pseudonym-key=integration-test-key-of-32-bytes-or-more"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("RPT-001")
class ReportApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final RequestPostProcessor NATIONAL = user("u-nat", "REPORT_VIEWER", "report.read");
    private static final RequestPostProcessor DUBAI = regional("u-dxb", "REPORT_VIEWER", List.of("DXB"),
            "report.read");
    private static final RequestPostProcessor NOBODY = user("u-no", "CLERK", "vehicle.read");

    @TestConfiguration
    static class Scopes {
        @Bean
        AuthorisationResolver authorisationResolver() {
            return new ContractClient.RegionalResolver();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    ReportingService reporting;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/reporting-service.v1.yaml");
    }

    private void fact(String id, String type, String region, Instant at, long fine, String status) {
        reporting.ingest(id, type, "V-" + id, at, Map.of("region", region, "fine", fine, "status", status,
                "responsibleFileNumber", "TF-SECRET-" + id, "channel", "BACKOFFICE"));
    }

    @Test
    @Tag("RPT-004")
    @Tag("RPT-020")
    @Tag("RPT-022")
    @DisplayName("RPT-001, RPT-004, RPT-020, RPT-022: facts are kept once and pseudonymised; a report counts and sums "
            + "by day and region; a regional viewer sees only their region; history cannot be changed")
    void reports() throws Exception {
        String s = Long.toString(System.nanoTime(), 36);
        fact(s + "a", "itms.violation.recorded", "DXB", Instant.parse("2026-09-02T08:00:00Z"), 30000, "OPEN");
        fact(s + "b", "itms.violation.recorded", "DXB", Instant.parse("2026-09-02T09:00:00Z"), 20000, "OPEN");
        fact(s + "c", "itms.violation.recorded", "AUH", Instant.parse("2026-09-03T09:00:00Z"), 10000, "PAID");
        fact(s + "a", "itms.violation.recorded", "DXB", Instant.parse("2026-09-02T08:00:00Z"), 30000, "OPEN");
        fact(s + "d", "itms.payment.confirmed", "DXB", Instant.parse("2026-09-02T08:00:00Z"), 1, "CONFIRMED");

        assertThat(db.sql("select count(*) from fact_event where event_id like :p").param("p", s + "%")
                .query(Long.class).single()).isEqualTo(4);
        assertThat(db.sql("select data::text from fact_event where event_id = :id").param("id", s + "a")
                .query(String.class).single()).doesNotContain("TF-SECRET").contains("P-");
        assertThatThrownBy(() -> db.sql("delete from fact_event where event_id = :id").param("id", s + "a").update())
                .hasMessageContaining("never change");

        JsonNode defs = api.expect(api.get("/v1/reports", NATIONAL), 200);
        assertThat(defs.get("items").findValuesAsString("code")).contains("VIOLATIONS_RECORDED", "PAYMENTS_CONFIRMED");

        Map<String, Object> sept = Map.of("code", "VIOLATIONS_RECORDED", "from", "2026-09-01", "to", "2026-09-30");
        JsonNode all = api.expect(api.post("/v1/report-runs", sept, NATIONAL), 200);
        assertThat(all.get("rows")).hasSize(2);
        JsonNode dxb = all.get("rows").get(0); // ordered by day: 2 September (DXB) before 3 September (AUH)
        assertThat(dxb.get("region").asString()).isEqualTo("DXB");
        assertThat(dxb.get("count").asLong()).isEqualTo(2);
        assertThat(dxb.get("amountMinor").asLong()).isEqualTo(50000);

        JsonNode mine = api.expect(api.post("/v1/report-runs", sept, DUBAI), 200);
        assertThat(mine.get("rows").findValuesAsString("region")).containsOnly("DXB");
        JsonNode refused = api.expect(api.post("/v1/report-runs", Map.of("code", "VIOLATIONS_RECORDED", "from",
                "2026-09-01", "to", "2026-09-30", "regions", List.of("AUH")), DUBAI), 403);
        assertThat(refused.get("code").asString()).isEqualTo("REGION_OUT_OF_SCOPE");

        JsonNode paid = api.expect(api.post("/v1/report-runs", Map.of("code", "VIOLATIONS_RECORDED", "from",
                "2026-09-01", "to", "2026-09-30", "status", "PAID", "period", "MONTH", "channel", "BACKOFFICE"),
                NATIONAL), 200);
        assertThat(paid.get("rows")).singleElement().satisfies(r -> {
            assertThat(r.get("region").asString()).isEqualTo("AUH");
            assertThat(r.get("period").asString()).isEqualTo("2026-09-01");
        });
    }

    @Test
    @Tag("RPT-002")
    @DisplayName("RPT-002: CSV export with the bilingual title and print date; formula-like cells are neutralised")
    void csv() throws Exception {
        String s = Long.toString(System.nanoTime(), 36);
        fact(s + "x", "itms.accident.reported", "=HYPERLINK", Instant.parse("2026-08-05T08:00:00Z"), 0, "REPORTED");
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/v1/report-runs")
                .with(jwt().jwt(j -> j.subject("u-nat").claim("roles", List.of("REPORT_VIEWER"))
                        .claim("permissions", List.of("report.read"))))
                .header("X-Channel", "BACKOFFICE").contentType(MediaType.APPLICATION_JSON)
                .accept("text/csv").content(json.writeValueAsString(Map.of("code", "ACCIDENTS_REPORTED", "from",
                        "2026-08-01", "to", "2026-08-31")))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("ACCIDENTS_REPORTED.csv");
        String csv = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿").contains("Accidents reported").contains("الحوادث")
                .contains("Printed").contains("period,region,eventType,count,amountMinor")
                .contains("2026-08-05,\"'=HYPERLINK\",itms.accident.reported,1,");
        assertThat(ReportController.cell("-5")).isEqualTo("-5");
        assertThat(ReportController.cell("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"");
    }

    @Test
    @Tag("RPT-005")
    @DisplayName("RPT-001, RPT-005: permission, unknown report, reversed and too long periods")
    void refusals() throws Exception {
        assertThat(api.get("/v1/reports", NOBODY).getResponse().getStatus()).isEqualTo(403);
        assertThat(api.expect(api.post("/v1/report-runs", Map.of("code", "NOPE", "from", "2026-01-01", "to",
                "2026-01-02"), NATIONAL), 404).get("code").asString()).isEqualTo("REPORT_NOT_FOUND");
        assertThat(api.expect(api.post("/v1/report-runs", Map.of("code", "VIOLATIONS_RECORDED", "from", "2026-02-01",
                "to", "2026-01-01"), NATIONAL), 422).get("code").asString()).isEqualTo("PERIOD_INVALID");
        assertThat(api.expect(api.post("/v1/report-runs", Map.of("code", "VIOLATIONS_RECORDED", "from", "2024-01-01",
                "to", "2026-01-01"), NATIONAL), 422).get("code").asString()).isEqualTo("PERIOD_TOO_LONG");
        assertThat(api.post("/v1/report-runs", Map.of("code", "VIOLATIONS_RECORDED", "from", "2026-01-01", "to",
                "2026-01-02", "extra", true), NATIONAL).getResponse().getStatus()).isEqualTo(400);
    }
}
