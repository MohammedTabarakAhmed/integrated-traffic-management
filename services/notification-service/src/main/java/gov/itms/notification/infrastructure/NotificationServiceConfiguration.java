package gov.itms.notification.infrastructure;

import gov.itms.notification.application.ChannelGateway;
import gov.itms.notification.application.CustomerNotifications;
import gov.itms.notification.application.NotificationService;
import gov.itms.notification.domain.DeliveryChannel;
import gov.itms.notification.domain.Subscriptions;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.OwnTrafficFile;
import gov.itms.platform.client.ServiceCallException;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import gov.itms.platform.events.ProcessedEvents;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Wires notifications: repository, gateways, the dispatcher and reminder jobs, and the event consumer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationServiceConfiguration {

    @Bean
    ChannelGateway channelGateway(RestClient.Builder builder, NotificationProperties properties) {
        Map<DeliveryChannel, RestClient> clients = new EnumMap<>(DeliveryChannel.class);
        properties.gateways().forEach((channel, url) -> {
            // HTTP/1.1: over plain HTTP the JDK client's default h2c upgrade on a POST with a streamed body gets no
            // usable answer from some gateways (the local WireMock mock never completes it); TLS gateways are unaffected.
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(properties.gatewayTimeout()).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(properties.gatewayTimeout());
            clients.put(DeliveryChannel.valueOf(channel),
                    builder.clone().baseUrl(url.toString()).requestFactory(factory).build());
        });
        return new HttpChannelGateway(clients);
    }

    /** The signed-in customer's own messages and preferences (ADR-067 d); the file is found with their own token. */
    @Bean
    CustomerNotifications customerNotifications(JdbcClient jdbc, ObjectMapper json, AuditRecorder audit, Clock clock,
                                                Environment environment,
                                                ObjectProvider<ObservationRegistry> observations) {
        OwnTrafficFile files = OwnTrafficFile.create(
                environment.getRequiredProperty("itms.clients.traffic-files.base-url"),
                environment.getRequiredProperty("itms.clients.traffic-files.timeout", Duration.class),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        CustomerNotifications.OwnFile own = () -> {
            try {
                return files.fileNumber();
            } catch (ServiceCallException e) {
                LoggerFactory.getLogger(CustomerNotifications.class).warn("Own traffic file lookup failed: {}",
                        e.getMessage());
                throw new CustomerNotifications.TrafficFilesUnavailableException(e);
            }
        };
        return new CustomerNotifications(new JdbcNotificationRepository(jdbc, json), own, audit, clock);
    }

    @Bean
    NotificationService notificationService(JdbcClient jdbc, ObjectMapper json, ChannelGateway gateway,
                                            AuditRecorder audit, ProcessedEvents processed,
                                            NotificationProperties properties, Clock clock) {
        return new NotificationService(new JdbcNotificationRepository(jdbc, json), gateway, audit, processed,
                new NotificationService.Settings(properties.maxAttempts(), properties.retryDelay(),
                        properties.reminderOffsetDays(), expiryEvents(properties)), clock);
    }

    private static List<NotificationService.ExpiryEvent> expiryEvents(NotificationProperties properties) {
        return properties.expiryEvents().stream()
                .map(e -> new NotificationService.ExpiryEvent(e.eventType(),
                        e.subjectType() == null ? null : Subscriptions.SubjectType.valueOf(e.subjectType()),
                        e.subjectField(), e.recipientField(), e.expiryField()))
                .toList();
    }

    /** Sends queued notifications and the daily expiry reminders (CC-072, CC-074); safe on every instance. */
    public static class NotificationJobs {

        private static final Logger LOG = LoggerFactory.getLogger(NotificationJobs.class);
        private final NotificationService notifications;
        private final NotificationProperties properties;

        public NotificationJobs(NotificationService notifications, NotificationProperties properties) {
            this.notifications = notifications;
            this.properties = properties;
        }

        @Scheduled(initialDelayString = "${itms.notifications.dispatch-interval:5s}",
                fixedDelayString = "${itms.notifications.dispatch-interval:5s}")
        public void dispatch() {
            try {
                for (int batch = 0; batch < 20; batch++) {
                    if (notifications.dispatch(properties.batchSize()) < properties.batchSize()) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                LOG.error("Notification dispatch failed; it is retried on the next run", e);
            }
        }

        @Scheduled(cron = "${itms.notifications.reminder-cron:0 0 6 * * *}",
                zone = "${itms.notifications.reminder-zone:UTC}")
        public void reminders() {
            try {
                int total = 0;
                for (int batch = 0; batch < 1000; batch++) {
                    int sent = notifications.sendDueReminders(properties.batchSize());
                    total += sent;
                    if (sent == 0) {
                        break;
                    }
                }
                LOG.info("Expiry reminders: {} notifications queued", total);
            } catch (RuntimeException e) {
                LOG.error("Expiry reminder run failed; it runs again on the next schedule", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.notifications.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class JobConfiguration {

        @Bean
        NotificationJobs notificationJobs(NotificationService notifications, NotificationProperties properties) {
            return new NotificationJobs(notifications, properties);
        }
    }

    /** Event-driven notifications (CC-071): off unless the broker is available. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.notifications.consumer.enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        DomainEventListener domainEventListener(NotificationService notifications, ObjectMapper json) {
            return new DomainEventListener(notifications, json);
        }
    }
}
