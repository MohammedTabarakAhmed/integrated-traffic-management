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
import java.time.Instant;
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
        for (String client : List.of("fees", "payments", "holds")) {
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
        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath)
                .withHeader("Authorization", "Bearer t");
        if (key != null) {
            req.withHeader("Idempotency-Key", key);
        }
        if (qs > 0) {
            for (String pair : path.substring(qs + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        if (payload != null) {
            req.withContentType("application/json").withBody(payload);
        }
        SimpleResponse response = SimpleResponse.Builder.status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).build();
        ValidationReport report = result.getResponse().getStatus() < 400
                ? contract.validate(req.build(), response)
                : contract.validateResponse(requestPath, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, requestPath,
                result.getResponse().getStatus()).isEmpty();
        return result;
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who, "it-" + UUID.randomUUID());
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
        spec.put("engineCapacityCc", 1598);
        spec.put("origin", "Japan");
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
        String vin = customsVehicle(category, "TF-" + REFS.incrementAndGet());
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
        assertThat(v.get("spec").get("engineCapacityCc").asInt()).as("VL-001: engine capacity stored and read back")
                .isEqualTo(1598);
        assertThat(v.get("spec").get("origin").asString()).isEqualTo("Japan");
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
    @Tag("VL-011")
    @Tag("VF-023")
    @DisplayName("VL-011, VF-023: an unpaid-fines hold on the vehicle stops the renewal with the reason and next step")
    void renewalBlockedByHold() throws Exception {
        String vin = registeredVehicle("SHORT");
        PARTNERS.policyEnds.put(vin, TODAY.plusMonths(6));
        PARTNERS.heldSubjects.add(vin);
        int quotes = PARTNERS.quotes.size();

        MvcResult blocked = postAs("/v1/vehicles/" + vin + "/renewals", Map.of("customerCategory", "CITIZEN"),
                officer());
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("ELIGIBILITY_BLOCKED");
        assertThat(body(blocked).get("nextStep").asString()).isEqualTo("PAY_FINES");
        assertThat(body(blocked).get("blocks").get(0).get("reasonCode").asString()).isEqualTo("UNPAID_FINES");
        assertThat(PARTNERS.quotes).as("no fee was asked").hasSize(quotes);
        JsonNode check = PARTNERS.eligibilityChecks.getLast();
        assertThat(check.get("serviceCode").asString()).isEqualTo("VEH_RENEWAL");
        assertThat(check.get("subjects").findValuesAsString("type")).containsExactly("VEHICLE", "TRAFFIC_FILE");

        PARTNERS.heldSubjects.remove(vin);
        MvcResult renewed = postAs("/v1/vehicles/" + vin + "/renewals", Map.of("customerCategory", "CITIZEN"),
                officer());
        assertThat(renewed.getResponse().getStatus()).as(renewed.getResponse().getContentAsString()).isEqualTo(201);
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

    // ---- Increment 2: the vehicle file after registration and the plate trade (ADR-059)

    private static final AtomicLong REFS = new AtomicLong(System.nanoTime() % 1_000_000L);

    private static RequestPostProcessor clerk() {
        return user("u-clerk", "VEHICLE_CLERK", "vehicle.service", "vehicle.read", "vehicle.register",
                "vehicle.create", "vehicle.inspection.record");
    }

    private static String ref(String prefix) {
        return prefix + REFS.incrementAndGet();
    }

    private JsonNode expect(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? null : json.readTree(text);
    }

    /** Pays a vehicle request (any kind) and returns it. */
    private void paid(JsonNode request) {
        assertThat(vehicles.onPaid("VR-" + request.get("id").asString().toUpperCase(Locale.ROOT))).isTrue();
    }

    /** VL-002: customs sends the certificate; the vehicle is created from it. */
    private String customsVehicle(String category, String owner) throws Exception {
        String vin = newVin();
        Map<String, Object> body = vehicle(vin, category);
        String cert = ref("CC-");
        expect(postAs("/v1/customs-certificates", Map.of("certificateNo", cert, "source", "CUSTOMS", "issuerCode",
                "CUSTOMS_HQ", "vin", vin, "spec", body.get("spec"), "issuedOn", TODAY.toString()),
                user("svc-customs", "CUSTOMS_FEED", "vehicle.customs.feed")), 201);
        expect(postAs("/v1/vehicles", Map.of("customsCertificateNo", cert, "ownerFileNumber", owner, "region",
                "NORTH"), officer()), 201);
        return vin;
    }

    private void inspected(String vin) throws Exception {
        expect(postAs("/v1/vehicles/" + vin + "/inspection-results", Map.of("centreCode", "CENTRE_1", "inspectedOn",
                TODAY.toString(), "odometerKm", 1500, "passed", true), officer()), 201);
    }

    @Test
    @Tag("VL-002")
    @Tag("VL-006")
    @Tag("VL-018")
    @Tag("VL-019")
    @DisplayName("VL-002, VL-006, VL-018/019: customs certificate used once; bans block and lift; approvals decided")
    void customsBansApprovals() throws Exception {
        String vin = newVin();
        Map<String, Object> spec = new HashMap<>(vehicle(vin, "PRIVATE"));
        String cert = ref("CC-");
        RequestPostProcessor customs = user("svc-customs", "CUSTOMS_FEED", "vehicle.customs.feed");
        expect(postAs("/v1/customs-certificates", Map.of("certificateNo", cert, "source", "DEALER", "issuerCode",
                "DEALER_1", "vin", vin, "spec", spec.get("spec"), "issuedOn", TODAY.toString()), customs), 201);
        assertThat(code(postAs("/v1/customs-certificates", Map.of("certificateNo", cert, "source", "DEALER",
                "issuerCode", "DEALER_1", "vin", vin, "spec", spec.get("spec"), "issuedOn", TODAY.toString()),
                customs))).isEqualTo("CERTIFICATE_EXISTS");
        assertThat(body(getAs("/v1/customs-certificates/" + cert, officer())).get("used").asBoolean()).isFalse();
        JsonNode created = expect(postAs("/v1/vehicles", Map.of("customsCertificateNo", cert, "ownerFileNumber",
                "TF-C1", "region", "NORTH"), officer()), 201);
        assertThat(created.get("vin").asString()).isEqualTo(vin);
        assertThat(body(getAs("/v1/customs-certificates/" + cert, officer())).get("used").asBoolean()).isTrue();
        String other = newVin();
        assertThat(code(postAs("/v1/vehicles", Map.of("customsCertificateNo", cert, "ownerFileNumber", "TF-C1",
                "region", "NORTH", "vin", other), officer()))).isIn("CERTIFICATE_USED", "CERTIFICATE_VIN_MISMATCH");

        String plain = newVin();
        expect(postAs("/v1/vehicles", vehicle(plain, "PRIVATE"), officer()), 201);
        PARTNERS.policyEnds.put(plain, TODAY.plusMonths(6));
        assertThat(code(postAs("/v1/vehicles/" + plain + "/registrations", Map.of("customerCategory", "CITIZEN"),
                officer()))).isEqualTo("CUSTOMS_CERTIFICATE_REQUIRED");

        RequestPostProcessor police = user("u-police", "VEHICLE_BAN_OFFICER", "vehicle.ban.manage", "vehicle.read");
        JsonNode ban = expect(postAs("/v1/vehicles/" + vin + "/bans", Map.of("reasonCode", "COURT_ORDER",
                "reference", "CASE-1"), police), 201);
        PARTNERS.policyEnds.put(vin, TODAY.plusMonths(6));
        series();
        assertThat(code(postAs("/v1/vehicles/" + vin + "/registrations", Map.of("customerCategory", "CITIZEN"),
                officer()))).isEqualTo("VEHICLE_BANNED");
        assertThat(code(postAs("/v1/vehicles/" + vin + "/bans/" + ban.get("id").asString() + "/lift",
                Map.of("reason", "court lifted it"), police))).as("not by who imposed it").isEqualTo("SAME_PERSON");
        RequestPostProcessor supervisor = user("u-police-2", "VEHICLE_BAN_OFFICER", "vehicle.ban.manage");
        expect(postAs("/v1/vehicles/" + vin + "/bans/" + ban.get("id").asString() + "/lift",
                Map.of("reason", "court lifted it"), supervisor), 200);
        assertThat(code(postAs("/v1/vehicles/" + vin + "/bans/" + ban.get("id").asString() + "/lift",
                Map.of("reason", "again"), supervisor))).isEqualTo("BAN_LIFTED");

        JsonNode approval = expect(postAs("/v1/vehicles/" + vin + "/approvals", Map.of("kind", "PUBLIC_DECLARATION",
                "detail", Map.of("route", "R1")), clerk()), 201);
        RequestPostProcessor approver = user("u-approver", "VEHICLE_APPROVER", "vehicle.approve", "vehicle.read");
        JsonNode decided = expect(postAs("/v1/vehicles/" + vin + "/approvals/" + approval.get("id").asString()
                + "/decision", Map.of("approve", true, "note", "documents complete"), approver), 200);
        assertThat(decided.get("status").asString()).isEqualTo("APPROVED");
        assertThat(code(postAs("/v1/vehicles/" + vin + "/approvals/" + approval.get("id").asString() + "/decision",
                Map.of("approve", false), approver))).isEqualTo("APPROVAL_DECIDED");

        JsonNode request = expect(postAs("/v1/vehicles/" + vin + "/registrations", Map.of("customerCategory",
                "CITIZEN"), officer()), 201);
        paid(request);
        JsonNode records = body(getAs("/v1/vehicles/" + vin + "/records", officer()));
        assertThat(records.get("bans")).hasSize(1);
        assertThat(records.get("approvals")).hasSize(1);
    }

    @Test
    @Tag("VL-012")
    @Tag("VL-021")
    @Tag("VL-013")
    @Tag("VL-014")
    @Tag("VL-022")
    @DisplayName("VL-012/013/014/021/022: a mortgaged vehicle is transferred with the bank's consent and the buyer's; "
            + "details change after an inspection; cards are replaced and tracked")
    void transferChangeCards() throws Exception {
        String vin = registeredVehicle("PRIVATE");
        RequestPostProcessor bank = user("svc-bank", "BANK", "vehicle.mortgage.manage", "vehicle.read");
        JsonNode mortgage = expect(postAs("/v1/vehicles/" + vin + "/mortgages", Map.of("bankCode", "BANK_1",
                "contractRef", ref("M-")), bank), 201);
        Map<String, Object> transfer = Map.of("buyerFileNumber", "TF-BUYER", "buyerAcceptsFines", true,
                "sellerRetainsPlate", false, "customerCategory", "CITIZEN");
        JsonNode request = expect(postAs("/v1/vehicles/" + vin + "/transfers", transfer, clerk()), 201);
        assertThat(request.get("status").asString()).isEqualTo("AWAITING_CONSENT");
        assertThat(code(postAs("/v1/vehicles/" + vin + "/transfers", transfer, clerk()))).isEqualTo("REQUEST_OPEN");
        assertThat(code(postAs("/v1/vehicles/" + vin + "/transfers/" + request.get("id").asString() + "/consent",
                null, clerk()))).isEqualTo("MORTGAGE_ACTIVE");
        expect(postAs("/v1/vehicles/" + vin + "/mortgages/" + mortgage.get("id").asString() + "/transfer-consents",
                Map.of("transferId", request.get("id").asString()), bank), 200);
        JsonNode consented = expect(postAs("/v1/vehicles/" + vin + "/transfers/" + request.get("id").asString()
                + "/consent", null, clerk()), 200);
        assertThat(consented.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        paid(consented);
        assertThat(body(getAs("/v1/vehicles/" + vin, officer())).get("ownerFileNumber").asString())
                .isEqualTo("TF-BUYER");

        Map<String, Object> change = Map.of("colours", List.of("BLACK"), "customerCategory", "CITIZEN");
        assertThat(code(postAs("/v1/vehicles/" + vin + "/changes", change, clerk()))).isEqualTo("INSPECTION_REQUIRED");
        inspected(vin);
        assertThat(code(postAs("/v1/vehicles/" + vin + "/changes", Map.of("colours", List.of("WHITE"),
                "customerCategory", "CITIZEN"), clerk()))).isEqualTo("NOTHING_CHANGED");
        paid(expect(postAs("/v1/vehicles/" + vin + "/changes", change, clerk()), 201));
        assertThat(body(getAs("/v1/vehicles/" + vin, officer())).get("spec").get("colours").get(0).asString())
                .isEqualTo("BLACK");

        assertThat(code(postAs("/v1/vehicles/" + vin + "/card-replacements", Map.of("reason", "BURNT", "delivery",
                "CENTRE", "customerCategory", "CITIZEN"), clerk()))).isEqualTo("FIELD_INVALID");
        paid(expect(postAs("/v1/vehicles/" + vin + "/card-replacements", Map.of("reason", "LOST", "delivery",
                "COURIER", "address", "1 Palm Street, North", "customerCategory", "CITIZEN"), clerk()), 201));
        JsonNode cards = body(getAs("/v1/vehicles/" + vin + "/cards", officer())).get("items");
        assertThat(cards.size()).isGreaterThanOrEqualTo(2);
        JsonNode latest = cards.get(cards.size() - 1);
        RequestPostProcessor printer = user("svc-print", "CARD_PRINTER", "vehicle.card.manage");
        JsonNode moved = expect(postAs("/v1/vehicles/" + vin + "/cards/" + latest.get("id").asString() + "/status",
                Map.of("status", "PRINTED"), printer), 200);
        assertThat(moved.get("status").asString()).isEqualTo("PRINTED");
        expect(postAs("/v1/vehicles/" + vin + "/cards/" + latest.get("id").asString() + "/status",
                Map.of("status", "DISPATCHED", "trackingRef", "TRK-1"), printer), 200);
        expect(postAs("/v1/vehicles/" + vin + "/mortgages/" + mortgage.get("id").asString() + "/release", null, bank),
                200);
    }

    @Test
    @Tag("VL-015")
    @Tag("VL-016")
    @Tag("VL-017")
    @Tag("VL-020")
    @DisplayName("VL-015/016/017/020: certificates, insurance recovery, temporary entry and exit permits, export "
            + "de-registration")
    void certificatesPermitsDeregistration() throws Exception {
        String vin = registeredVehicle("PRIVATE");
        paid(expect(postAs("/v1/vehicles/" + vin + "/certificates", Map.of("kind", "OWNERSHIP", "customerCategory",
                "CITIZEN"), clerk()), 201));
        assertThat(body(getAs("/v1/vehicles/" + vin + "/certificates", officer())).get("items")).hasSize(1);
        RequestPostProcessor insurer = user("svc-insurer", "INSURER", "vehicle.insurance.recovery");
        assertThat(code(postAs("/v1/vehicles/" + vin + "/insurance-recovery-certificates", Map.of("insurerCode",
                "INS1", "claimRef", "CL-1", "reason", "FIRE"), insurer))).isEqualTo("FIELD_INVALID");
        expect(postAs("/v1/vehicles/" + vin + "/insurance-recovery-certificates", Map.of("insurerCode", "INS1",
                "claimRef", ref("CL-"), "reason", "THEFT"), insurer), 201);

        JsonNode exit = expect(postAs("/v1/vehicles/" + vin + "/exit-permits", Map.of("country", "OMN", "borderPoint",
                "BORDER_1", "validFrom", TODAY.toString(), "validTo", TODAY.plusDays(10).toString(),
                "customerCategory", "CITIZEN"), clerk()), 201);
        assertThat(code(postAs("/v1/vehicles/" + vin + "/exit-permits", Map.of("country", "OMN", "borderPoint",
                "BORDER_1", "validFrom", TODAY.toString(), "validTo", TODAY.plusDays(400).toString(),
                "customerCategory", "CITIZEN"), clerk()))).isIn("PERMIT_TOO_LONG", "REQUEST_OPEN", "REGISTRATION_EXPIRES");
        paid(exit);

        String foreignVin = "JHM" + String.format("%014d", REFS.incrementAndGet());
        JsonNode entry = expect(postAs("/v1/temporary-permits", Map.of("vin", foreignVin, "foreignPlate", "OM 123",
                "country", "OMN", "holderFileNumber", "TF-VISITOR", "borderPoint", "BORDER_1", "validFrom",
                TODAY.toString(), "validTo", TODAY.plusDays(30).toString(), "region", "NORTH", "customerCategory",
                "VISITOR"), clerk()), 201);
        paid(entry);
        String permitId = db.sql("select id::text from temporary_permit where vin = :v").param("v", foreignVin)
                .query(String.class).single();
        assertThat(body(getAs("/v1/temporary-permits/" + permitId, clerk())).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(expect(postAs("/v1/temporary-permits/" + permitId + "/close", null, clerk()), 200).get("status")
                .asString()).isEqualTo("CLOSED");

        String other = registeredVehicle("PRIVATE");
        assertThat(code(postAs("/v1/vehicles/" + other + "/deregistrations", Map.of("reason", "SOLD",
                "retainPlate", false, "customerCategory", "CITIZEN"), clerk()))).isEqualTo("FIELD_INVALID");
        paid(expect(postAs("/v1/vehicles/" + other + "/deregistrations", Map.of("reason", "EXPORT", "retainPlate",
                true, "destinationCountry", "OMN", "customerCategory", "CITIZEN"), clerk()), 201));
        assertThat(body(getAs("/v1/vehicles/" + other, officer())).get("status").asString()).isEqualTo("DEREGISTERED");
        assertThat(code(postAs("/v1/vehicles/" + other + "/changes", Map.of("colours", List.of("RED"),
                "customerCategory", "CITIZEN"), clerk()))).isEqualTo("VEHICLE_DEREGISTERED");
    }

    @Test
    @Tag("VL-026")
    @Tag("VL-027")
    @DisplayName("VL-026/027: a company sees and renews its fleet and assigns drivers; a dealer registers in bulk and "
            + "hands over")
    void fleetsAndDealers() throws Exception {
        series();
        String company = ref("TF-CO");
        String a = customsVehicle("SHORT", company);
        String b = customsVehicle("SHORT", company);
        PARTNERS.policyEnds.put(a, TODAY.plusMonths(6));
        PARTNERS.policyEnds.put(b, TODAY.plusMonths(6));
        JsonNode bulk = expect(postAs("/v1/dealers/" + company + "/registrations", Map.of("vins", List.of(a, b,
                newVin()), "customerCategory", "CITIZEN"), clerk()), 200);
        assertThat(bulk.get("items")).hasSize(3);
        db.sql("select id::text from registration_request r where r.status = 'AWAITING_PAYMENT' and r.vehicle_id in "
                        + "(select id from vehicle where vin in (:a, :b))").param("a", a).param("b", b)
                .query(String.class).list()
                .forEach(id -> assertThat(vehicles.onPaid("VR-" + id.toUpperCase(Locale.ROOT))).isTrue());

        assertThat(body(getAs("/v1/fleets/" + company + "/vehicles", officer())).get("items")).hasSize(2);
        MvcResult csv = mvc.perform(get("/v1/fleets/" + company + "/vehicles").with(officer())
                .header("X-Channel", "BACKOFFICE").accept("text/csv")).andReturn();
        assertThat(csv.getResponse().getContentAsString()).startsWith("vin,plate").contains(a, b);
        JsonNode renewals = expect(postAs("/v1/fleets/" + company + "/renewals", Map.of("vins", List.of(a, b),
                "customerCategory", "CITIZEN"), officer()), 200);
        assertThat(renewals.get("items")).hasSize(2);
        expect(postAs("/v1/fleets/" + company + "/driver-assignments", Map.of("vin", a, "driverFileNumber", "TF-DRV",
                "validFrom", TODAY.toString()), clerk()), 201);
        assertThat(body(getAs("/v1/fleets/" + company + "/driver-assignments", officer())).get("items")).hasSize(1);
        assertThat(code(postAs("/v1/fleets/TF-OTHER/driver-assignments", Map.of("vin", a, "driverFileNumber",
                "TF-DRV"), clerk()))).isEqualTo("NOT_IN_FLEET");

        String dealer = ref("TF-DL");
        String stock = customsVehicle("SHORT", dealer);
        assertThat(code(postAs("/v1/dealers/" + dealer + "/handovers", Map.of("vin", a, "buyerFileNumber",
                "TF-NEW"), clerk()))).isEqualTo("NOT_DEALERS");
        JsonNode handed = expect(postAs("/v1/dealers/" + dealer + "/handovers", Map.of("vin", stock,
                "buyerFileNumber", "TF-NEW"), clerk()), 200);
        assertThat(handed.get("ownerFileNumber").asString()).isEqualTo("TF-NEW");
    }

    @Test
    @Tag("VL-033")
    @Tag("VL-035")
    @Tag("VL-037")
    @Tag("VL-039")
    @DisplayName("VL-033/035/037/039: a number is reserved and paid, retained, renewed, sold, exchanged onto a vehicle "
            + "and reported lost")
    void plateTrade() throws Exception {
        series();
        String holder = ref("TF-PL");
        long number = 10_000 + REFS.incrementAndGet() % 80_000;
        JsonNode reservation = expect(postAs("/v1/plate-reservations", Map.of("seriesCode", "IT_NORTH_PRIVATE",
                "number", number, "holderFileNumber", holder, "customerCategory", "CITIZEN"), clerk()), 201);
        assertThat(code(postAs("/v1/plate-reservations", Map.of("seriesCode", "IT_NORTH_PRIVATE", "number", number,
                "holderFileNumber", "TF-X", "customerCategory", "CITIZEN"), clerk()))).isEqualTo("NUMBER_TAKEN");
        paid(reservation);
        JsonNode held = body(getAs("/v1/plates?holderFileNumber=" + holder, officer())).get("items").get(0);
        assertThat(held.get("status").asString()).isEqualTo("RETAINED");
        String plateId = held.get("id").asString();
        assertThat(body(getAs("/v1/plates/" + plateId, officer())).get("number").asLong()).isEqualTo(number);
        paid(expect(postAs("/v1/plates/" + plateId + "/retention-renewals", Map.of("customerCategory", "CITIZEN"),
                clerk()), 201));

        String buyer = ref("TF-BY");
        paid(expect(postAs("/v1/plates/" + plateId + "/sales", Map.of("buyerFileNumber", buyer, "customerCategory",
                "CITIZEN"), clerk()), 201));
        assertThat(body(getAs("/v1/plates?holderFileNumber=" + buyer, officer())).get("items")).hasSize(1);

        String vin = customsVehicle("PRIVATE", buyer);
        PARTNERS.policyEnds.put(vin, TODAY.plusMonths(6));
        paid(expect(postAs("/v1/vehicles/" + vin + "/registrations", Map.of("customerCategory", "CITIZEN"),
                officer()), 201));
        paid(expect(postAs("/v1/vehicles/" + vin + "/plate-exchanges", Map.of("plateId", plateId, "customerCategory",
                "CITIZEN"), clerk()), 201));
        assertThat(body(getAs("/v1/plates/" + plateId, officer())).get("status").asString()).isEqualTo("ASSIGNED");

        assertThat(code(postAs("/v1/plates/" + plateId + "/loss-reports", Map.of("kind", "STOLEN", "policeReference",
                "POL-1", "replacement", "SIDEWAYS", "customerCategory", "CITIZEN"), clerk())))
                .isIn("FIELD_INVALID", "VALIDATION_FAILED");
        paid(expect(postAs("/v1/plates/" + plateId + "/loss-reports", Map.of("kind", "STOLEN", "policeReference",
                ref("POL-"), "replacement", "NEW", "customerCategory", "CITIZEN"), clerk()), 201));
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.vehicle.plate-reported-lost'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    @Tag("VL-034")
    @Tag("VL-036")
    @Tag("VL-038")
    @DisplayName("VL-034/036/038: an auction is bid on and closed; plates are ordered and delivered by the factory; "
            + "trial plates are issued and cancelled")
    void auctionsOrdersTrials() throws Exception {
        series();
        RequestPostProcessor auctioneer = user("u-auction", "PLATE_AUCTIONEER", "vehicle.plate.auction", "vehicle.read");
        long number = Long.parseLong(String.valueOf(1 + REFS.incrementAndGet() % 9).repeat(4));
        JsonNode auction = expect(postAs("/v1/plate-auctions", Map.of("title", "Premium numbers", "salesChannel",
                "ONLINE", "startsAt", Instant.now().minusSeconds(60).toString(), "endsAt",
                Instant.now().plusSeconds(3).toString(), "region", "NORTH", "lots", List.of(Map.of("seriesCode",
                        "IT_NORTH_PRIVATE", "number", number, "reserveMinor", 1000))), auctioneer), 201);
        String auctionId = auction.get("auction").get("id").asString();
        String lotPlate = auction.get("lots").get(0).get("plateId").asString();
        assertThat(code(postAs("/v1/plate-auctions/" + auctionId + "/bids", Map.of("plateId", lotPlate,
                "bidderFileNumber", "TF-B1", "amountMinor", 500), clerk()))).isEqualTo("BID_TOO_LOW");
        expect(postAs("/v1/plate-auctions/" + auctionId + "/bids", Map.of("plateId", lotPlate, "bidderFileNumber",
                "TF-B1", "amountMinor", 2000), clerk()), 201);
        expect(postAs("/v1/plate-auctions/" + auctionId + "/bids", Map.of("plateId", lotPlate, "bidderFileNumber",
                "TF-B2", "amountMinor", 3000), clerk()), 201);
        assertThat(body(getAs("/v1/plate-auctions/" + auctionId, officer())).get("lots")).hasSize(1);
        assertThat(code(postAs("/v1/plate-auctions/" + auctionId + "/close", null, auctioneer)))
                .isEqualTo("AUCTION_RUNNING");
        Thread.sleep(3_500);
        JsonNode closed = expect(postAs("/v1/plate-auctions/" + auctionId + "/close", null, auctioneer), 200);
        assertThat(closed.get("auction").get("status").asString()).isEqualTo("CLOSED");

        RequestPostProcessor factory = user("svc-factory", "PLATE_FACTORY", "vehicle.plate.factory");
        MvcResult raised = postAs("/v1/plate-orders", Map.of("factoryCode", "FACTORY_1"), plateAdmin());
        if (raised.getResponse().getStatus() == 201) {
            JsonNode order = body(raised);
            String orderId = order.get("order").get("id").asString();
            expect(postAs("/v1/plate-orders/" + orderId + "/status", Map.of("status", "IN_PRODUCTION"), factory), 200);
            Map<String, String> serials = new HashMap<>();
            order.get("items").forEach(i -> serials.put(i.get("id").asString(), ref("SN-")));
            assertThat(expect(postAs("/v1/plate-orders/" + orderId + "/status", Map.of("status", "DELIVERED",
                    "serials", serials), factory), 200).get("order").get("status").asString()).isEqualTo("DELIVERED");
            assertThat(body(getAs("/v1/plate-orders/" + orderId, plateAdmin())).get("order").get("status")
                    .asString()).isEqualTo("DELIVERED");
        } else {
            assertThat(code(raised)).isEqualTo("NOTHING_TO_ORDER");
        }

        expect(postAs("/v1/plate-series", Map.of("code", "IT_NORTH_TRIAL", "region", "NORTH", "plateCategory", "TRIAL",
                "colourCode", "RED", "design", "STANDARD", "prefix", "T", "firstNumber", 1, "lastNumber", 9_999,
                "active", true), plateAdmin()), 200);
        String dealer = ref("TF-TR");
        assertThat(code(postAs("/v1/trial-plates", Map.of("dealerFileNumber", dealer, "region", "NORTH", "days", 60,
                "customerCategory", "DEALER"), clerk()))).isEqualTo("TRIAL_DAYS_INVALID");
        JsonNode trial = expect(postAs("/v1/trial-plates", Map.of("dealerFileNumber", dealer, "region", "NORTH", "days",
                14, "customerCategory", "DEALER"), clerk()), 201);
        paid(trial);
        String trialId = db.sql("select id::text from trial_plate where dealer_file = :d").param("d", dealer)
                .query(String.class).single();
        assertThat(body(getAs("/v1/trial-plates/" + trialId, clerk())).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(expect(postAs("/v1/trial-plates/" + trialId + "/cancel", null, clerk()), 200).get("status")
                .asString()).isEqualTo("CANCELLED");
        assertThat(code(postAs("/v1/trial-plates/" + trialId + "/exchange", null, clerk())))
                .isEqualTo("TRIAL_PLATE_STATUS_INVALID");
    }
}
