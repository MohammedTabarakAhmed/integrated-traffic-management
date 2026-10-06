package gov.itms.notification.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notification settings, {@code itms.notifications.*} (NFR-042): nothing here is hard-coded policy.
 *
 * @param gateways           base address of the gateway per channel (SMS, EMAIL, PUSH); a channel without one fails
 *                           its deliveries
 * @param gatewayTimeout     connect and read timeout, 500 ms to 30 s
 * @param maxAttempts        send attempts before a notification is FAILED, 1-20
 * @param retryDelay         delay before the second attempt, doubled each time, 1 s to 1 h
 * @param reminderOffsetDays days before expiry when reminders go out (CC-072; BRD default 30, 7, 1), 1-10 distinct
 *                           values of 0-365
 * @param dispatchInterval   pause between dispatcher runs, 1 s to 10 min
 * @param batchSize          notifications per dispatcher batch, 1-500
 */
@ConfigurationProperties("itms.notifications")
public record NotificationProperties(
        Map<String, URI> gateways,
        @DefaultValue("5s") Duration gatewayTimeout,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration retryDelay,
        @DefaultValue({"30", "7", "1"}) List<Integer> reminderOffsetDays,
        @DefaultValue("5s") Duration dispatchInterval,
        @DefaultValue("50") int batchSize) {

    public NotificationProperties {
        java.util.Map<String, URI> upper = new java.util.HashMap<>();
        if (gateways != null) {
            gateways.forEach((k, v) -> upper.put(k.toUpperCase(java.util.Locale.ROOT), v));
        }
        gateways = Map.copyOf(upper);
        for (String channel : gateways.keySet()) {
            if (!List.of("SMS", "EMAIL", "PUSH").contains(channel)) {
                throw new IllegalArgumentException("itms.notifications.gateways keys are SMS, EMAIL, PUSH (CC-070)");
            }
        }
        if (gatewayTimeout.compareTo(Duration.ofMillis(500)) < 0 || gatewayTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("itms.notifications.gateway-timeout must be 500 ms to 30 s (NFR-042)");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("itms.notifications.max-attempts must be 1-20 (NFR-042)");
        }
        if (retryDelay.compareTo(Duration.ofSeconds(1)) < 0 || retryDelay.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("itms.notifications.retry-delay must be 1 s to 1 h (NFR-042)");
        }
        reminderOffsetDays = List.copyOf(reminderOffsetDays);
        if (reminderOffsetDays.isEmpty() || reminderOffsetDays.size() > 10
                || new HashSet<>(reminderOffsetDays).size() != reminderOffsetDays.size()
                || reminderOffsetDays.stream().anyMatch(d -> d == null || d < 0 || d > 365)) {
            throw new IllegalArgumentException("itms.notifications.reminder-offset-days must be 1-10 distinct days of "
                    + "0-365 (CC-072, NFR-042)");
        }
        if (dispatchInterval.compareTo(Duration.ofSeconds(1)) < 0
                || dispatchInterval.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("itms.notifications.dispatch-interval must be 1 s to 10 min (NFR-042)");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("itms.notifications.batch-size must be 1-500 (NFR-042)");
        }
    }
}
