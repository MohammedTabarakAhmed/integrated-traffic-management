package gov.itms.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.AuthorisationResolver;
import gov.itms.platform.security.Scopes;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls the service through MockMvc and checks every request and response against the service's OpenAPI document
 * (INT-003). Changing POSTs get a fresh Idempotency-Key unless the test passes one.
 */
final class ContractClient {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final OpenApiInteractionValidator contract;

    ContractClient(MockMvc mvc, ObjectMapper json, String specification) {
        this.mvc = mvc;
        this.json = json;
        this.contract = OpenApiInteractionValidator.createFor(specification).build();
    }

    /** A caller with national scope. */
    static RequestPostProcessor user(String sub, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub).claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    /** A caller whose grants reach only {@code regions} (read by {@link RegionalResolver}). */
    static RequestPostProcessor regional(String sub, String role, List<String> regions, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub).claim("roles", List.of(role))
                .claim("regions", regions).claim("permissions", Arrays.asList(permissions)));
    }

    /**
     * Test stand-in for iam-service: permissions from the token, scoped to the {@code regions} claim when present and
     * national otherwise.
     */
    static final class RegionalResolver implements AuthorisationResolver {
        @Override
        public Authorisation resolve(Jwt jwt) {
            Set<String> permissions = new HashSet<>(
                    Objects.requireNonNullElse(jwt.getClaimAsStringList("permissions"), List.of()));
            List<String> regions = jwt.getClaimAsStringList("regions");
            Scopes scopes = regions == null ? Scopes.NATIONAL
                    : new Scopes(Set.copyOf(regions), Set.of(Scopes.ALL), Set.of(Scopes.ALL));
            String role = Objects.requireNonNullElse(jwt.getClaimAsStringList("roles"), List.of("TEST")).getFirst();
            return new Authorisation(jwt.getSubject(), permissions.isEmpty() ? List.of()
                    : List.of(new Authorisation.Grant(role, permissions, scopes, jwt.getExpiresAt())), jwt.getIssuedAt());
        }
    }

    MvcResult post(String path, Object body, RequestPostProcessor who) throws Exception {
        return post(path, body, who, "it-" + UUID.randomUUID());
    }

    MvcResult post(String path, Object body, RequestPostProcessor who, String key) throws Exception {
        return call(MockMvcRequestBuilders.post(path), Request.Method.POST, path, body, who, key);
    }

    MvcResult put(String path, Object body, RequestPostProcessor who) throws Exception {
        return call(MockMvcRequestBuilders.put(path), Request.Method.PUT, path, body, who, "it-" + UUID.randomUUID());
    }

    MvcResult get(String path, RequestPostProcessor who) throws Exception {
        return call(MockMvcRequestBuilders.get(path), Request.Method.GET, path, null, who, null);
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Asserts the status and returns the body (null when empty). */
    JsonNode expect(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? null : json.readTree(text);
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", "COUNTER")
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
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath).withHeader("Authorization", "Bearer t");
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
        int status = result.getResponse().getStatus();
        SimpleResponse response = SimpleResponse.Builder.status(status)
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).build();
        ValidationReport report = status < 400 ? contract.validate(req.build(), response)
                : contract.validateResponse(requestPath, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, requestPath, status).isEmpty();
        return result;
    }
}
