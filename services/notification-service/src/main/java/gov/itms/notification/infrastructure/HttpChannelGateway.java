package gov.itms.notification.infrastructure;

import gov.itms.notification.application.ChannelGateway;
import gov.itms.notification.domain.DeliveryChannel;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ICD-05 (draft): one messaging gateway per channel, {@code POST /v1/messages} with the destination, subject, body and
 * our notification id as the client reference; the gateway answers with its message id, later quoted in the delivery
 * receipt (CC-074). Message bodies and destinations are never logged (SEC-014).
 */
class HttpChannelGateway implements ChannelGateway {

    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelGateway.class);

    private final Map<DeliveryChannel, RestClient> clients;

    HttpChannelGateway(Map<DeliveryChannel, RestClient> clients) {
        this.clients = Map.copyOf(clients);
    }

    record MessageRequest(String channel, String to, String subject, String body, String clientReference) {
    }

    record MessageAccepted(String messageId) {
    }

    @Override
    public String send(DeliveryChannel channel, String destination, String subject, String body,
                       String notificationId) {
        RestClient client = clients.get(channel);
        if (client == null) {
            throw new GatewayException("no gateway configured for " + channel, null);
        }
        try {
            MessageAccepted accepted = client.post().uri("/v1/messages")
                    .body(new MessageRequest(channel.name(), destination, subject, body, notificationId))
                    .retrieve().body(MessageAccepted.class);
            if (accepted == null || accepted.messageId() == null || accepted.messageId().isBlank()
                    || accepted.messageId().length() > 200) {
                throw new GatewayException(channel + " gateway gave no message id", null);
            }
            return accepted.messageId();
        } catch (RestClientException e) {
            LOG.warn("{} gateway did not accept notification {}: {}", channel, notificationId,
                    e.getClass().getSimpleName());
            throw new GatewayException(channel + " gateway: " + e.getClass().getSimpleName(), e);
        }
    }
}
