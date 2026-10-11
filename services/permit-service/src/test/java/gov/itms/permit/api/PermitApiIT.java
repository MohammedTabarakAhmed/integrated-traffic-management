package gov.itms.permit.api;

import static gov.itms.permit.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.permit.application.PermitService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Training permits: types, eligibility, committee tests, lifecycle and expiry (TR-001..TR-008); contract-checked. */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("TR-001")
class PermitApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final RequestPostProcessor ADMIN = user("u-admin", "PERMIT_ADMIN", "permit.type.manage");
    private static final RequestPostProcessor CLERK = user("u-clerk", "PERMIT_CLERK", "permit.application.submit",
            "permit.test.book", "permit.lifecycle.manage");
    private static final RequestPostProcessor EXAMINER = user("u-exam", "PERMIT_EXAMINER", "permit.test.record");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    PermitService permits;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/permit-service.v1.yaml");
    }

    private String type(String category, boolean test, int validityDays) throws Exception {
        String code = "PT_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "category", category, "minimumDrivingYears", 3,
                "minimumAge", 21, "cleanRecordDays", 365, "medicalFitRequired", true, "testRequired", test,
                "validityDays", validityDays, "renewalWindowDays", 30, "feeMinor", 5000));
        body.put("licenceClass", "LIGHT");
        api.expect(api.post("/v1/permit-types", body, ADMIN), 200);
        return code;
    }

    private Map<String, Object> application(String type, String file) {
        Map<String, Object> m = new HashMap<>(Map.of("typeCode", type, "trafficFileNumber", file, "licenceEligible",
                true, "drivingYears", 5, "age", 30, "cleanRecordDays", 400, "medicallyFit", true, "dualControls",
                false));
        m.put("schoolId", "SCHOOL-1");
        return m;
    }

    @Test
    @Tag("TR-002")
    @Tag("TR-003")
    @Tag("TR-006")
    @DisplayName("TR-002/003/006: eligibility checked, committee test booked and passed, approved, issued, suspended, "
            + "reinstated and cancelled with reasons")
    void instructorLifecycle() throws Exception {
        String type = type("INSTRUCTOR", true, 365);
        Map<String, Object> young = application(type, "TF-P1");
        young.put("age", 18);
        assertThat(api.post("/v1/permits", young, CLERK).getResponse().getStatus()).as("ineligible").isEqualTo(400);
        young.put("exceptionReason", "committee exception granted");
        api.expect(api.post("/v1/permits", young, CLERK), 201);
        Map<String, Object> noSchool = application(type, "TF-P2");
        noSchool.remove("schoolId");
        assertThat(api.post("/v1/permits", noSchool, CLERK).getResponse().getStatus()).isEqualTo(400);

        JsonNode permit = api.expect(api.post("/v1/permits", application(type, "TF-P3"), CLERK), 201);
        assertThat(permit.get("status").asString()).isEqualTo("APPLIED");
        assertThat(permit.get("number").asString()).isNotBlank();
        String base = "/v1/permits/" + permit.get("id").asString();
        assertThat(api.post(base + "/test-results", Map.of("examiner", "EX-1", "passed", true), EXAMINER)
                .getResponse().getStatus()).as("no test booked").isEqualTo(404);
        assertThat(api.post(base + "/tests", Map.of("committee", "C1", "scheduledAt",
                Instant.now().minusSeconds(60).toString()), CLERK).getResponse().getStatus()).isEqualTo(400);
        api.expect(api.post(base + "/tests", Map.of("committee", "C1", "scheduledAt",
                Instant.now().plus(1, ChronoUnit.DAYS).toString()), CLERK), 201);
        assertThat(api.expect(api.post(base + "/test-results", Map.of("examiner", "EX-1", "passed", false, "reason",
                "unsafe lane change"), EXAMINER), 200).get("status").asString()).isEqualTo("APPLIED");
        api.expect(api.post(base + "/tests", Map.of("committee", "C1", "scheduledAt",
                Instant.now().plus(2, ChronoUnit.DAYS).toString()), CLERK), 201);
        assertThat(api.expect(api.post(base + "/test-results", Map.of("examiner", "EX-1", "passed", true), EXAMINER),
                200).get("status").asString()).isEqualTo("TESTED");

        assertThat(api.post(base + "/transitions", Map.of("action", "approve"), CLERK).getResponse().getStatus())
                .as("approval needs a reason").isEqualTo(400);
        api.expect(api.post(base + "/transitions", Map.of("action", "approve", "reason", "test passed"), CLERK), 200);
        JsonNode issued = api.expect(api.post(base + "/transitions", Map.of("action", "issue"), CLERK), 200);
        assertThat(issued.get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.get("validTo").isNull()).isFalse();
        api.expect(api.post(base + "/transitions", Map.of("action", "suspend", "reason", "complaint"), CLERK), 200);
        api.expect(api.post(base + "/transitions", Map.of("action", "reinstate"), CLERK), 200);
        api.expect(api.post(base + "/transitions", Map.of("action", "renew"), CLERK), 200);
        assertThat(api.post(base + "/transitions", Map.of("action", "fly"), CLERK).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(api.expect(api.post(base + "/transitions", Map.of("action", "cancel", "reason", "school closed"),
                CLERK), 200).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.permit.status-changed' and "
                + "subject = :id").param("id", permit.get("id").asString()).query(Long.class).single())
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    @Tag("TR-005")
    @Tag("TR-008")
    @DisplayName("TR-005/008: a training vehicle needs dual controls; permits without a test are approved directly; "
            + "expiry and traffic-file suspension run as the system")
    void vehicleExpiryAndSuspension() throws Exception {
        String vehicleType = type("TRAINING_VEHICLE", false, 1);
        Map<String, Object> car = application(vehicleType, "TF-V1");
        car.put("vehiclePlate", "A 1234");
        assertThat(api.post("/v1/permits", car, CLERK).getResponse().getStatus()).isEqualTo(400);
        car.put("dualControls", true);
        JsonNode permit = api.expect(api.post("/v1/permits", car, CLERK), 201);
        assertThat(permit.get("status").asString()).isEqualTo("APPROVED");
        String base = "/v1/permits/" + permit.get("id").asString();
        api.expect(api.post(base + "/transitions", Map.of("action", "issue"), CLERK), 200);
        db.sql("update training_permit set valid_to = now() - interval '1 minute' where id = cast(:id as uuid)")
                .param("id", permit.get("id").asString()).update();
        assertThat(permits.expireDue(100)).isGreaterThanOrEqualTo(1);
        assertThat(permits.get(UUID.fromString(permit.get("id").asString())).status()).isEqualTo("EXPIRED");

        String instructor = type("INSTRUCTOR", false, 365);
        JsonNode other = api.expect(api.post("/v1/permits", application(instructor, "TF-S1"), CLERK), 201);
        api.expect(api.post("/v1/permits/" + other.get("id").asString() + "/transitions", Map.of("action", "issue"),
                CLERK), 200);
        assertThat(permits.suspendByTrafficEvent("TF-S1", "licence suspended")).isEqualTo(1);
        assertThat(permits.get(UUID.fromString(other.get("id").asString())).status()).isEqualTo("SUSPENDED");
        assertThat(api.post("/v1/permits", application("NO_SUCH_TYPE", "TF-X"), CLERK).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(api.post("/v1/permit-types", Map.of("code", "X", "category", "Y", "minimumDrivingYears", 0,
                "minimumAge", 21, "cleanRecordDays", 0, "medicalFitRequired", false, "testRequired", false,
                "validityDays", 1, "renewalWindowDays", 0, "feeMinor", 0), CLERK).getResponse().getStatus())
                .isEqualTo(403);
    }
}
