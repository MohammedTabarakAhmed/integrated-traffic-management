package gov.itms.notification.application;

import gov.itms.notification.domain.DeliveryChannel;

/**
 * The SMS, email and push gateways (CC-070). An implementation returns the gateway's message id, used to match the
 * delivery receipt (CC-074), or throws {@link GatewayException} when the message was not accepted.
 */
public interface ChannelGateway {

    String send(DeliveryChannel channel, String destination, String subject, String body, String notificationId);

    class GatewayException extends RuntimeException {
        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
