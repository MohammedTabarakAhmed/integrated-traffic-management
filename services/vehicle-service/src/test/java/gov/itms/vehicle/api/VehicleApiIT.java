package gov.itms.vehicle.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import gov.itms.vehicle.application.VehicleService;
import gov.itms.vehicle.infrastructure.PartnerStub;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end slice test of vehicles: HTTP → security → service → PostgreSQL (V1 guards), with real HTTP stand-ins for
 * the insurance hub (ICD-02), fees-service and payment-service (client credentials through a stub token endpoint).
 * Requests and responses are validated against vehicle-service.v1.yaml. The payment event consumer is off; the test
 * hands the payment confirmation to the service itself. The SHORT category (1-month validity, never inspected) puts a
 * newly registered vehicle inside its renewal window at once.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.vehicles.consumer-enabled=false",
        "itms.vehicles.renewal-window-days=31",
        "itms.vehicles.categories[0].code=SHORT",
        "itms.vehicles.categories[0].validity-months=1",
        "itms.vehicles.categories[0].inspection-exempt-years=50",
        "itms.vehicles.categories[0].plate-category=PRIVATE",
        "itms.vehicles.categories[1].code=PRIVATE",
        "itms.vehicles.categories[1].validity-months=12",
        "itms.vehicles.categories[1].inspection-exempt-years=3",
        "itms.vehicles.categories[1].plate-category=PRIVATE"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("VL-010")
class VehicleApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final PartnerStub PARTNERS = new PartnerStub();
    private static final AtomicLong VINS = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static OpenApiInteractionValidator contract;

    @DynamicPropertySource
    static void partners(DynamicPropertyRegistry registry) {
        registry.add("itms.vehicles.insurance.base-url", PARTNERS::baseUrl);
        for (String client : List.of("fees", "payments")) {
            registry.add("itms.clients." + client + ".base-url", PARTNERS::baseUrl);
            registry.add("itms.clients." + client + ".token-uri", () -> PARTNERS.baseUrl() + "/token");
            registry.add("itms.clients." + client + ".client-id", () -> PartnerStub.CLIENT_ID);
            registry.add("itms.clients." + client + ".client-secret", () -> PartnerStub.CLIENT_SECRET);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    VehicleService vehicles;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/vehicle-service.v1.yaml").build();
    }

    @AfterAll
    static void stopPartners() {
        PARTNERS.close();
    }

    private static RequestPostProcessor officer() {
        return user("u-officer", "VEHICLE_OFFICER", "vehicle.create", "vehicle.read", "vehicle.register",
                "vehicle.inspection.record");
    }

    private static RequestPostProcessor plateAdmin() {
        return user("u-plates", "PLATE_ADMIN", "vehicle.plate.manage");
    }

    private static RequestPostProcessor user(String sub, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", "PORTAL").header("Accept-Language", "en")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, path).withHeader("Authorization", "Bearer t");
        if (key != null) {
            req.withHeader("Idempotency-Key", key);
        }
        if (payload != null) {
            req.withContentType("application/json").withBody(payload);
        }
        SimpleResponse response = SimpleResponse.Builder.status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).build();
        ValidationReport report = result.getResponse().getStatus() < 400
                ? contract.validate(req.build(), response)
                : contract.validateResponse(path, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, path,
                result.getResponse().getStatus()).isEmpty();
        return result;
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        boolean idempotent = path.equals("/v1/vehicles") || path.endsWith("/registrations")
                || path.endsWith("/renewals");
        return call(post(path), Request.Method.POST, path, body, who, idempotent ? "it-" + UUID.randomUUID() : null);
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        return call(get(path), Request.Method.GET, path, null, who, null);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return body(r).get("code").asString();
    }

    /** A European VIN (no check digit), unique per call. */
    private static String newVin() {
        return "WDB" + String.format("%014d", VINS.incrementAndGet());
    }

    private static Map<String, Object> vehicle(String vin, String category) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("engineNo", "ENG-" + vin.substring(10));
        spec.put("make", "TOYOTA");
        spec.put("model", "COROLLA");
        spec.put("modelYear", TODAY.getYear());
        spec.put("bodyType", "SEDAN");
        spec.put("colours", List.of("WHITE"));
        spec.put("fuelType", "PETROL");
        spec.put("steering", "LEFT");
        spec.put("seats", 5);
        spec.put("emptyWeightKg", 1200);
        spec.put("grossWeightKg", 1700);
        spec.put("axles", 2);
        spec.put("cylinders", 4);
        spec.put("category", category);
        return Map.of("vin", vin, "spec", spec, "ownerFileNumber", "TF-" + vin.substring(10), "region", "NORTH");
    }

    private void series() throws Exception {
        MvcResult r = postAs("/v1/plate-series", Map.of("code", "IT_NORTH_PRIVATE", "region", "NORTH",
                "plateCategory", "PRIVATE", "colourCode", "WHITE", "design", "STANDARD", "prefix", "A",
                "firstNumber", 1, "lastNumber", 99_999, "premiumPattern", "^(\\d)\\1+$", "active", true),
                plateAdmin());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** Creates and first-registers a vehicle, then confirms its payment; returns the VIN. */
    private String registeredVehicle(String category) throws Exception {
        series();
        String vin = newVin();
        MvcResult created = postAs("/v1/vehicles", vehicle(vin, category), officer());
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        PARTNERS.policyEnds.put(vin, TODAY.plusMonths(6));
        MvcResult started = postAs("/v1/vehicles/" + vin + "/registrations", Map.of("customerCategory", "CITIZEN"),
                officer());
        assertThat(started.getResponse().getStatus()).as(started.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode request = body(started);
        assertThat(request.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(request.get("amountMinor").asLong()).isEqualTo(40000);
        String itemRef = "VR-" + request.get("id").asString().toUpperCase(Locale.ROOT);
        assertThat(PARTNERS.payables.stream().map(p -> p.get("itemRef").asString())).contains(itemRef);
        assertThat(vehicles.onPaid(itemRef)).isTrue();
        assertThat(vehicles.onPaid(itemRef)).as("the same payment event again").isFalse();
        return vin;
    }

    @Test
    @Tag("VL-032")
    @Tag("VL-005")
    @DisplayName("first registration → payment confirmation → REGISTERED with an ASSIGNED plate; history and events")
    void firstRegistration() throws Exception {
        String vin = registeredVehicle("PRIVATE");
        JsonNode v = body(getAs("/v1/vehicles/" + vin, officer()));
        assertThat(v.get("status").asString()).isEqualTo("REGISTERED");
        assertThat(v.get("registrationValid").asBoolean()).isTrue();
        assertThat(v.get("registrationExpiry").asString()).isEqualTo(TODAY.plusMonths(12).minusDays(1).toString());
        assertThat(v.get("plateNumber").asString()).startsWith("A ");
        assertThat(db.sql("select status from plate where display = :d").param("d", v.get("plateNumber").asString())
                .query(String.class).single()).isEqualTo("ASSIGNED");
        assertThat(body(getAs("/v1/vehicles/" + vin + "/history", officer())).get("items").findValuesAsString("event"))
                .containsSubsequence("CREATED", "REGISTRATION_REQUESTED", "REGISTERED");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.vehicle.registered' "
                + "and subject = :v").param("v", vin).query(Long.class).single()).isEqualTo(1);
        assertThat(PARTNERS.authorisations).contains("Bearer it-token");
        assertThat(code(postAs("/v1/vehicles", vehicle(vin, "PRIVATE"), officer()))).isEqualTo("VIN_ACTIVE");
        assertThatThrownBy(() -> db.sql("update vehicle set vin = 'X' where vin = :v").param("v", vin).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never changes");
        assertThatThrownBy(() -> db.sql("delete from vehicle where vin = :v").param("v", vin).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
    }

    @Test
    @Tag("UAT-3")
    @Tag("VL-024")
    @Tag("VL-011")
    @DisplayName("UAT-3: a policy that expired yesterday stops the online renewal before payment with the exact message")
    void uat3RenewalBlockedByInsurance() throws Exception {
        String vin = registeredVehicle("SHORT");
        PARTNERS.policyEnds.put(vin, TODAY.minusDays(1));
        int quotes = PARTNERS.quotes.size();
        int payables = PARTNERS.payables.size();

        MvcResult blocked = postAs("/v1/vehicles/" + vin + "/renewals", Map.of("customerCategory", "CITIZEN"),
                officer());
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("INSURANCE_EXPIRED");
        assertThat(body(blocked).get("detail").asString())
                .isEqualTo("Your vehicle insurance has expired. Renew it with your insurer, then try again.");
        assertThat(PARTNERS.quotes).as("no fee was asked").hasSize(quotes);
        assertThat(PARTNERS.payables).as("stopped before payment").hasSize(payables);
        assertThat(db.sql("select count(*) from registration_request r join vehicle v on v.id = r.vehicle_id "
                + "where v.vin = :v and r.kind = 'RENEWAL'").param("v", vin).query(Long.class).single()).isZero();

        PARTNERS.insurerDown.add(vin);
        MvcResult down = postAs("/v1/vehicles/" + vin + "/renewals", Map.of("customerCategory", "CITIZEN"),
                officer());
        assertThat(down.getResponse().getStatus()).as("insurer down fails closed").isEqualTo(503);
        assertThat(code(down)).isEqualTo("INSURANCE_UNAVAILABLE");
        PARTNERS.insurerDown.remove(vin);

        PARTNERS.policyEnds.put(vin, TODAY.plusMonths(6));
        MvcResult renewed = postAs("/v1/vehicles/" + vin + "/renewals", Map.of("customerCategory", "CITIZEN"),
                officer());
        assertThat(renewed.getResponse().getStatus()).as(renewed.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(renewed).get("kind").asString()).isEqualTo("RENEWAL");
    }

    @Test
    @Tag("VL-040")
    @Tag("CC-001")
    @DisplayName("inspection results are recorded; permissions are enforced; unknown VINs are 404")
    void inspectionsAndGuards() throws Exception {
        String vin = newVin();
        postAs("/v1/vehicles", vehicle(vin, "PRIVATE"), officer());
        MvcResult inspected = postAs("/v1/vehicles/" + vin + "/inspection-results", Map.of("centreCode", "CENTRE_1",
                "inspectedOn", TODAY.toString(), "odometerKm", 1200, "results", Map.of("BRAKES", "PASS"),
                "passed", true), officer());
        assertThat(inspected.getResponse().getStatus()).as(inspected.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(postAs("/v1/vehicles/" + vin + "/inspection-results", Map.of("centreCode", "CENTRE_1",
                "inspectedOn", TODAY.plusDays(1).toString(), "odometerKm", 1200, "passed", true), officer())
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(postAs("/v1/vehicles", vehicle(newVin(), "PRIVATE"), plateAdmin()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(getAs("/v1/vehicles/" + newVin(), officer()).getResponse().getStatus()).isEqualTo(404);
        Map<String, Object> badVin = new HashMap<>(vehicle(newVin(), "PRIVATE"));
        badVin.put("vin", "1M8GDM9A1KP042788");
        assertThat(code(postAs("/v1/vehicles", badVin, officer()))).isEqualTo("VIN_CHECK_DIGIT");
        assertThatThrownBy(() -> db.sql("delete from inspection_result").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }
}
