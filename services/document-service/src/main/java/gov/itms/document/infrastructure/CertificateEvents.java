package gov.itms.document.infrastructure;

import gov.itms.document.application.CertificateHandOff;
import gov.itms.document.application.Ports;
import gov.itms.platform.client.ServiceCallException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The certificate hand-off's adapters (CC-082; Q-075 f, Q-078 a): the event listener and the holder's names. */
final class CertificateEvents {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateEvents.class);

    private CertificateEvents() {
    }

    /**
     * Consumes the owning services' domain events (CloudEvents JSON, ADR-002) and hands certificate events to
     * {@link CertificateHandOff}. A malformed event is logged and skipped; an unavailable partner is thrown so the
     * event is redelivered.
     */
    static class Listener {

        private final CertificateHandOff handOff;
        private final ObjectMapper json;

        Listener(CertificateHandOff handOff, ObjectMapper json) {
            this.handOff = handOff;
            this.json = json;
        }

        @KafkaListener(topics = "#{'${itms.documents.consumer.topics}'.split(',')}",
                groupId = "${itms.documents.consumer.group-id:document-service}")
        void onMessage(String message) {
            JsonNode event;
            try {
                event = json.readTree(message);
            } catch (RuntimeException e) {
                LOG.warn("Skipping an event that is not JSON");
                return;
            }
            if (!event.hasNonNull("id") || !event.hasNonNull("type")) {
                LOG.warn("Skipping an event without id or type");
                return;
            }
            Map<String, String> data = new LinkedHashMap<>();
            JsonNode d = event.get("data");
            if (d != null && d.isObject()) {
                d.properties().forEach(e -> {
                    JsonNode v = e.getValue();
                    if (v.isValueNode() && !v.isNull()) {
                        data.put(e.getKey(), v.asString());
                    }
                });
            }
            handOff.onEvent(event.get("id").asString(), event.get("type").asString(), data);
        }
    }

    /**
     * traffic-file-service {@code GET /v1/traffic-files/{fileNumber}} with this service's own account
     * ({@code trafficfile.read}): an individual's given and family name, or a company's name, in both languages.
     */
    static class RemoteHolderNames implements Ports.HolderNames {

        private final RestClient client;

        RemoteHolderNames(RestClient client) {
            this.client = client;
        }

        record NameBody(String givenNl, String familyNl, String givenEn, String familyEn) {
        }

        record IdentityBody(NameBody name) {
        }

        record CompanyBody(String nameNl, String nameEn) {
        }

        record FileBody(String fileNumber, IdentityBody identity, CompanyBody company) {
        }

        @Override
        public Optional<Names> of(String trafficFileNumber) {
            FileBody f;
            try {
                f = client.get().uri("/v1/traffic-files/{n}", trafficFileNumber).header("X-Channel", "BACKOFFICE")
                        .accept(MediaType.APPLICATION_JSON).retrieve().body(FileBody.class);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 404) {
                    return Optional.empty();
                }
                LOG.warn("Holder name lookup failed: HTTP {}", e.getStatusCode().value());
                throw new IllegalStateException("traffic-file-service did not answer", e);
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Holder name lookup failed: {}", e.getClass().getSimpleName());
                throw new IllegalStateException("traffic-file-service did not answer", e);
            }
            if (f == null) {
                return Optional.empty();
            }
            String nl;
            String en;
            if (f.company() != null) {
                nl = f.company().nameNl();
                en = f.company().nameEn();
            } else if (f.identity() != null && f.identity().name() != null) {
                NameBody n = f.identity().name();
                nl = join(n.givenNl(), n.familyNl());
                en = join(n.givenEn(), n.familyEn());
            } else {
                return Optional.empty();
            }
            // One language may be missing (CC-012 needs one complete pair): print the other in its place.
            nl = nl == null ? en : nl;
            en = en == null ? nl : en;
            return nl == null ? Optional.empty() : Optional.of(new Names(nl, en));
        }

        private static String join(String given, String family) {
            if (given == null && family == null) {
                return null;
            }
            return ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
        }
    }
}
