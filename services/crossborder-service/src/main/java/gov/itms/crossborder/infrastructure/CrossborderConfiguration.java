package gov.itms.crossborder.infrastructure;

import gov.itms.crossborder.application.CrossborderService;
import gov.itms.crossborder.application.ExchangeDelivery;
import gov.itms.crossborder.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CrossborderConfiguration.CrossborderProperties.class)
public class CrossborderConfiguration {

    /**
     * Exchange settings (XB-001..XB-008): this state's code in envelopes; delivery attempts (at most 8) with
     * exponential back-off; how often real-time partners are served (UAT-5 needs a notice within 15 minutes, so the
     * interval must stay well below that); when the daily batch and the monthly settlement statements run; the
     * business zone for statement periods; and the partner call timeout.
     */
    @ConfigurationProperties("itms.crossborder")
    public record CrossborderProperties(@DefaultValue("LOCAL") String localState,
                                        @DefaultValue("8") int maxAttempts,
                                        @DefaultValue("PT1M") Duration retryBase,
                                        @DefaultValue("PT1H") Duration retryMax,
                                        @DefaultValue("100") int deliveryBatchSize,
                                        @DefaultValue("PT15S") Duration deliveryInterval,
                                        @DefaultValue("PT10S") Duration partnerTimeout,
                                        @DefaultValue("UTC") String zone) {
        public CrossborderProperties {
            if (deliveryInterval.compareTo(Duration.ofMinutes(5)) > 0 || deliveryInterval.isNegative()
                    || deliveryInterval.isZero()) {
                throw new IllegalArgumentException("itms.crossborder.delivery-interval must be up to 5 minutes (UAT-5)");
            }
            if (partnerTimeout.isNegative() || partnerTimeout.isZero()
                    || partnerTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("itms.crossborder.partner-timeout must be up to 2 minutes");
            }
            ZoneId.of(zone);
        }
    }

    @Bean
    Ports.ExchangeRepository exchangeRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcExchangeRepository(jdbc, json);
    }

    @Bean
    CrossborderService crossborderService(Ports.ExchangeRepository repo, AuditRecorder audit,
                                          DomainEventRecorder events, Clock clock, CrossborderProperties p) {
        return new CrossborderService(repo, audit, events, new CrossborderService.Settings(p.localState(),
                p.maxAttempts(), p.retryBase(), p.retryMax()), clock);
    }

    @Bean
    Ports.PartnerTransport partnerTransport(CrossborderProperties p) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(p.partnerTimeout()).build());
        factory.setReadTimeout(p.partnerTimeout());
        return new HttpPartnerTransport(RestClient.builder().requestFactory(factory).build());
    }

    @Bean
    ExchangeDelivery exchangeDelivery(CrossborderService exchange, Ports.PartnerTransport transport,
                                      CrossborderProperties p) {
        return new ExchangeDelivery(exchange, transport, p.deliveryBatchSize());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.crossborder.consumer-enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        ViolationEventListener violationEventListener(CrossborderService exchange, ObjectMapper json) {
            return new ViolationEventListener(exchange, json);
        }
    }

    /** Real-time delivery, the daily batch and monthly statements; safe on every instance (attempts are claimed). */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.crossborder.jobs-enabled", havingValue = "true", matchIfMissing = true)
    static class JobConfiguration {

        @Bean
        ExchangeJobs exchangeJobs(ExchangeDelivery delivery, CrossborderService exchange, CrossborderProperties p,
                                  Clock clock) {
            return new ExchangeJobs(delivery, exchange, ZoneId.of(p.zone()), clock);
        }
    }

    public static class ExchangeJobs {

        private static final Logger LOG = LoggerFactory.getLogger(ExchangeJobs.class);

        private final ExchangeDelivery delivery;
        private final CrossborderService exchange;
        private final ZoneId zone;
        private final Clock clock;

        ExchangeJobs(ExchangeDelivery delivery, CrossborderService exchange, ZoneId zone, Clock clock) {
            this.delivery = delivery;
            this.exchange = exchange;
            this.zone = zone;
            this.clock = clock;
        }

        /** Near real time for partners that support it (XB-008, UAT-5). */
        @Scheduled(fixedDelayString = "${itms.crossborder.delivery-interval:PT15S}",
                initialDelayString = "${itms.crossborder.delivery-initial-delay:PT30S}")
        public void realtime() {
            int sent = delivery.deliverAll("REALTIME");
            if (sent > 0) {
                LOG.info("Cross-border: {} messages delivered in real time", sent);
            }
        }

        /** Once a day for partners without real-time support (XB-008). */
        @Scheduled(cron = "${itms.crossborder.batch-cron:0 0 1 * * *}", zone = "${itms.crossborder.zone:UTC}")
        public void dailyBatch() {
            int sent = delivery.deliverAll("DAILY_BATCH");
            LOG.info("Cross-border daily batch: {} messages delivered", sent);
        }

        /** The previous calendar month's statement for every active partner (XB-004). */
        @Scheduled(cron = "${itms.crossborder.settlement-cron:0 0 3 1 * *}", zone = "${itms.crossborder.zone:UTC}")
        public void monthlyStatements() {
            LocalDate firstOfMonth = ZonedDateTime.now(clock.withZone(zone)).toLocalDate().withDayOfMonth(1);
            var from = firstOfMonth.minusMonths(1).atStartOfDay(zone).toInstant();
            var to = firstOfMonth.atStartOfDay(zone).toInstant();
            for (CrossborderService.Partner p : exchange.partners(null)) {
                try {
                    exchange.issueStatements(p.code(), from, to, null);
                } catch (RuntimeException e) {
                    LOG.warn("Statement for partner {} not issued: {}", p.code(), e.getClass().getSimpleName());
                }
            }
        }
    }
}
