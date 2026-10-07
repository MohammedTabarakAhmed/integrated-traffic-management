package gov.itms.document.api;

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
import gov.itms.document.infrastructure.ClamdStub;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end slice test of documents and certificates: HTTP → security → service → PostgreSQL (V1 guards), a real
 * MinIO object store (S3 API, SigV4) and a TCP stand-in for clamd. JSON requests and responses are validated against
 * document-service.v1.yaml; binary bodies are checked byte for byte. Two small document types replace the configured
 * list so the size limit is reachable.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.documents.store.bucket=itms-documents",
        "itms.documents.store.access-key=" + DocumentApiIT.MINIO_USER,
        "itms.documents.store.secret-key=" + DocumentApiIT.MINIO_PASSWORD,
        "itms.documents.scanner.host=127.0.0.1",
        "itms.documents.scanner.timeout=5s",
        "itms.documents.types[0].code=SUPPORTING_DOCUMENT",
        "itms.documents.types[0].media-types=application/pdf,image/png",
        "itms.documents.types[0].max-size=4KB",
        "itms.documents.types[0].classification=INTERNAL",
        "itms.documents.types[1].code=IDENTITY_DOCUMENT",
        "itms.documents.types[1].media-types=application/pdf",
        "itms.documents.types[1].max-size=4KB",
        "itms.documents.types[1].classification=CONFIDENTIAL"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-080")
class DocumentApiIT {

