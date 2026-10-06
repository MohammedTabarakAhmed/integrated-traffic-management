package gov.itms.appointment.infrastructure;

import gov.itms.appointment.application.AppointmentService;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/** Wires appointments and the reminder job. */
@Configuration(proxyBeanMethods = false)
public class AppointmentServiceConfiguration {

    @Bean
    AppointmentService appointmentService(JdbcClient jdbc, ObjectMapper json, AuditRecorder audit,
                                          DomainEventRecorder events, Clock clock) {
        return new AppointmentService(new JdbcAppointmentRepository(jdbc, json), audit, events, clock);
    }

    /**
     * Appointment reminders, {@code itms.appointments.reminders.*} (CC-071, NFR-042).
     *
     * @param lead      how long before the appointment the reminder event goes out, 1 h to 7 days (default 24 h)
     * @param interval  pause between runs, 1 min to 1 h
     * @param batchSize bookings per run, 1-1000
     */
    @ConfigurationProperties("itms.appointments.reminders")
    public record ReminderProperties(@DefaultValue("true") boolean enabled, @DefaultValue("24h") Duration lead,
                                     @DefaultValue("5m") Duration interval, @DefaultValue("200") int batchSize) {
        public ReminderProperties {
            if (lead.compareTo(Duration.ofHours(1)) < 0 || lead.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException("itms.appointments.reminders.lead must be 1 h to 7 days");
            }
            if (interval.compareTo(Duration.ofMinutes(1)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("itms.appointments.reminders.interval must be 1 min to 1 h");
            }
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("itms.appointments.reminders.batch-size must be 1-1000");
            }
        }
    }

    /** Emits reminder events; safe on every instance (rows are locked and skipped by the others). */
    public static class ReminderJob {

        private static final Logger LOG = LoggerFactory.getLogger(ReminderJob.class);
        private final AppointmentService appointments;
        private final ReminderProperties properties;

        public ReminderJob(AppointmentService appointments, ReminderProperties properties) {
            this.appointments = appointments;
            this.properties = properties;
        }

        @Scheduled(initialDelayString = "${itms.appointments.reminders.interval:5m}",
                fixedDelayString = "${itms.appointments.reminders.interval:5m}")
        public void run() {
            try {
                int n = appointments.sendReminders(properties.lead(), properties.batchSize());
                if (n > 0) {
                    LOG.info("Appointment reminders: {} due", n);
                }
            } catch (RuntimeException e) {
                LOG.error("Appointment reminder run failed; it is retried on the next run", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(ReminderProperties.class)
    @ConditionalOnProperty(name = "itms.appointments.reminders.enabled", havingValue = "true", matchIfMissing = true)
    static class ReminderConfiguration {

        @Bean
        ReminderJob reminderJob(AppointmentService appointments, ReminderProperties properties) {
            return new ReminderJob(appointments, properties);
        }
    }
}
