package gov.itms.hold.application;

import gov.itms.hold.application.HoldRepository.HoldVersion;
import gov.itms.hold.domain.Channel;
import gov.itms.hold.domain.DomainException;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.Subject;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hold use cases (CC-030; ADR-033):
 * <ul>
 *   <li>placing needs {@value #PLACE} in a grant that reaches the hold's module (CC-001);</li>
 *   <li>lifting needs {@value #LIFT} in a grant of one of the roles the hold names as able to lift it, reaching the
 *       module (CC-030 "who may lift it"), with a reason;</li>
 *   <li>reading needs {@value #READ} reaching the module; a hold outside the caller's scope answers as if it did
 *       not exist (for a lift, {@value #LIFT} in the module also makes it visible);</li>
 *   <li>every change and every view is audited in the same database transaction (CC-120); the database keeps the
 *       temporal history (DAT-002).</li>
 * </ul>
 */
public class HoldService {

    public static final String PLACE = "hold.place";
    public static final String LIFT = "hold.lift";
    public static final String READ = "hold.read";
    static final String ENTITY = "Hold";
    /** Audit entity of a list view: the subject whose holds were shown. */
    static final String ENTITY_SUBJECT = "SubjectHolds";

    private final HoldRepository holds;
    private final AuditRecorder audit;
    private final Clock clock;

    public HoldService(HoldRepository holds, AuditRecorder audit, Clock clock) {
        this.holds = holds;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Hold place(Hold.Spec spec, Caller caller) {
        Channel channel = channelOf(caller);
        if (!caller.grants().allows(PLACE, spec.module())) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you can only place holds in your own modules", List.of("CC-001"));
        }
        Instant now = clock.instant();
        Hold hold = Hold.place(UUID.randomUUID(), spec, caller.actor(), channel, now, today(now));
        holds.insert(hold);
        audit.record("HOLD_PLACED", ENTITY, hold.id().toString(), null, snapshot(hold), spec.reason());
        return hold;
    }

    @Transactional
    public Hold lift(UUID id, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        Hold hold = holds.findForUpdate(id)
                .filter(h -> readable(h, caller) || caller.grants().allows(LIFT, h.spec().module()))
                .orElseThrow(() -> notFound(id));
        if (!caller.grants().allowsThroughRole(LIFT, hold.spec().liftableByRoles(), hold.spec().module())) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "LIFT_NOT_ALLOWED",
                    "only holders of " + hold.spec().liftableByRoles() + " may lift this hold", List.of("CC-030"),
                    String.join(", ", hold.spec().liftableByRoles()));
        }
        Map<String, Object> before = snapshot(hold);
        Instant now = clock.instant();
        hold.lift(caller.actor(), channel, reason, now, today(now));
        holds.saveLift(hold);
        audit.record("HOLD_LIFTED", ENTITY, id.toString(), before, snapshot(hold), hold.lifted().reason());
        return hold;
    }

    @Transactional
    public Hold get(UUID id, Caller caller) {
        Hold hold = holds.find(id).filter(h -> readable(h, caller)).orElseThrow(() -> notFound(id));
        audit.record("HOLD_VIEWED", ENTITY, id.toString(), null, null, null);
        return hold;
    }

    /** Every version of the hold, oldest first (DAT-002). */
    @Transactional
    public List<HoldVersion> history(UUID id, Caller caller) {
        holds.find(id).filter(h -> readable(h, caller)).orElseThrow(() -> notFound(id));
        audit.record("HOLD_HISTORY_VIEWED", ENTITY, id.toString(), null, null, null);
        return holds.history(id);
    }

    /** The subject's holds the caller may see, newest first; holds in other modules are left out (CC-001). */
    @Transactional
    public List<Hold> bySubject(Subject subject, Caller caller) {
        List<Hold> visible = holds.findBySubject(subject).stream().filter(h -> readable(h, caller)).toList();
        audit.record("HOLDS_LISTED", ENTITY_SUBJECT, subject.type() + ":" + subject.id(), null,
                Map.of("count", visible.size()), null);
        return visible;
    }

    /** The service's calendar day; dates of holds are days in the Authority's time zone (clock zone, Q-053). */
    public LocalDate today() {
        return today(clock.instant());
    }

    private LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, clock.getZone());
    }

    private static boolean readable(Hold hold, Caller caller) {
        return caller.grants().allows(READ, hold.spec().module());
    }

    private static Channel channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CHANNEL_REQUIRED",
                    "the request does not name its channel (X-Channel)", List.of("CC-030", "CC-120"));
        }
        return caller.channel();
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "HOLD_NOT_FOUND", "hold " + id + " does not exist",
                List.of("CC-030"));
    }

    private static Map<String, Object> snapshot(Hold hold) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subjectType", hold.spec().subject().type().name());
        m.put("subjectId", hold.spec().subject().id());
        m.put("holdType", hold.spec().holdType());
        m.put("module", hold.spec().module());
        m.put("source", hold.spec().source());
        m.put("sourceRef", hold.spec().sourceRef());
        m.put("startDate", hold.startDate().toString());
        m.put("endDate", hold.spec().endDate() == null ? null : hold.spec().endDate().toString());
        m.put("blockedServices", hold.spec().blockedServices());
        m.put("liftableByRoles", hold.spec().liftableByRoles());
        m.put("status", hold.storedStatus().name());
        m.put("version", hold.version());
        return m;
    }
}
