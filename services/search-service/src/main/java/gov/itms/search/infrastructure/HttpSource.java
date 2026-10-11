package gov.itms.search.infrastructure;

import gov.itms.search.application.Ports;
import gov.itms.search.domain.Search;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A source answered over HTTP by its owning service, with the caller's token and channel (the owning service applies
 * the caller's permissions and data scope and audits the read). The query is passed as a URI variable (encoded) or as
 * a JSON string in the configured body, never spliced into a URL or JSON by hand. Redirects are not followed.
 */
final class HttpSource implements Ports.Source {

    private final SearchProperties.SourceSettings settings;
    private final Pattern accepted;
    private final RestClient client;
    private final ObjectMapper json;

    HttpSource(SearchProperties.SourceSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.accepted = Pattern.compile(settings.pattern());
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(settings.timeout());
        this.client = RestClient.builder().baseUrl(settings.baseUrl().toString()).requestFactory(factory).build();
    }

    @Override
    public String name() {
        return settings.name();
    }

    @Override
    public Search.EntityType type() {
        return settings.type();
    }

    @Override
    public boolean accepts(String query) {
        return accepted.matcher(query).matches();
    }

    @Override
    public List<Search.Hit> find(String query, Ports.Caller caller) {
        String answer;
        try {
            RestClient.RequestHeadersSpec<?> request;
            if (settings.method().equals("POST")) {
                request = client.post().uri(settings.path(), Map.of("q", query))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(settings.body().replace("{q}", json.writeValueAsString(query)));
            } else {
                request = client.get().uri(settings.path(), Map.of("q", query));
            }
            answer = request.header("Authorization", "Bearer " + caller.bearerToken())
                    .header("X-Channel", caller.channel()).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 400 || status == 422) {
                return List.of(); // no such record, or not an identifier this service recognises
            }
            if (status == 401 || status == 403) {
                throw new Ports.Forbidden(settings.name() + " refused the caller");
            }
            throw new Ports.Unavailable(settings.name() + " answered " + status, e);
        } catch (RestClientException e) {
            throw new Ports.Unavailable(settings.name() + " did not answer", e);
        }
        return hits(answer);
    }

    List<Search.Hit> hits(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = json.readTree(answer);
        } catch (RuntimeException e) {
            throw new Ports.Unavailable(settings.name() + " answered something that is not JSON", e);
        }
        List<JsonNode> records = new ArrayList<>();
        if (settings.itemsField() == null) {
            records.add(root);
        } else if (root.get(settings.itemsField()) != null && root.get(settings.itemsField()).isArray()) {
            root.get(settings.itemsField()).forEach(records::add);
        } else {
            throw new Ports.Unavailable(settings.name() + " answered without " + settings.itemsField(), null);
        }
        List<Search.Hit> hits = new ArrayList<>();
        for (JsonNode r : records) {
            String id = text(r, settings.idField());
            if (id == null) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String f : settings.fields()) {
                String v = text(r, f);
                if (v != null) {
                    fields.put(f, v);
                }
            }
            String reference = settings.referenceField() == null ? id : text(r, settings.referenceField());
            String region = settings.regionField() == null ? null : text(r, settings.regionField());
            hits.add(new Search.Hit(settings.type(), id, reference == null ? id : reference, region, fields));
        }
        return hits;
    }

    /** A scalar at a dotted path ({@code identity.name.givenEn}); null when absent, null or not a scalar. */
    private static String text(JsonNode node, String path) {
        JsonNode n = node;
        for (String part : path.split("\\.")) {
            n = n == null ? null : n.get(part);
        }
        return n == null || n.isNull() || !n.isValueNode() ? null : n.asString();
    }
}
