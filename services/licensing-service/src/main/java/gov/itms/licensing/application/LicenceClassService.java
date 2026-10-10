package gov.itms.licensing.application;

import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.LicenceClassVersion;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The licence class catalogue (DL-020, DL-062; BRD §6.2):
 * <ul>
 *   <li>versions of a class are submitted ({@value #SUBMIT}) and approved or rejected once by someone else
 *       ({@value #APPROVE}); the maker may withdraw their own pending version;</li>
 *   <li>approving a version ends the previous approved version of the class the day before; an approved version that
 *       starts on or after the new start is an overlap;</li>
 *   <li>examination-service and this service read the version in force and the rule of a customer category
 *       ({@value #READ});</li>
 *   <li>classes are national configuration, so the permissions apply in any grant.</li>
 * </ul>
 */
public class LicenceClassService {

    public static final String SUBMIT = "licensing.class.submit";
    public static final String APPROVE = "licensing.class.approve";
    public static final String READ = "licensing.class.read";
    static final String ENTITY = "LicenceClassVersion";

    private final LicenceClassRepository classes;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public LicenceClassService(LicenceClassRepository classes, AuditRecorder audit, DomainEventRecorder events,
                               Clock clock) {
        this.classes = classes;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** A class rule resolved for one customer category on one date. */
    public record ResolvedRule(LicenceClassVersion version, LicenceClassVersion.CategoryRule rule) {
    }

    @Transactional
    public LicenceClassVersion submit(LicenceClassVersion.Spec spec, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        require(caller, SUBMIT);
        classes.lockClass(spec.classCode());
        Instant now = clock.instant();
        LicenceClassVersion version = LicenceClassVersion.submit(UUID.randomUUID(),
                classes.nextVersionNo(spec.classCode()), spec, reason, caller.actor(), channel, now, today(now));
        classes.insert(version);
        audit.record("LICENCE_CLASS_SUBMITTED", ENTITY, version.id().toString(), null, snapshot(version),
                version.submitted().reason());
        return version;
    }

    @Transactional
    public LicenceClassVersion approve(UUID id, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        require(caller, APPROVE);
        LicenceClassVersion found = classes.find(id).orElseThrow(() -> notFound(id));
        classes.lockClass(found.spec().classCode());
        LicenceClassVersion version = classes.findForUpdate(id).orElseThrow(() -> notFound(id));
        Instant now = clock.instant();
        version.approve(caller.actor(), channel, reason, now, today(now));
        for (LicenceClassVersion other : classes.byClass(version.spec().classCode())) {
            if (other.id().equals(version.id()) || other.status() != LicenceClassVersion.Status.APPROVED
                    || !other.overlaps(version.spec().effectiveFrom(), version.effectiveTo())) {
                continue;
            }
            if (!other.spec().effectiveFrom().isBefore(version.spec().effectiveFrom())) {
                throw DomainException.conflict("CLASS_VERSION_OVERLAP",
                        "an approved version (v" + other.versionNo() + ") already covers this period",
                        List.of("DL-020"), other.versionNo());
            }
            Map<String, Object> before = snapshot(other);
            other.endBefore(version.spec().effectiveFrom());
            classes.saveEnd(other);
            audit.record("LICENCE_CLASS_ENDED", ENTITY, other.id().toString(), before, snapshot(other),
                    "superseded by v" + version.versionNo());
        }
        classes.saveDecision(version);
        audit.record("LICENCE_CLASS_APPROVED", ENTITY, id.toString(), null, snapshot(version), reason);
        events.record("itms.licensing.class-approved", version.spec().classCode(), snapshot(version));
        return version;
    }

    @Transactional
    public LicenceClassVersion reject(UUID id, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        LicenceClassVersion found = classes.find(id).orElseThrow(() -> notFound(id));
        boolean withdrawal = found.submitted().actor().id().equals(caller.actor().id());
        if (!withdrawal) {
            require(caller, APPROVE);
        }
        LicenceClassVersion version = classes.findForUpdate(id).orElseThrow(() -> notFound(id));
        version.reject(caller.actor(), channel, reason, clock.instant());
        classes.saveDecision(version);
        audit.record(withdrawal ? "LICENCE_CLASS_WITHDRAWN" : "LICENCE_CLASS_REJECTED", ENTITY, id.toString(), null,
                snapshot(version), version.decided().reason());
        return version;
    }

    @Transactional(readOnly = true)
    public LicenceClassVersion get(UUID id, Caller caller) {
        require(caller, READ);
        return classes.find(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public List<LicenceClassVersion> versions(String classCode, Caller caller) {
        require(caller, READ);
        return classes.byClass(classCode);
    }

    /** The version of every class in force on {@code day} (today when null), by class code. */
    @Transactional(readOnly = true)
    public List<LicenceClassVersion> inForce(LocalDate day, Caller caller) {
        require(caller, READ);
        LocalDate on = day == null ? today(clock.instant()) : day;
        return classes.approved().stream().filter(v -> v.inForce(on)).toList();
    }

    /** The version of one class in force on {@code day} (today when null). */
    @Transactional(readOnly = true)
    public LicenceClassVersion inForce(String classCode, LocalDate day, Caller caller) {
        require(caller, READ);
        return versionInForce(classCode, day == null ? today(clock.instant()) : day);
    }

    /**
     * The rule of a customer category for a class on {@code day} (DL-020, DL-062): the category's own rule, otherwise
     * the rule for every other category.
     */
    @Transactional(readOnly = true)
    public ResolvedRule rule(String classCode, String customerCategory, LocalDate day, Caller caller) {
        require(caller, READ);
        LocalDate on = day == null ? today(clock.instant()) : day;
        LicenceClassVersion version = versionInForce(classCode, on);
        return new ResolvedRule(version, version.spec().ruleFor(customerCategory).orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "CLASS_RULE_NOT_FOUND",
                        "class " + classCode + " has no rule for customer category " + customerCategory,
                        List.of("DL-020", "DL-062"), classCode, customerCategory)));
    }

    private LicenceClassVersion versionInForce(String classCode, LocalDate on) {
        return classes.byClass(classCode).stream().filter(v -> v.inForce(on)).findFirst()
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "CLASS_NOT_IN_FORCE",
                        "no version of class " + classCode + " is in force on " + on, List.of("DL-020"),
                        classCode, on.toString()));
    }

    private LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, clock.getZone());
    }

    static void require(Caller caller, String permission) {
        if (!caller.grants().holds(permission)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with licence classes", List.of("CC-001"));
        }
    }

    static Channel channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
        return caller.channel();
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "CLASS_VERSION_NOT_FOUND",
                "licence class version " + id + " does not exist", List.of("DL-020"));
    }

    static Map<String, Object> snapshot(LicenceClassVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("classCode", v.spec().classCode());
        m.put("versionNo", v.versionNo());
        m.put("gearboxRecorded", v.spec().gearboxRecorded());
        m.put("rules", v.spec().rules().stream().map(r -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("customerCategory", r.customerCategory());
            rule.put("minAgeYears", r.minAgeYears());
            rule.put("maxAgeYears", r.maxAgeYears());
            rule.put("requiredTests", r.requiredTests());
            rule.put("minTrainingHours", r.minTrainingHours());
            rule.put("medicalRequired", r.medicalRequired());
            rule.put("validityMonths", r.validityMonths());
            rule.put("residencyLinked", r.residencyLinked());
            return rule;
        }).toList());
        m.put("effectiveFrom", v.spec().effectiveFrom().toString());
        m.put("effectiveTo", v.effectiveTo() == null ? null : v.effectiveTo().toString());
        m.put("status", v.status().name());
        return m;
    }
}
