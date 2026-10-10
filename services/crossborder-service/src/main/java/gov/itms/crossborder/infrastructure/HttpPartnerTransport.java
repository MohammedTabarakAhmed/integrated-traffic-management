package gov.itms.crossborder.infrastructure;

import gov.itms.crossborder.application.CrossborderService.Partner;
import gov.itms.crossborder.application.Ports;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Posts exchange envelopes to a partner state's endpoint (ICD: {@code POST {endpoint}/messages} for real time,
 * {@code POST {endpoint}/batches} for the daily batch). The message id is the Idempotency-Key, so a retried delivery is
 * recognised by the partner. 2xx and 409 (already received) are acceptance; other 4xx except 408 and 429 are permanent
 * refusals; everything else is retried. Production endpoints use mutual TLS configured on the client (SEC, ICD).
 */
class HttpPartnerTransport implements Ports.PartnerTransport {

    private static final Logger LOG = LoggerFactory.getLogger(HttpPartnerTransport.class);

    record Reply(String reference) {
    }

    private final RestClient client;

    HttpPartnerTransport(RestClient client) {
        this.client = client;
    }

    @Override
    public Outcome send(Partner partner, Map<String, Object> envelope) {
        return post(partner, partner.endpointUrl() + "/messages", String.valueOf(envelope.get("messageId")), envelope);
    }

    @Override
    public Outcome sendBatch(Partner partner, List<Map<String, Object>> envelopes) {
        String key = "batch-" + Integer.toHexString(envelopes.stream().map(e -> String.valueOf(e.get("messageId")))
                .sorted().toList().hashCode());
        return post(partner, partner.endpointUrl() + "/batches", key, Map.of("items", envelopes));
    }

    private Outcome post(Partner partner, String url, String key, Object body) {
        try {
            Reply reply = client.post().uri(url).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(Reply.class);
            return new Outcome(true, false, "accepted", reply == null ? null : reply.reference());
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 409) {
                return new Outcome(true, false, "already received", null);
            }
            boolean permanent = status >= 400 && status < 500 && status != 408 && status != 429;
            LOG.warn("Partner {} answered HTTP {}", partner.code(), status);
            return new Outcome(false, permanent, "HTTP " + status, null);
        } catch (RestClientException e) {
            LOG.warn("Partner {} unreachable: {}", partner.code(), e.getClass().getSimpleName());
            return new Outcome(false, false, "unreachable: " + e.getClass().getSimpleName(), null);
        }
    }
}
