package gov.itms.payment.infrastructure;

import gov.itms.payment.application.CustomerPayments;
import gov.itms.payment.application.PaymentService;
import gov.itms.payment.application.FinanceOperations;
import gov.itms.payment.application.Ports;
import gov.itms.payment.domain.Payment;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.OwnTrafficFile;
import gov.itms.platform.client.ServiceCallException;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActorProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Wires payments, the ICD-03 gateway adapters and the pending-resolution job (FN-005). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentServiceConfiguration.PaymentProperties.class)
public class PaymentServiceConfiguration {

    /**
     * {@code itms.payments.*} (NFR-042). The callback keys arrive as the secret file
     * {@code itms.payments.gateway.callback-keys} (comma-separated during a rotation; SEC-004).
     *
     * @param acceptedMethods  methods accepted (FN-001; cheques are configurable)
     * @param inquiryAfter     a PENDING payment is asked about after this, 1-30 min (ICD-03: 5 min)
     * @param resolutionWindow unresolved PENDING payments go to EXCEPTION after this, 5-120 min (FN-005: 30 min)
     * @param businessZone     time zone of the business day for receipts (FN-011)
     */
    @ConfigurationProperties("itms.payments")
    public record PaymentProperties(List<Payment.Method> acceptedMethods,
                                    @DefaultValue("5m") Duration inquiryAfter,
                                    @DefaultValue("30m") Duration resolutionWindow,
                                    @DefaultValue("UTC") String businessZone,
                                    @DefaultValue("1m") Duration jobInterval,
                                    @DefaultValue Gateway gateway) {

        public record Gateway(URI baseUrl, @DefaultValue("5s") Duration timeout, String callbackBaseUrl,
                              String callbackKeys) {
        }

        public PaymentProperties {
            acceptedMethods = acceptedMethods == null || acceptedMethods.isEmpty()
                    ? List.of(Payment.Method.GATEWAY_CARD, Payment.Method.E_PAYMENT, Payment.Method.CASH,
                    Payment.Method.CARD_TERMINAL) : List.copyOf(acceptedMethods);
            if (inquiryAfter.compareTo(Duration.ofMinutes(1)) < 0 || inquiryAfter.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalArgumentException("itms.payments.inquiry-after must be 1-30 min (FN-005)");
            }
            if (resolutionWindow.compareTo(Duration.ofMinutes(5)) < 0
                    || resolutionWindow.compareTo(Duration.ofMinutes(120)) > 0
                    || resolutionWindow.compareTo(inquiryAfter) <= 0) {
                throw new IllegalArgumentException("itms.payments.resolution-window must be 5-120 min and after "
                        + "inquiry-after (FN-005)");
            }
            if (jobInterval.compareTo(Duration.ofSeconds(10)) < 0 || jobInterval.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("itms.payments.job-interval must be 10 s to 10 min");
            }
            ZoneId.of(businessZone);
            if (gateway == null || gateway.baseUrl() == null || gateway.callbackBaseUrl() == null) {
                throw new IllegalArgumentException("itms.payments.gateway.base-url and callback-base-url are required");
            }
            if (gateway.callbackKeys() == null || gateway.callbackKeys().isBlank()) {
                throw new IllegalArgumentException("itms.payments.gateway.callback-keys must be provided as a secret "
                        + "file (SEC-004)");
            }
        }
    }

    @Bean
    Ports.PaymentGateway paymentGateway(RestClient.Builder builder, PaymentProperties properties) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.gateway().timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.gateway().timeout());
        RestClient client = builder.clone().baseUrl(properties.gateway().baseUrl().toString()).requestFactory(factory)
                .build();
        return new GatewayAdapters.HttpPaymentGateway(client, properties.gateway().callbackBaseUrl());
    }

    @Bean
    PaymentService paymentService(JdbcClient jdbc, ObjectMapper json, Ports.PaymentGateway gateway,
                                  PaymentProperties properties, AuditRecorder audit, DomainEventRecorder events,
                                  Clock clock) {
        List<String> keys = Arrays.stream(properties.gateway().callbackKeys().split(",")).map(String::strip)
                .filter(k -> !k.isEmpty()).toList();
        return new PaymentService(new JdbcPaymentRepository(jdbc, json),
                gateway, new GatewayAdapters.HmacCallbackVerifier(keys), audit, events,
                new PaymentService.Settings(Set.copyOf(properties.acceptedMethods()), properties.inquiryAfter(),
                        properties.resolutionWindow(), ZoneId.of(properties.businessZone())), clock);
    }

    /** The signed-in customer's own items (ADR-067 d); the file is looked up with the customer's own token. */
    @Bean
    CustomerPayments customerPayments(JdbcClient jdbc, ObjectMapper json, PaymentService payments, AuditRecorder audit,
                                      Environment environment, ObjectProvider<ObservationRegistry> observations) {
        OwnTrafficFile files = OwnTrafficFile.create(
                environment.getRequiredProperty("itms.clients.traffic-files.base-url"),
                environment.getRequiredProperty("itms.clients.traffic-files.timeout", Duration.class),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        Ports.OwnFile own = () -> {
            try {
                return files.fileNumber();
            } catch (ServiceCallException e) {
                LoggerFactory.getLogger(CustomerPayments.class).warn("Own traffic file lookup failed: {}",
                        e.getMessage());
                throw new Ports.TrafficFilesUnavailableException("your traffic file cannot be found now", e);
            }
        };
        return new CustomerPayments(new JdbcPaymentRepository(jdbc, json), own, payments, audit);
    }

    @Bean
    FinanceOperations financeOperations(JdbcClient jdbc, CurrentActorProvider actors, AuditRecorder audit,
                                        DomainEventRecorder events, Clock clock, Ports.PaymentGateway gateway) {
        return new FinanceOperations(new JdbcFinanceRepository(jdbc), actors, audit, events, clock, gateway);
    }

    /** Resolves PENDING payments (FN-005); safe on every instance (rows locked, others skip them). */
    public static class PendingJob {

        private static final Logger LOG = LoggerFactory.getLogger(PendingJob.class);
        private final PaymentService payments;

        public PendingJob(PaymentService payments) {
            this.payments = payments;
        }

        @Scheduled(initialDelayString = "${itms.payments.job-interval:1m}",
                fixedDelayString = "${itms.payments.job-interval:1m}")
        public void run() {
            try {
                int n = payments.resolvePending(100);
                if (n > 0) {
                    LOG.info("Pending payments: {} checked with the gateway", n);
                }
            } catch (RuntimeException e) {
                LOG.error("Pending payment run failed; it is retried on the next run", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.payments.pending-job.enabled", havingValue = "true", matchIfMissing = true)
    static class JobConfiguration {

        @Bean
        PendingJob pendingJob(PaymentService payments) {
            return new PendingJob(payments);
        }
    }
}
