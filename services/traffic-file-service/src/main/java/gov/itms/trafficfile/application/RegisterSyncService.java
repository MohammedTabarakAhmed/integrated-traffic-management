package gov.itms.trafficfile.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.trafficfile.application.IdentityRegister.RegisterPerson;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity register change notifications (DL-010; ICD-01 {@code IdentityChanged}; ADR-045). The register (through its
 * adapter account, {@value #NOTIFY}) reports that a person's residency, name, nationality or life status changed.
 * Each notification is handled once ({@code eventId}); the service does not trust the notice's content but re-reads
 * the person from the register (ICD-01), then updates the locked identity fields of their active file. A reported
 * death makes the file DECEASED. If the register cannot be read, the notification fails with 503 and nothing is
 * recorded, so the register sends it again.
 */
public class RegisterSyncService {

    public static final String NOTIFY = "trafficfile.register.notify";
    static final String CONSUMER = "identity-register";
    static final String EVENT_IDENTITY_UPDATED = "itms.traffic-file.identity-updated";
    static final String EVENT_DECEASED = "itms.traffic-file.deceased";
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    public enum ChangeType { RESIDENCY, NAME, NATIONALITY, DEATH }

    /** What happened to a notification. */
    public enum Outcome { APPLIED, UNCHANGED, DUPLICATE, NO_FILE, FILE_NOT_ACTIVE, NOT_IN_REGISTER }

    public record Notification(String eventId, String nationalIdNo, ChangeType changeType, LocalDate effectiveDate) {
    }

    public record Result(Outcome outcome, String fileNumber) {
    }

    private final TrafficFileRepository files;
    private final IdentityRegister register;
    private final ProcessedEvents processed;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final Pattern nationalIdPattern;

    public RegisterSyncService(TrafficFileRepository files, IdentityRegister register, ProcessedEvents processed,
                               AuditRecorder audit, DomainEventRecorder events, Clock clock,
                               Pattern nationalIdPattern) {
        this.files = files;
        this.register = register;
        this.processed = processed;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.nationalIdPattern = nationalIdPattern;
    }

    @Transactional
    public Result notify(Notification notification, Caller caller) {
        Channel channel = TrafficFileService.channelOf(caller);
        if (!caller.grants().holds(NOTIFY)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "only the identity register's adapter may send notifications", List.of("CC-001", "DL-010"));
        }
        if (notification.eventId() == null || !EVENT_ID.matcher(notification.eventId()).matches()
                || notification.nationalIdNo() == null
                || !nationalIdPattern.matcher(notification.nationalIdNo()).matches()
                || notification.changeType() == null) {
            throw DomainException.validation("FIELD_INVALID", "the notification is incomplete or malformed",
                    List.of("DL-010"), "notification");
        }
        if (!processed.firstDelivery(CONSUMER, notification.eventId())) {
            return new Result(Outcome.DUPLICATE, null);
        }
        Optional<TrafficFile> found = files.findByNationalId(notification.nationalIdNo());
        if (found.isEmpty()) {
            record(notification, null, Outcome.NO_FILE);
            return new Result(Outcome.NO_FILE, null);
        }
        TrafficFile file = files.findByNumberForUpdate(found.get().fileNumber()).orElseThrow();
        if (!file.isActive() || file.identitySource() != TrafficFile.IdentitySource.REGISTER) {
            record(notification, file.fileNumber(), Outcome.FILE_NOT_ACTIVE);
            return new Result(Outcome.FILE_NOT_ACTIVE, file.fileNumber());
        }
        Optional<RegisterPerson> person;
        try {
            person = register.lookup(notification.nationalIdNo());
        } catch (IdentityRegister.RegisterUnavailableException e) {
            throw new DomainException(DomainException.Kind.UNAVAILABLE, "REGISTER_UNAVAILABLE",
                    "the identity register did not answer; send the notification again later",
                    List.of("DL-010", "INT-009"));
        }
        if (person.isEmpty()) {
            record(notification, file.fileNumber(), Outcome.NOT_IN_REGISTER);
            return new Result(Outcome.NOT_IN_REGISTER, file.fileNumber());
        }
        RegisterPerson p = person.get();
        Identity current = new Identity(file.identity().document(), p.name(), p.nationality(), p.birthDate(),
                p.gender(), p.residencyStatus(), p.residencyExpiry());
        Map<String, Object> before = eventData(file, notification);
        boolean changed = file.syncFromRegister(current, p.deceased(), caller.actor(), channel, clock.instant());
        files.saveRegisterSync(file);
        Outcome outcome = changed ? Outcome.APPLIED : Outcome.UNCHANGED;
        Map<String, Object> after = eventData(file, notification);
        audit.record("TRAFFIC_FILE_REGISTER_SYNCED", TrafficFileService.ENTITY, file.fileNumber(), before, after,
                null);
        if (changed) {
            events.record(EVENT_IDENTITY_UPDATED, file.fileNumber(), after);
        }
        if (file.status() == TrafficFile.Status.DECEASED) {
            events.record(EVENT_DECEASED, file.fileNumber(), after);
        }
        return new Result(outcome, file.fileNumber());
    }

    private void record(Notification notification, String fileNumber, Outcome outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", notification.eventId());
        m.put("changeType", notification.changeType().name());
        m.put("outcome", outcome.name());
        audit.record("REGISTER_NOTIFICATION_RECEIVED", TrafficFileService.ENTITY, fileNumber == null ? "-" : fileNumber,
                null, m, null);
    }

    /** Business keys and the change type only; the identity number and names stay out (SEC-017). */
    private static Map<String, Object> eventData(TrafficFile file, Notification notification) {
        Map<String, Object> m = TrafficFileService.eventData(file);
        m.put("changeType", notification.changeType().name());
        m.put("registerEventId", notification.eventId());
        m.put("effectiveDate", notification.effectiveDate() == null ? null : notification.effectiveDate().toString());
        return m;
    }
}