    static final String MINIO_USER = "itms-it-access";
    static final String MINIO_PASSWORD = "itms-it-secret-key-0123456789";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", MINIO_USER)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static final ClamdStub CLAMD = new ClamdStub();
    private static OpenApiInteractionValidator contract;
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) {
        registry.add("itms.documents.store.endpoint", () -> "http://" + MINIO.getHost() + ":"
                + MINIO.getMappedPort(9000));
        registry.add("itms.documents.scanner.port", CLAMD::port);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void setUp() throws Exception {
        contract = OpenApiInteractionValidator.createFor("/openapi/document-service.v1.yaml").build();
        assertThat(MINIO.execInContainer("mc", "alias", "set", "local", "http://127.0.0.1:9000", MINIO_USER,
                MINIO_PASSWORD).getExitCode()).isZero();
        assertThat(MINIO.execInContainer("mc", "mb", "--ignore-existing", "local/itms-documents").getExitCode())
                .isZero();
    }

    @AfterAll
    static void stopClamd() throws Exception {
        CLAMD.close();
    }

    private static RequestPostProcessor officer() {
        return user("u-officer", "DOCUMENT_OFFICER", "document.create", "document.read");
    }

    private static RequestPostProcessor sensitiveReader() {
        return user("u-sensitive", "DOCUMENT_SENSITIVE_READER", "document.read", "document.read.confidential");
    }

    private static RequestPostProcessor issuer() {
        return user("u-issuer", "CERTIFICATE_ISSUER", "certificate.issue");
    }

    private static RequestPostProcessor certificateAdmin() {
        return user("u-cadmin", "CERTIFICATE_ADMIN", "certificate.template.manage", "certificate.revoke",
                "certificate.read");
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("roles", List.of("CUSTOMER")));
    }

    private static RequestPostProcessor user(String sub, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        if (who != null) {
            builder.with(who);
        }
        builder.header("X-Channel", "BACKOFFICE").accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath);
        if (who != null) {
            req.withHeader("Authorization", "Bearer t");
        }
        if (key != null) {
            req.withHeader("Idempotency-Key", key);
        }
        if (qs > 0) {
            for (String pair : path.substring(qs + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv[1]);
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
        boolean idempotent = path.equals("/v1/documents") || path.startsWith("/v1/certificates");
        return call(post(path), Request.Method.POST, path, body, who, idempotent ? "it-" + UUID.randomUUID() : null);
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        return call(get(path), Request.Method.GET, path, null, who, null);
    }

    /** Binary upload; the response is JSON and checked against the contract's response schema. */
    private MvcResult upload(String id, byte[] content, RequestPostProcessor who) throws Exception {
        MvcResult r = mvc.perform(post("/v1/documents/" + id + "/content").with(who).header("X-Channel", "BACKOFFICE")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(content)
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        SimpleResponse response = SimpleResponse.Builder.status(r.getResponse().getStatus())
                .withContentType(r.getResponse().getContentType())
                .withBody(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).build();
        assertThat(contract.validateResponse("/v1/documents/" + id + "/content", Request.Method.POST, response)
                .getMessages()).isEmpty();
        return r;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return body(r).get("code").asString();
    }

    private String declared(String type, String ownerType, String ownerId, RequestPostProcessor who)
            throws Exception {
        MvcResult r = postAs("/v1/documents", Map.of("type", type, "ownerType", ownerType, "ownerId", ownerId,
                "title", "Test document"), who);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    @Test
    @Tag("CC-081")
    @DisplayName("upload → scanned clean → stored in MinIO; download returns the same bytes with their digest")
    void uploadAndDownload() throws Exception {
        String id = declared("SUPPORTING_DOCUMENT", "VEHICLE", "VIN-" + UUID.randomUUID().toString().substring(0, 8),
                officer());
        int scansBefore = CLAMD.scans.get();
        MvcResult up = upload(id, PDF, officer());
        assertThat(up.getResponse().getStatus()).as(up.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode doc = body(up);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF));
        assertThat(doc.get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(doc.get("mediaType").asString()).isEqualTo("application/pdf");
        assertThat(doc.get("sha256").asString()).isEqualTo(sha);
        assertThat(doc.get("classification").asString()).as("the type's default").isEqualTo("INTERNAL");
        assertThat(CLAMD.scans.get()).isEqualTo(scansBefore + 1);

        MvcResult download = mvc.perform(get("/v1/documents/" + id + "/content").with(officer())
                .header("X-Channel", "BACKOFFICE")).andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(PDF);
        assertThat(download.getResponse().getHeader("Repr-Digest")).isEqualTo("sha-256=:"
                + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha)) + ":");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        assertThat(code(upload(id, PDF, officer()))).isEqualTo("CONTENT_ALREADY_UPLOADED");
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action in "
                + "('DOCUMENT_CREATED', 'DOCUMENT_UPLOADED', 'DOCUMENT_CONTENT_VIEWED')").param("id", id)
                .query(Long.class).single()).isEqualTo(3);
        assertThatThrownBy(() -> db.sql("update document set sha256 = 'x' where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from document where id = cast(:id as uuid)").param("id", id)
                .update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("CC-081")
    @DisplayName("too large, wrong real type, empty and infected uploads are refused; infected is quarantined")
    void refusals() throws Exception {
        String owner = "VIN-R" + System.nanoTime() % 100000;
        String big = declared("SUPPORTING_DOCUMENT", "VEHICLE", owner, officer());
        byte[] tooLarge = new byte[5000];
        System.arraycopy(PDF, 0, tooLarge, 0, PDF.length);
        assertThat(code(upload(big, tooLarge, officer()))).isEqualTo("FILE_TOO_LARGE");

        String exe = declared("SUPPORTING_DOCUMENT", "VEHICLE", owner, officer());
        assertThat(code(upload(exe, "MZ\u0090\0 renamed.pdf".getBytes(StandardCharsets.ISO_8859_1), officer())))
                .isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(code(upload(exe, new byte[0], officer()))).isEqualTo("FILE_EMPTY");
        String png = declared("IDENTITY_DOCUMENT", "VEHICLE", owner, officer());
        assertThat(code(upload(png, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0},
                officer()))).as("PNG is not allowed for identity documents").isEqualTo("FILE_TYPE_NOT_ALLOWED");

        String infected = declared("SUPPORTING_DOCUMENT", "VEHICLE", owner, officer());
        byte[] eicar = ("%PDF-1.4\n" + ClamdStub.EICAR + "\n").getBytes(StandardCharsets.ISO_8859_1);
        MvcResult refused = upload(infected, eicar, officer());
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(refused)).isEqualTo("MALWARE_FOUND");
        JsonNode quarantined = body(getAs("/v1/documents/" + infected, officer()));
        assertThat(quarantined.get("status").asString()).isEqualTo("QUARANTINED");
        assertThat(mvc.perform(get("/v1/documents/" + infected + "/content").with(officer())
                .header("X-Channel", "BACKOFFICE")).andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.document.quarantined' "
                + "and subject = :id").param("id", infected).query(Long.class).single()).isEqualTo(1);

        assertThat(upload(declared("SUPPORTING_DOCUMENT", "VEHICLE", owner, officer()), PDF,
                user("u-other", "DOCUMENT_OFFICER", "document.create")).getResponse().getStatus())
                .as("only the declarer uploads").isEqualTo(403);
        CLAMD.mode = ClamdStub.Mode.SILENT;
        try {
            MvcResult noVerdict = upload(declared("SUPPORTING_DOCUMENT", "VEHICLE", owner, officer()), PDF,
                    officer());
            assertThat(noVerdict.getResponse().getStatus()).isEqualTo(503);
            assertThat(code(noVerdict)).isEqualTo("SCANNER_UNAVAILABLE");
        } finally {
            CLAMD.mode = ClamdStub.Mode.NORMAL;
        }
    }

    @Test
    @Tag("CC-085")
    @Tag("CC-001")
    @DisplayName("links to records; confidential reads need the extra permission; customers reach their own documents")
    void linksAndAccess() throws Exception {
        String customerId = "c-" + UUID.randomUUID().toString().substring(0, 8);
        String own = declared("IDENTITY_DOCUMENT", "CUSTOMER", customerId, customer(customerId));
        upload(own, PDF, customer(customerId));
        assertThat(postAs("/v1/documents", Map.of("type", "IDENTITY_DOCUMENT", "ownerType", "CUSTOMER",
                "ownerId", "someone-else"), customer(customerId)).getResponse().getStatus()).isEqualTo(403);

        String record = "ACC-" + customerId;
        MvcResult link = postAs("/v1/documents/" + own + "/links", Map.of("recordType", "ACCIDENT_CASE",
                "recordId", record), officer());
        assertThat(link.getResponse().getStatus()).as(link.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(getAs("/v1/documents/" + own + "/links", customer(customerId))).get("items")).hasSize(1);

        assertThat(getAs("/v1/documents/" + own, officer()).getResponse().getStatus())
                .as("confidential without the extra permission").isEqualTo(403);
        assertThat(body(getAs("/v1/documents/" + own, sensitiveReader())).get("classification").asString())
                .isEqualTo("CONFIDENTIAL");
        assertThat(body(getAs("/v1/documents?recordType=ACCIDENT_CASE&recordId=" + record, officer()))
                .get("items")).as("listing filters out confidential documents").isEmpty();
        assertThat(body(getAs("/v1/documents?recordType=ACCIDENT_CASE&recordId=" + record, sensitiveReader()))
                .get("items")).hasSize(1);
        assertThat(body(getAs("/v1/documents?ownerType=CUSTOMER&ownerId=" + customerId, customer(customerId)))
                .get("items")).hasSize(1);
        assertThat(getAs("/v1/documents?ownerType=CUSTOMER&ownerId=" + customerId, customer("c-stranger"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs("/v1/documents/" + own, customer("c-stranger")).getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs("/v1/documents/" + UUID.randomUUID(), officer()).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @Tag("CC-082")
    @Tag("CC-083")
    @DisplayName("certificates: issue with QR, public verification without a token, wrong code 404, revoke")
    void certificates() throws Exception {
        String code = "NOC_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
        MvcResult template = postAs("/v1/certificate-templates", Map.of("code", code, "titleNl", "شهادة",
                "titleEn", "No-objection certificate", "bodyNl", "نشهد أن {{holderNameNl}}",
                "bodyEn", "We certify that {{holderNameEn}} owns {{plate}}", "validityDays", 30,
                "reason", "first version"), certificateAdmin());
        assertThat(template.getResponse().getStatus()).as(template.getResponse().getContentAsString()).isEqualTo(201);

        MvcResult issued = postAs("/v1/certificates", Map.of("templateCode", code, "holderNameNl", "محمد أحمد",
                "holderNameEn", "Mohammed Ahmed", "subjectType", "VEHICLE", "subjectId", "VIN-1",
                "data", Map.of("plate", "A 1"), "reference", "REF-1"), issuer());
        assertThat(issued.getResponse().getStatus()).as(issued.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode c = body(issued);
        String number = c.get("number").asString();
        String verification = c.get("verificationCode").asString();
        assertThat(number).matches("CRT-\\d{4}-\\d{8}-\\d");

        MvcResult html = mvc.perform(get("/v1/documents/" + c.get("documentId").asString() + "/content")
                .with(officer()).header("X-Channel", "BACKOFFICE")).andReturn();
        assertThat(html.getResponse().getStatus()).isEqualTo(200);
        String page = html.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<svg", "We certify that Mohammed Ahmed owns A 1", number,
                number + "?code=" + verification);

        MvcResult publicCheck = call(get("/v1/certificate-verifications/" + number + "?code=" + verification),
                Request.Method.GET, "/v1/certificate-verifications/" + number + "?code=" + verification, null, null,
                null);
        assertThat(publicCheck.getResponse().getStatus()).as("no token needed").isEqualTo(200);
        assertThat(body(publicCheck).get("validity").asString()).isEqualTo("VALID");
        assertThat(body(publicCheck).get("holder").asString()).isEqualTo("M. A.");
        assertThat(body(publicCheck).has("verificationCode")).isFalse();
        MvcResult wrong = call(get("/v1/certificate-verifications/" + number + "?code=WRONGCODE2"),
                Request.Method.GET, "/v1/certificate-verifications/" + number + "?code=WRONGCODE2", null, null, null);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/v1/certificates/" + number)).andReturn().getResponse().getStatus())
                .as("staff reads still need a token").isEqualTo(401);

        assertThat(postAs("/v1/certificates/" + number + "/revocations", Map.of("reason", "issued in error"),
                issuer()).getResponse().getStatus()).isEqualTo(403);
        assertThat(body(postAs("/v1/certificates/" + number + "/revocations", Map.of("reason", "issued in error"),
                certificateAdmin())).get("status").asString()).isEqualTo("REVOKED");
        assertThat(body(call(get("/v1/certificate-verifications/" + number + "?code=" + verification),
                Request.Method.GET, "/v1/certificate-verifications/" + number + "?code=" + verification, null, null,
                null)).get("validity").asString()).isEqualTo("REVOKED");
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :n and action = 'CERTIFICATE_VERIFIED'")
                .param("n", number).query(Long.class).single()).isEqualTo(2);
    }
}
