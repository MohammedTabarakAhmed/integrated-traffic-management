package gov.itms.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-074")
class NotificationTest {

    private static final Instant NOW = TemplateTest.NOW;
    private static final Template TEMPLATE = TemplateTest.template("VL_RENEWED", "VEHICLE", false);
    private static final Recipient RECIPIENT = new Recipient("c-1", Language.EN, "+97150123456", null, null,
            Map.of());

    private static Notification queued(DeliveryChannel channel) {
        return Notification.queue(UUID.randomUUID(), RECIPIENT, TEMPLATE, channel,
                TEMPLATE.render(channel, Language.EN, Map.of("plate", "A 1")), "REF-1", "evt-1", NOW);
    }

    @Test
    @DisplayName("CC-074: queued → sent → delivered by receipt; a repeated receipt is refused")
    void sendAndDeliver() {
        Notification n = queued(DeliveryChannel.SMS);
        assertThat(n.status()).isEqualTo(Notification.Status.QUEUED);
        assertThat(n.nextAttemptAt()).isEqualTo(NOW);
        assertThat(n.destination()).isEqualTo("+97150123456");
        assertThat(n.templateVersion()).isEqualTo(1);
        n.sent("gw-1", NOW.plusSeconds(1));
        assertThat(n.status()).isEqualTo(Notification.Status.SENT);
        assertThat(n.attempts()).isEqualTo(1);
        assertThat(n.providerMessageId()).isEqualTo("gw-1");
        n.receipt(true, null, NOW.plusSeconds(5));
        assertThat(n.status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(n.deliveredAt()).isEqualTo(NOW.plusSeconds(5));
        assertThatThrownBy(() -> n.receipt(false, "late", NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("NOTIFICATION_NOT_SENT");
        assertThatThrownBy(() -> n.sent("gw-2", NOW)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("NOTIFICATION_STATE");
    }

    @Test
    @DisplayName("CC-074: a refused send is retried with a doubling delay, then FAILED after the last attempt")
    void retryThenFail() {
        Notification n = queued(DeliveryChannel.SMS);
        Duration delay = Duration.ofSeconds(30);
        n.sendFailed("timeout", 3, delay, NOW);
        assertThat(n.status()).isEqualTo(Notification.Status.QUEUED);
        assertThat(n.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        n.sendFailed("timeout", 3, delay, NOW);
        assertThat(n.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        n.sendFailed(null, 3, delay, NOW);
        assertThat(n.status()).isEqualTo(Notification.Status.FAILED);
        assertThat(n.attempts()).isEqualTo(3);
        assertThat(n.nextAttemptAt()).isNull();
        assertThat(n.lastError()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("CC-074: a negative receipt fails the delivery with the gateway's error")
    void negativeReceipt() {
        Notification n = queued(DeliveryChannel.SMS);
        n.sent("gw-1", NOW);
        n.receipt(false, "handset unreachable", NOW);
        assertThat(n.status()).isEqualTo(Notification.Status.FAILED);
        assertThat(n.lastError()).isEqualTo("handset unreachable");
    }

    @Test
    @DisplayName("CC-074: an inbox message is delivered at once and marked read once")
    void inbox() {
        Notification n = queued(DeliveryChannel.INBOX);
        assertThat(n.status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(n.destination()).isEqualTo("c-1");
        n.markRead(NOW.plusSeconds(10));
        n.markRead(NOW.plusSeconds(20));
        assertThat(n.readAt()).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> queued(DeliveryChannel.SMS).markRead(NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
