package gov.itms.trafficfile.api;

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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 * End-to-end slice test of traffic files: HTTP → security → service → identity register (a real HTTP stub) →
 * PostgreSQL (V1 guards and temporal history) → audit and event outboxes. Every request and response is validated
 * against traffic-file-service.v1.yaml. Tests share one database, so register people are opened once and passports
 * are new per test.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("DL-001")
class TrafficFileApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final IdentityRegisterStub REGISTER = new IdentityRegisterStub();
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("itms.traffic-file.register.base-url", REGISTER::baseUrl);
        registry.add("itms.traffic-file.licensing.authorities[DXB_DED]", () -> REGISTER.baseUrl() + "/dxb");
    }

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/traffic-file-service.v1.yaml").build();
    }

    @AfterAll
    static void stopRegister() {
        REGISTER.stop();
    }

    // ------------------------------------------------------------------ actors (token-claims mode: national scope)

    private static RequestPostProcessor officer() {
        return user("u-officer", "officer", "TRAFFIC_FILE_OFFICER", "trafficfile.create", "trafficfile.read",
                "trafficfile.update");
    }

    private static RequestPostProcessor sensitiveReader() {
        return user("u-sensitive", "sensitive", "TRAFFIC_FILE_SENSITIVE_READER", "trafficfile.read",
                "trafficfile.sensitive.read");
    }

    private static RequestPostProcessor reader() {
        return user("u-reader", "reader", "READER", "trafficfile.read");
    }

    private static RequestPostProcessor user(String sub, String username, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", username)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    // ------------------------------------------------------------------ helpers with contract validation

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String channel, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Correlation-Id", "tf-it")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (channel != null) {
            builder.header("X-Channel", channel);
        }
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
        SimpleResponse response = SimpleResponse.Builder
                .status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .build();
        ValidationReport report = result.getResponse().getStatus() < 400
                ? contract.validate(req.build(), response)
                : contract.validateResponse(path, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, path,
                result.getResponse().getStatus()).isEmpty();
        return result;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return body(r).get("code").asString();
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        return call(get(path), Request.Method.GET, path, null, who, "BACKOFFICE", null);
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who, "BACKOFFICE", "it-" + UUID.randomUUID());
    }

    private static Map<String, Object> nationalId(String number) {
        Map<String, Object> m = new HashMap<>();
        m.put("document", Map.of("type", "NATIONAL_ID", "nationalIdNo", number));
        m.put("category", "INDIVIDUAL");
        m.put("type", "CITIZEN");
        m.put("regionOpened", "DXB");
        return m;
    }

    private static String newPassport() {
        return "IT" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
    }

    private static Map<String, Object> visitor(String passport, LocalDate birthDate) {
        Map<String, Object> m = new HashMap<>();
        m.put("document", Map.of("type", "PASSPORT", "passportNo", passport, "passportCountry", "GBR"));
        m.put("category", "INDIVIDUAL");
        m.put("type", "VISITOR");
        m.put("regionOpened", "AUH");
        m.put("visitorIdentity", Map.of("name", Map.of("givenEn", "Jane", "familyEn", "Visitor"),
                "nationality", "GBR", "birthDate", birthDate.toString(), "gender", "FEMALE"));
        return m;
    }

    /** Opens the register person's file, or finds it if an earlier test opened it. */
    private String fileOf(String nationalIdNo) throws Exception {
        MvcResult r = postAs("/v1/traffic-files/individuals", nationalId(nationalIdNo), officer());
        if (r.getResponse().getStatus() == 201) {
            return body(r).get("fileNumber").asString();
        }
        assertThat(code(r)).isEqualTo("FILE_ALREADY_EXISTS");
        return body(r).get("detail").asString().replaceAll(".*?([0-9]{11}).*", "$1");
    }

    private static int luhn(String digits) {
        int sum = 0;
        boolean twice = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
            twice = !twice;
        }
        return (10 - sum % 10) % 10;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Tag("DL-002")
    @DisplayName("a national ID file takes its identity from the register, locked, masked, audited and published")
    void opensFromRegister() throws Exception {
        MvcResult r = postAs("/v1/traffic-files/individuals", nationalId(IdentityRegisterStub.RESIDENT), officer());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode file = body(r);
        String number = file.get("fileNumber").asString();
        assertThat(number).matches("[0-9]{11}");
        assertThat(number.charAt(10) - '0').as("Luhn check digit (Q-022)")
                .isEqualTo(luhn(number.substring(0, 10)));
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/v1/traffic-files/" + number);
        assertThat(file.get("identitySource").asString()).isEqualTo("REGISTER");
        assertThat(file.get("identity").get("nationalIdNo").asString()).isEqualTo("***********5672");
        assertThat(file.get("identity").get("name").get("givenEn").asString()).isEqualTo("Maria");
        assertThat(file.get("identity").get("name").get("familyNl").asString()).isEqualTo("سانتوس");
        assertThat(file.get("identity").get("gender").asString()).isEqualTo("FEMALE");
        assertThat(file.get("identity").get("residencyStatus").asString()).isEqualTo("RESIDENT");
        assertThat(file.get("identity").get("residencyExpiry").asString()).isEqualTo("2028-01-31");
        assertThat(file.get("lockedFields").toString()).contains("birthDate", "name", "nationality");
        assertThat(file.get("version").asLong()).isEqualTo(1);

        assertThat(db.sql("select count(*) from audit_outbox where action = 'TRAFFIC_FILE_OPENED' and entity_id = :n")
                .param("n", number).query(Long.class).single()).isEqualTo(1);
        String event = db.sql("select data::text from event_outbox where event_type = 'itms.traffic-file.opened' "
                + "and subject = :n").param("n", number).query(String.class).single();
        assertThat(event).contains(number).doesNotContain(IdentityRegisterStub.RESIDENT).doesNotContain("Maria");
        String audit = db.sql("select new_value::text from audit_outbox where action = 'TRAFFIC_FILE_OPENED' "
                + "and entity_id = :n").param("n", number).query(String.class).single();
        assertThat(audit).doesNotContain(IdentityRegisterStub.RESIDENT);
    }

    @Test
    @Tag("DL-009")
    @DisplayName("one file per identity document: a second answers 409 naming the existing file")
    void duplicateDocument() throws Exception {
        String number = fileOf(IdentityRegisterStub.CITIZEN);
        MvcResult again = postAs("/v1/traffic-files/individuals", nationalId(IdentityRegisterStub.CITIZEN), officer());
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("FILE_ALREADY_EXISTS");
        assertThat(body(again).get("detail").asString()).contains(number);

        String passport = newPassport();
        assertThat(postAs("/v1/traffic-files/individuals", visitor(passport, LocalDate.of(1985, 5, 5)), officer())
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult second = postAs("/v1/traffic-files/individuals", visitor(passport, LocalDate.of(1985, 5, 5)),
                officer());
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @Tag("DL-002")
    @DisplayName("register outcomes: fields may not be entered, unknown and deceased people are refused, outage is 503")
    void registerOutcomes() throws Exception {
        Map<String, Object> withFields = nationalId(IdentityRegisterStub.CITIZEN);
        withFields.put("visitorIdentity", Map.of("name", Map.of("givenEn", "X", "familyEn", "Y"),
                "nationality", "ARE", "birthDate", "1990-01-01", "gender", "MALE"));
        MvcResult r = postAs("/v1/traffic-files/individuals", withFields, officer());
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(r)).isEqualTo("REGISTER_FIELDS_GIVEN");

        r = postAs("/v1/traffic-files/individuals", nationalId("123456789012"), officer());
        assertThat(code(r)).isEqualTo("REGISTER_PERSON_NOT_FOUND");
        r = postAs("/v1/traffic-files/individuals", nationalId(IdentityRegisterStub.DECEASED), officer());
        assertThat(code(r)).isEqualTo("PERSON_DECEASED");
        r = postAs("/v1/traffic-files/individuals", nationalId(IdentityRegisterStub.FAILING), officer());
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(r)).isEqualTo("REGISTER_UNAVAILABLE");
        r = postAs("/v1/traffic-files/individuals", nationalId("12AB"), officer());
        assertThat(code(r)).isEqualTo("FIELD_INVALID");
    }

    @Test
    @DisplayName("a visitor's file is opened from the passport; identity required; no future birth date")
    void visitorFile() throws Exception {
        MvcResult r = postAs("/v1/traffic-files/individuals", visitor(newPassport(), LocalDate.of(1985, 5, 5)),
                officer());
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(r).get("identitySource").asString()).isEqualTo("DOCUMENT");
        assertThat(body(r).get("lockedFields")).isEmpty();
        assertThat(body(r).get("identity").get("passportNo").asString()).startsWith("*******");

        Map<String, Object> noIdentity = visitor(newPassport(), LocalDate.of(1985, 5, 5));
        noIdentity.remove("visitorIdentity");
        assertThat(code(postAs("/v1/traffic-files/individuals", noIdentity, officer())))
                .isEqualTo("VISITOR_IDENTITY_REQUIRED");
        assertThat(code(postAs("/v1/traffic-files/individuals", visitor(newPassport(), LocalDate.now().plusDays(2)),
                officer()))).isEqualTo("BIRTH_DATE_IN_FUTURE");
    }

    private static RequestPostProcessor customer(String nationalId, String... permissions) {
        return jwt().jwt(j -> j.subject("u-cust-" + nationalId).claim("preferred_username", nationalId)
                .claim("subject_type", "CUSTOMER").claim("national_id", nationalId)
                .claim("roles", List.of("CUSTOMER")).claim("permissions", Arrays.asList(permissions)));
    }

    @Test
    @Tag("CH-002")
    @Tag("CC-001")
    @DisplayName("ADR-067 d: a customer reads only their own file, found by the national id in the token; the view "
            + "is audited; staff and customers without the permission get 403; no file is 404")
    void ownFile() throws Exception {
        String number = fileOf(IdentityRegisterStub.CITIZEN);

        MvcResult own = getAs("/v1/me/traffic-file", customer(IdentityRegisterStub.CITIZEN,
                "customer.trafficfile.read"));
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(own).get("fileNumber").asString()).isEqualTo(number);
        assertThat(body(own).get("identity").get("nationalIdNo").asString()).isEqualTo(IdentityRegisterStub.CITIZEN);
        assertThat(db.sql("select count(*) from audit_outbox where action = 'TRAFFIC_FILE_VIEWED' and entity_id = :n "
                        + "and new_value::text like '%OWNER%'")
                .param("n", number).query(Long.class).single()).isPositive();

        assertThat(getAs("/v1/me/traffic-file", customer(IdentityRegisterStub.CITIZEN)).getResponse().getStatus())
                .as("customer without the permission").isEqualTo(403);
        assertThat(getAs("/v1/me/traffic-file", user("u-staff", "staff", "READER", "trafficfile.read",
                "customer.trafficfile.read")).getResponse().getStatus()).as("staff token").isEqualTo(403);
        MvcResult none = getAs("/v1/me/traffic-file", customer("784190000000001", "customer.trafficfile.read"));
        assertThat(none.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(none)).isEqualTo("OWN_FILE_NOT_FOUND");
    }

    @Test
    @Tag("SEC-014")
    @DisplayName("full identity numbers and health data only for trafficfile.sensitive.read")
    void sensitiveData() throws Exception {
        Map<String, Object> request = visitor(newPassport(), LocalDate.of(1980, 1, 1));
        request.put("profile", Map.of("bloodGroup", "O_POS", "physicalConditions", List.of("LIMB_PROSTHESIS"),
                "languages", List.of("EN")));
        MvcResult opened = postAs("/v1/traffic-files/individuals", request, officer());
        String number = body(opened).get("fileNumber").asString();
        assertThat(body(opened).get("profile").get("bloodGroup").isNull()).isTrue();

        JsonNode officerView = body(getAs("/v1/traffic-files/" + number, reader()));
        assertThat(officerView.get("profile").get("physicalConditions").isNull()).isTrue();
        JsonNode full = body(getAs("/v1/traffic-files/" + number, sensitiveReader()));
        assertThat(full.get("profile").get("bloodGroup").asString()).isEqualTo("O_POS");
        assertThat(full.get("identity").get("passportNo").asString()).startsWith("IT");

        JsonNode history = body(getAs("/v1/traffic-files/" + number + "/history", reader()));
        assertThat(history.get("items").get(0).get("state").has("blood_group")).isFalse();
        assertThat(history.get("items").get(0).get("state").get("passport_no").asString()).startsWith("*");
    }

    @Test
    @Tag("DL-005")
    @Tag("DAT-002")
    @DisplayName("the profile is replaced with the version read; a stale version is refused; every version is kept")
    void profileUpdate() throws Exception {
        MvcResult opened = postAs("/v1/traffic-files/individuals", visitor(newPassport(), LocalDate.of(1975, 3, 3)),
                officer());
        String number = body(opened).get("fileNumber").asString();
        Map<String, Object> update = Map.of("version", 1, "profile",
                Map.of("mobile", "+971501234567", "email", "jane@example.org", "educationLevel", "BACHELOR"));
        MvcResult r = postAs("/v1/traffic-files/" + number + "/profile-updates", update, officer());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(r).get("version").asLong()).isEqualTo(2);
        assertThat(body(r).get("profile").get("email").asString()).isEqualTo("jane@example.org");

        MvcResult stale = postAs("/v1/traffic-files/" + number + "/profile-updates", update, officer());
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("VERSION_MISMATCH");

        JsonNode history = body(getAs("/v1/traffic-files/" + number + "/history", reader()));
        assertThat(history.get("items")).hasSize(2);
        assertThat(history.get("items").get(0).get("validTo").isNull()).isFalse();
        assertThat(history.get("items").get(1).get("state").get("email").asString()).isEqualTo("jane@example.org");

        MvcResult bad = postAs("/v1/traffic-files/" + number + "/profile-updates",
                Map.of("version", 2, "profile", Map.of("languages", List.of("EN", "EN"))), officer());
        assertThat(code(bad)).isIn("LIST_DUPLICATES", "VALIDATION_FAILED");
    }

    @Test
    @Tag("CC-102")
    @DisplayName("searching by document finds the file and is audited, found or not")
    void search() throws Exception {
        String number = fileOf(IdentityRegisterStub.CITIZEN);
        MvcResult found = postAs("/v1/traffic-files/searches",
                Map.of("document", Map.of("type", "NATIONAL_ID", "nationalIdNo", IdentityRegisterStub.CITIZEN)),
                reader());
        assertThat(body(found).get("items").get(0).get("fileNumber").asString()).isEqualTo(number);
        MvcResult none = postAs("/v1/traffic-files/searches",
                Map.of("document", Map.of("type", "PASSPORT", "passportNo", newPassport(), "passportCountry", "FRA")),
                reader());
        assertThat(body(none).get("items")).isEmpty();
        assertThat(db.sql("select count(*) from audit_outbox where action = 'TRAFFIC_FILE_SEARCHED'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("permissions, channel and Idempotency-Key are required")
    void guards() throws Exception {
        MvcResult r = postAs("/v1/traffic-files/individuals", visitor(newPassport(), LocalDate.of(1980, 1, 1)),
                reader());
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        r = call(post("/v1/traffic-files/individuals"), Request.Method.POST, "/v1/traffic-files/individuals",
                visitor(newPassport(), LocalDate.of(1980, 1, 1)), officer(), null, "it-" + UUID.randomUUID());
        assertThat(code(r)).isEqualTo("CHANNEL_REQUIRED");
        r = call(post("/v1/traffic-files/individuals"), Request.Method.POST, "/v1/traffic-files/individuals",
                visitor(newPassport(), LocalDate.of(1980, 1, 1)), officer(), "BACKOFFICE", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(getAs("/v1/traffic-files/99999999990", reader()).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @Tag("DL-002")
    @Tag("CC-122")
    @DisplayName("the database keeps the number and identity, register fields and history")
    void databaseGuards() throws Exception {
        String number = fileOf(IdentityRegisterStub.CITIZEN);
        assertThatThrownBy(() -> db.sql("update traffic_file set national_id_no = '1', version = version + 1 "
                + "where file_number = :n").param("n", number).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("identity document");
        assertThatThrownBy(() -> db.sql("update traffic_file set given_en = 'Changed', version = version + 1 "
                + "where file_number = :n").param("n", number).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("register sync");
        assertThatThrownBy(() -> db.sql("delete from traffic_file where file_number = :n").param("n", number).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> db.sql("delete from traffic_file_history").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------ companies (DL-003, DL-004)

    private String individualFile() throws Exception {
        MvcResult r = postAs("/v1/traffic-files/individuals", visitor(newPassport(), LocalDate.of(1980, 1, 1)),
                officer());
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return body(r).get("fileNumber").asString();
    }

    private static Map<String, Object> company(String licenceNo, List<String> signatories, List<String> managers) {
        Map<String, Object> m = new HashMap<>();
        m.put("licence", Map.of("source", "DXB_DED", "number", licenceNo));
        m.put("category", "COMPANY");
        m.put("type", "LLC");
        m.put("regionOpened", "DXB");
        m.put("signatories", signatories);
        m.put("fleetManagers", managers);
        return m;
    }

    @Test
    @Tag("DL-003")
    @Tag("DL-004")
    @DisplayName("a company file comes from the licensing authority, names representatives, and replaces them")
    void companyFile() throws Exception {
        String signatory = individualFile();
        String manager = individualFile();
        MvcResult r = postAs("/v1/traffic-files/companies",
                company(IdentityRegisterStub.LICENCE_ACTIVE, List.of(signatory), List.of(manager)), officer());
        String number;
        if (r.getResponse().getStatus() == 409) {
            // A rerun against the same database: the licence already has its file.
            number = body(r).get("detail").asString().replaceAll(".*?([0-9]{11}).*", "$1");
        } else {
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
            JsonNode file = body(r);
            number = file.get("fileNumber").asString();
            assertThat(file.get("kind").asString()).isEqualTo("COMPANY");
            assertThat(file.get("identity").isNull()).isTrue();
            assertThat(file.get("profile").isNull()).isTrue();
            assertThat(file.get("company").get("nameEn").asString()).isEqualTo("Rapid Transport LLC");
            assertThat(file.get("company").get("licenceExpiry").asString()).isEqualTo("2030-12-31");
            assertThat(file.get("company").get("signatories").get(0).asString()).isEqualTo(signatory);
            assertThat(file.get("lockedFields").toString()).contains("activities", "licenceExpiry");
        }
        long version = body(getAs("/v1/traffic-files/" + number, reader())).get("version").asLong();
        MvcResult updated = postAs("/v1/traffic-files/" + number + "/representatives-updates",
                Map.of("version", version, "signatories", List.of(manager), "fleetManagers", List.of()), officer());
        assertThat(updated.getResponse().getStatus()).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(updated).get("company").get("signatories").get(0).asString()).isEqualTo(manager);
        assertThat(body(updated).get("version").asLong()).isEqualTo(version + 1);

        MvcResult profileOnCompany = postAs("/v1/traffic-files/" + number + "/profile-updates",
                Map.of("version", version + 1, "profile", Map.of()), officer());
        assertThat(code(profileOnCompany)).isEqualTo("WRONG_FILE_KIND");
        MvcResult companyAsSignatory = postAs("/v1/traffic-files/companies",
                company(IdentityRegisterStub.LICENCE_ACTIVE_2, List.of(number), List.of()), officer());
        assertThat(code(companyAsSignatory)).isIn("REPRESENTATIVE_INVALID", "FILE_ALREADY_EXISTS");
    }

    @Test
    @Tag("DL-003")
    @DisplayName("unknown, expired, cancelled and unanswered licences are refused; a second file is 409")
    void companyLicenceOutcomes() throws Exception {
        String signatory = individualFile();
        assertThat(code(postAs("/v1/traffic-files/companies", company("CN-0000404", List.of(signatory), List.of()),
                officer()))).isEqualTo("COMPANY_LICENCE_NOT_FOUND");
        assertThat(code(postAs("/v1/traffic-files/companies",
                company(IdentityRegisterStub.LICENCE_EXPIRED, List.of(signatory), List.of()), officer())))
                .isEqualTo("COMPANY_LICENCE_EXPIRED");
        assertThat(code(postAs("/v1/traffic-files/companies",
                company(IdentityRegisterStub.LICENCE_CANCELLED, List.of(signatory), List.of()), officer())))
                .isEqualTo("COMPANY_LICENCE_CANCELLED");
        MvcResult down = postAs("/v1/traffic-files/companies",
                company(IdentityRegisterStub.LICENCE_FAILING, List.of(signatory), List.of()), officer());
        assertThat(down.getResponse().getStatus()).isEqualTo(503);
        Map<String, Object> otherSource = company(IdentityRegisterStub.LICENCE_ACTIVE, List.of(signatory), List.of());
        otherSource.put("licence", Map.of("source", "SHJ_SEDD", "number", IdentityRegisterStub.LICENCE_ACTIVE));
        assertThat(code(postAs("/v1/traffic-files/companies", otherSource, officer())))
                .as("a source without a configured authority is unknown").isEqualTo("COMPANY_LICENCE_NOT_FOUND");
        assertThat(postAs("/v1/traffic-files/companies",
                company(IdentityRegisterStub.LICENCE_ACTIVE, List.of(), List.of()), officer())
                .getResponse().getStatus()).isIn(400, 422);
    }

    @Test
    @Tag("DL-003")
    @DisplayName("the database keeps a company's licence and authority fields")
    void companyDatabaseGuards() throws Exception {
        String signatory = individualFile();
        MvcResult r = postAs("/v1/traffic-files/companies",
                company(IdentityRegisterStub.LICENCE_ACTIVE, List.of(signatory), List.of()), officer());
        String number = r.getResponse().getStatus() == 201 ? body(r).get("fileNumber").asString()
                : body(r).get("detail").asString().replaceAll(".*?([0-9]{11}).*", "$1");
        assertThatThrownBy(() -> db.sql("update traffic_file set commercial_licence_no = 'CN-1', version = version + 1 "
                + "where file_number = :n").param("n", number).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("identity document");
        assertThatThrownBy(() -> db.sql("update traffic_file set licence_expiry = date '2040-01-01', "
                + "version = version + 1 where file_number = :n").param("n", number).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("register sync");
        assertThatThrownBy(() -> db.sql("update traffic_file set signatories = '{}', version = version + 1 "
                + "where file_number = :n").param("n", number).update())
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------ ban list (DL-013)

    private static RequestPostProcessor banOfficer() {
        return user("u-ban", "ban-officer", "BAN_LIST_OFFICER", "trafficfile.ban.place", "trafficfile.ban.lift",
                "trafficfile.ban.read");
    }

    @Test
    @Tag("DL-013")
    @DisplayName("a banned person cannot be given a file until the ban is lifted; entries are masked and audited")
    void banList() throws Exception {
        String passport = newPassport();
        Map<String, Object> ban = new HashMap<>();
        ban.put("person", Map.of("type", "PASSPORT", "passportNo", passport, "passportCountry", "GBR"));
        ban.put("reason", "court order 12/2026");
        ban.put("source", "COURT");
        ban.put("sourceRef", "CO-" + passport);
        ban.put("endDate", LocalDate.now().plusYears(1).toString());
        MvcResult placed = postAs("/v1/ban-entries", ban, banOfficer());
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        String id = body(placed).get("id").asString();
        assertThat(body(placed).get("inForce").asBoolean()).isTrue();
        assertThat(body(placed).get("person").get("passportNo").asString()).startsWith("*");
        assertThat(body(placed).get("fileNumber").isNull()).isTrue();

        assertThat(postAs("/v1/ban-entries", ban, banOfficer()).getResponse().getStatus())
                .as("the same source reference twice").isEqualTo(409);
        MvcResult refused = postAs("/v1/traffic-files/individuals", visitor(passport, LocalDate.of(1980, 1, 1)),
                officer());
        assertThat(code(refused)).isEqualTo("PERSON_BANNED");

        MvcResult search = postAs("/v1/ban-entries/searches",
                Map.of("person", Map.of("type", "PASSPORT", "passportNo", passport, "passportCountry", "GBR")),
                banOfficer());
        assertThat(body(search).get("items")).hasSize(1);
        assertThat(getAs("/v1/ban-entries/" + id, banOfficer()).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAs("/v1/ban-entries/" + id, reader()).getResponse().getStatus()).isEqualTo(403);

        MvcResult lifted = postAs("/v1/ban-entries/" + id + "/lifts", Map.of("reason", "order revoked"),
                banOfficer());
        assertThat(body(lifted).get("status").asString()).isEqualTo("LIFTED");
        assertThat(code(postAs("/v1/ban-entries/" + id + "/lifts", Map.of("reason", "again"), banOfficer())))
                .isEqualTo("BAN_NOT_ACTIVE");
        assertThat(postAs("/v1/traffic-files/individuals", visitor(passport, LocalDate.of(1980, 1, 1)), officer())
                .getResponse().getStatus()).isEqualTo(201);

        assertThat(db.sql("select count(*) from ban_entry_history where ban_id = cast(:id as uuid)").param("id", id)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(db.sql("select count(*) from event_outbox where event_type in ('itms.ban.placed', 'itms.ban.lifted') "
                + "and subject = :id").param("id", id).query(Long.class).single()).isEqualTo(2);
        assertThat(db.sql("select string_agg(data::text, ' ') from event_outbox where subject = :id").param("id", id)
                .query(String.class).single()).doesNotContain(passport);
        assertThatThrownBy(() -> db.sql("delete from ban_entry where id = cast(:id as uuid)").param("id", id).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
    }

    @Test
    @Tag("DL-013")
    @DisplayName("ban dates are checked")
    void banDates() throws Exception {
        Map<String, Object> ban = new HashMap<>();
        ban.put("person", Map.of("type", "NATIONAL_ID", "nationalIdNo", "123456789"));
        ban.put("reason", "administrative ban");
        ban.put("source", "AUTHORITY");
        ban.put("startDate", LocalDate.now().toString());
        ban.put("endDate", LocalDate.now().minusDays(1).toString());
        assertThat(code(postAs("/v1/ban-entries", ban, banOfficer()))).isEqualTo("BAN_DATES_INVALID");
    }

    // ------------------------------------------------------------------ duplicates and merges (DL-009)

    private static RequestPostProcessor supervisor() {
        return user("u-sup", "supervisor", "TRAFFIC_FILE_SUPERVISOR", "trafficfile.read", "trafficfile.merge");
    }

    private String personFile(String given, String family, LocalDate born) throws Exception {
        Map<String, Object> request = visitor(newPassport(), born);
        request.put("visitorIdentity", Map.of("name", Map.of("givenEn", given, "familyEn", family),
                "nationality", "GBR", "birthDate", born.toString(), "gender", "MALE"));
        MvcResult r = postAs("/v1/traffic-files/individuals", request, officer());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("fileNumber").asString();
    }

    @Test
    @Tag("DL-009")
    @DisplayName("probable duplicates are listed; a supervisor merges one into the other, which stays readable")
    void duplicatesAndMerge() throws Exception {
        LocalDate born = LocalDate.of(1961, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3000));
        String survivor = personFile("Jonathan", "Smithers", born);
        String duplicate = personFile("Jonathon", "Smithers", born);
        personFile("Peter", "Parker", born);

        JsonNode candidates = body(getAs("/v1/traffic-files/" + survivor + "/probable-duplicates", reader()));
        assertThat(candidates.get("items")).hasSize(1);
        assertThat(candidates.get("items").get(0).get("file").get("fileNumber").asString()).isEqualTo(duplicate);

        assertThat(postAs("/v1/traffic-files/" + survivor + "/merges",
                Map.of("mergedFileNumber", duplicate, "reason", "same person"), officer()).getResponse().getStatus())
                .isEqualTo(403);
        MvcResult merged = postAs("/v1/traffic-files/" + survivor + "/merges",
                Map.of("mergedFileNumber", duplicate, "reason", "same person, two passports"), supervisor());
        assertThat(merged.getResponse().getStatus()).as(merged.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(merged).get("survivingFileNumber").asString()).isEqualTo(survivor);

        JsonNode mergedFile = body(getAs("/v1/traffic-files/" + duplicate, reader()));
        assertThat(mergedFile.get("status").asString()).isEqualTo("MERGED");
        assertThat(mergedFile.get("survivingFileNumber").asString()).isEqualTo(survivor);
        assertThat(code(postAs("/v1/traffic-files/" + duplicate + "/profile-updates",
                Map.of("version", 2, "profile", Map.of()), officer()))).isEqualTo("FILE_NOT_ACTIVE");
        assertThat(body(getAs("/v1/traffic-files/" + survivor + "/probable-duplicates", reader())).get("items"))
                .isEmpty();
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.traffic-file.merged' "
                + "and subject = :n").param("n", duplicate).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> db.sql("update traffic_file set status = 'ACTIVE', surviving_file_id = null, "
                + "version = version + 1 where file_number = :n").param("n", duplicate).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("merged");
        assertThatThrownBy(() -> db.sql("delete from file_merge").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------ name search (CC-101)

    private String namedFile(Map<String, String> name) throws Exception {
        LocalDate born = LocalDate.of(1971, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3000));
        Map<String, Object> request = visitor(newPassport(), born);
        request.put("visitorIdentity", Map.of("name", name, "nationality", "GBR", "birthDate", born.toString(),
                "gender", "MALE"));
        MvcResult r = postAs("/v1/traffic-files/individuals", request, officer());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("fileNumber").asString();
    }

    /** file number -> match of a name search. */
    private Map<String, String> byName(String name, String kind) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("kind", kind);
        MvcResult r = postAs("/v1/traffic-files/name-searches", request, reader());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        Map<String, String> hits = new HashMap<>();
        body(r).get("items").forEach(h -> hits.put(h.get("fileNumber").asString(), h.get("match").asString()));
        return hits;
    }

    @Test
    @Tag("CC-101")
    @Tag("CC-102")
    @DisplayName("name search: partial, both languages, transliterated and phonetic; audited without the name")
    void nameSearch() throws Exception {
        String mansoori = namedFile(Map.of("givenNl", "مُحَمَّد", "familyNl", "المنصوري", "givenEn", "Mohammed",
                "familyEn", "Al-Mansoori"));
        String khalid = namedFile(Map.of("givenEn", "Khalid", "familyEn", "Bin Waleed"));
        String fatima = namedFile(Map.of("givenNl", "فاطمة", "familyNl", "عبدالرحمن"));

        assertThat(byName("mohammed al mansoori", null)).containsEntry(mansoori, "EXACT");
        assertThat(byName("محمد المنصوري", null)).as("harakat folded").containsEntry(mansoori, "EXACT");
        assertThat(byName("Mansoo", null)).as("partial").containsEntry(mansoori, "PARTIAL");
        assertThat(byName("منصور", null)).as("partial, national language").containsEntry(mansoori, "PARTIAL");
        assertThat(byName("Muhammad Almansuri", null)).as("phonetic").containsEntry(mansoori, "PHONETIC");
        assertThat(byName("خالد", null)).as("transliterated, Arabic to Latin").containsEntry(khalid, "PHONETIC");
        assertThat(byName("Fatima Abdul Rahman", null)).as("transliterated, Latin to Arabic")
                .containsEntry(fatima, "PHONETIC");
        assertThat(byName("Mohammed Khalid", null)).as("every word within one name").doesNotContainKeys(
                mansoori, khalid);
        assertThat(byName("Mansoori", "COMPANY")).doesNotContainKey(mansoori);

        assertThat(code(postAs("/v1/traffic-files/name-searches", Map.of("name", "12345"), reader())))
                .isEqualTo("NAME_QUERY_INVALID");
        assertThat(postAs("/v1/traffic-files/name-searches", Map.of("name", "Mohammed"),
                user("u-none", "none", "NONE", "vehicle.read")).getResponse().getStatus()).isEqualTo(403);

        List<String> audits = db.sql("select new_value::text from audit_outbox where action = 'TRAFFIC_FILE_NAME_SEARCHED'")
                .query(String.class).list();
        assertThat(audits).hasSizeGreaterThanOrEqualTo(9).anyMatch(a -> a.contains(mansoori))
                .noneMatch(a -> a.contains("Mansoo") || a.contains("منصور"));
        assertThat(body(getAs("/v1/traffic-files/" + mansoori + "/history", reader())).toString())
                .doesNotContain("name_key").doesNotContain("name_text");
    }

    // ------------------------------------------------------------------ register changes (DL-010)

    private static RequestPostProcessor registerFeed() {
        return user("svc-register", "register-adapter", "IDENTITY_REGISTER_FEED", "trafficfile.register.notify");
    }

    private MvcResult notifyRegister(String eventId, String nationalId, String type) throws Exception {
        return call(post("/v1/identity-register-notifications"), Request.Method.POST,
                "/v1/identity-register-notifications",
                Map.of("eventId", eventId, "nationalIdNo", nationalId, "changeType", type), registerFeed(), "PARTNER",
                null);
    }

    @Test
    @Tag("DL-010")
    @DisplayName("register notices update the locked identity once each; a death makes the file DECEASED")
    void registerChanges() throws Exception {
        String mover = "784196512345674";
        REGISTER.override(mover, "Lena", "Mover", "RESIDENT", "ALIVE");
        String number = fileOf(mover);

        REGISTER.override(mover, "Lena", "Mover-Changed", "RESIDENT", "ALIVE");
        MvcResult applied = notifyRegister("ev-name-" + number, mover, "NAME");
        assertThat(applied.getResponse().getStatus()).as(applied.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(applied).get("outcome").asString()).isEqualTo("APPLIED");
        assertThat(body(getAs("/v1/traffic-files/" + number, reader())).get("identity").get("name").get("familyEn")
                .asString()).isEqualTo("Mover-Changed");
        assertThat(body(notifyRegister("ev-name-" + number, mover, "NAME")).get("outcome").asString())
                .isEqualTo("DUPLICATE");

        REGISTER.override(mover, "Lena", "Mover-Changed", "RESIDENT", "DECEASED");
        assertThat(body(notifyRegister("ev-death-" + number, mover, "DEATH")).get("outcome").asString())
                .isEqualTo("APPLIED");
        assertThat(body(getAs("/v1/traffic-files/" + number, reader())).get("status").asString())
                .isEqualTo("DECEASED");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.traffic-file.deceased' "
                + "and subject = :n").param("n", number).query(Long.class).single()).isEqualTo(1);
        assertThat(body(notifyRegister("ev-none-" + number, "123456780", "NAME")).get("outcome").asString())
                .isEqualTo("NO_FILE");
        assertThat(call(post("/v1/identity-register-notifications"), Request.Method.POST,
                "/v1/identity-register-notifications",
                Map.of("eventId", "ev-x", "nationalIdNo", mover, "changeType", "NAME"), officer(), "PARTNER", null)
                .getResponse().getStatus()).isEqualTo(403);
    }
}
