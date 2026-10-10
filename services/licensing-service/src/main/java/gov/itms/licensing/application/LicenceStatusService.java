package gov.itms.licensing.application;

import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.platform.security.CurrentActor;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The status of a licence (slice 3.5):
 * <ul>
 *   <li>cancellation and surrender (DL-058): surrendered on the holder's request; cancelled on death (from
 *       {@code itms.traffic-file.deceased} or by an officer), on a court order, or, when configured, after the Nth
 *       black-point suspension (CONF-001, interim (a): a black-point event suspends; cancellation is a separate
 *       escalation, off by default);</li>
 *   <li>black-point suspension (VF-032, UAT-1): violation-service decides it and publishes
 *       {@code itms.violation.licence-suspended}; the licence and its classes are suspended to the end date and in
 *       force again the day after;</li>
 *   <li>status enquiry and no-objection certificate (DL-059) for the holder and authorised partners; certificates are
 *       paid, numbered per year and verified with their code.</li>
 * </ul>
 */
public class LicenceStatusService implements LicenceService.PaidHandler {

    public static final String CANCEL = "licensing.licence.cancel";
    public static final String STATUS = "licensing.licence.status";
    public static final String NOC = "licensing.noc.request";
    static final String SUSPENSION_CONSUMER = "licensing-suspensions";
    static final String DEATH_CONSUMER = "licensing-deaths";
    static final String SYSTEM = "licensing-status";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /**
     * {@code itms.licensing.status.*}.
     *
     * @param cancelAfterSuspensions cancel the licence at this black-point suspension (0 = never; CONF-001)
     * @param nocValidityDays        how long a no-objection certificate stays valid (Q-078)
     */
    public record StatusRules(int cancelAfterSuspensions, int nocValidityDays) {
        public StatusRules {
            if (cancelAfterSuspensions < 0 || cancelAfterSuspensions > 20) {
                throw new IllegalArgumentException("itms.licensing.status.cancel-after-suspensions must be 0-20");
            }
            if (nocValidityDays < 1 || nocValidityDays > 366) {
                throw new IllegalArgumentException("itms.licensing.status.noc-validity-days must be 1-366");
            }
        }
    }

    /** What the holder and partners may see of a licence (DL-059): no conditions, no personal details. */
    public record Status(String licenceNumber, String status, LocalDate expiresOn, LocalDate suspendedUntil,
                         boolean probationary, List<Licence.LicenceClass> classes) {
    }

    private final LicenceService licences;
    private final LicenceRepository repo;
    private final StatusRules rules;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final ProcessedEvents processed;
    private final Clock clock;

    public LicenceStatusService(LicenceService licences, StatusRules rules, AuditRecorder audit,
                                DomainEventRecorder events, ProcessedEvents processed, Clock clock) {
        this.licences = licences;
        this.repo = licences.repo();
        this.rules = rules;
        this.audit = audit;
        this.events = events;
        this.processed = processed;
        this.clock = clock;
    }

    // ---- cancellation and surrender (DL-058)

    /**
     * An officer cancels a licence (death, court order) or records its surrender on the holder's request. A court
     * order and a death need a reference (the order or the death record).
     */
    @Transactional
    public Licence end(UUID licenceId, Licence.EndGround ground, String reference, String reason, Caller caller) {
        LicenceClassService.channelOf(caller);
        if (ground == null || ground == Licence.EndGround.BLACK_POINTS) {
            throw DomainException.validation("FIELD_INVALID", "ground must be REQUEST, DEATH or COURT_ORDER",
                    List.of("DL-058"), "ground");
        }
        if (ground != Licence.EndGround.REQUEST && (reference == null || reference.isBlank())) {
            throw DomainException.validation("REFERENCE_REQUIRED", "a " + ground + " needs its reference",
                    List.of("DL-058"), ground.name());
        }
        String why = LicenceService.reasonOf(reason, "DL-058");
        Licence found = licences.licenceInRegion(licenceId, CANCEL, caller, false);
        repo.lockHolder(found.trafficFileNumber());
        Licence l = repo.licenceForUpdate(licenceId).orElseThrow();
        end(l, ground, reference == null ? null : Records.text("reference", reference, "DL-058"), why,
                caller.actor().id(), null);
        return l;
    }

    private void end(Licence l, Licence.EndGround ground, String reference, String why, String actorId,
                     CurrentActor as) {
        Map<String, Object> before = LicenceService.snapshot(l);
        l.end(ground);
        repo.updateLicence(l);
        Instant now = clock.instant();
        int cards = licences.cancelCards(l, now);
        Map<String, Object> detail = LicenceService.snapshot(l);
        detail.put("ground", ground.name());
        detail.put("reference", reference);
        detail.put("cardsCancelled", cards);
        String event = l.status() == Licence.Status.SURRENDERED ? "SURRENDERED" : "CANCELLED";
        repo.history(l.id(), event, actorId, detail, now);
        if (as == null) {
            audit.record("LICENCE_" + event, LicenceService.ENTITY, l.licenceNumber(), before, detail, why);
            events.record("itms.licence." + event.toLowerCase(java.util.Locale.ROOT), l.licenceNumber(), detail);
        } else {
            audit.recordAs(as, "LICENCE_" + event, LicenceService.ENTITY, l.licenceNumber(), before, detail, why);
            events.recordAs(as, "itms.licence." + event.toLowerCase(java.util.Locale.ROOT), l.licenceNumber(),
                    detail);
        }
    }

    /** DL-058: traffic-file-service reports a death; every licence of the person is cancelled, once per event. */
    @Transactional
    public int onDeceased(String eventId, String trafficFileNumber) {
        if (!processed.firstDelivery(DEATH_CONSUMER, eventId)) {
            return 0;
        }
        repo.lockHolder(trafficFileNumber);
        CurrentActor system = CurrentActor.system(DEATH_CONSUMER);
        int n = 0;
        for (Licence found : repo.licencesOf(trafficFileNumber)) {
            if (found.status() == Licence.Status.CANCELLED || found.status() == Licence.Status.SURRENDERED) {
                continue;
            }
            Licence l = repo.licenceForUpdate(found.id()).orElseThrow();
            end(l, Licence.EndGround.DEATH, "REGISTER:" + eventId, "death reported by the identity register",
                    system.userId(), system);
            n++;
        }
        return n;
    }

    // ---- black-point suspension (VF-032, UAT-1)

    /**
     * Mirrors a suspension decided by violation-service on the person's licences in force. With
     * {@code cancel-after-suspensions} set, the Nth suspension cancels instead (CONF-001). Each suspension once.
     *
     * @return how many licences were suspended or cancelled
     */
    @Transactional
    public int onBlackPointSuspension(String eventId, String trafficFileNumber, String suspensionId, int ordinal,
                                      LocalDate from, LocalDate until) {
        if (!processed.firstDelivery(SUSPENSION_CONSUMER, eventId)) {
            return 0;
        }
        repo.lockHolder(trafficFileNumber);
        CurrentActor system = CurrentActor.system(SUSPENSION_CONSUMER);
        Instant now = clock.instant();
        int n = 0;
        for (Licence found : repo.licencesOf(trafficFileNumber)) {
            if (found.status() == Licence.Status.CANCELLED || found.status() == Licence.Status.SURRENDERED) {
                continue;
            }
            Licence l = repo.licenceForUpdate(found.id()).orElseThrow();
            if (rules.cancelAfterSuspensions() > 0 && ordinal >= rules.cancelAfterSuspensions()) {
                end(l, Licence.EndGround.BLACK_POINTS, "SUSPENSION:" + suspensionId,
                        "suspension " + ordinal + " reached the cancellation threshold (CONF-001)", system.userId(),
                        system);
                n++;
                continue;
            }
            Map<String, Object> before = LicenceService.snapshot(l);
            repo.insertSuspension(new Records.Suspension(UUID.randomUUID(), l.id(), "BLACK_POINTS", suspensionId,
                    ordinal, from, until, now));
            l.suspend(until);
            repo.updateLicence(l);
            Map<String, Object> detail = LicenceService.snapshot(l);
            detail.put("source", "BLACK_POINTS");
            detail.put("suspensionId", suspensionId);
            detail.put("ordinal", ordinal);
            detail.put("from", from.toString());
            detail.put("until", until.toString());
            repo.history(l.id(), "SUSPENDED", system.userId(), detail, now);
            audit.recordAs(system, "LICENCE_SUSPENDED", LicenceService.ENTITY, l.licenceNumber(), before, detail,
                    "black-point suspension (VF-032)");
            events.recordAs(system, "itms.licence.suspended", l.licenceNumber(), detail);
            n++;
        }
        return n;
    }

    /** Suspended licences whose suspension is over. */
    @Transactional(readOnly = true)
    public List<UUID> suspensionsEnded(int limit) {
        return repo.suspensionsEndedBefore(licences.today(), limit);
    }

    /** The licence is in force again the day after its suspension ends. */
    @Transactional
    public boolean endSuspension(UUID licenceId) {
        Licence l = repo.licenceForUpdate(licenceId).orElse(null);
        if (l == null) {
            return false;
        }
        Map<String, Object> before = LicenceService.snapshot(l);
        if (!l.endSuspensionOn(licences.today())) {
            return false;
        }
        repo.updateLicence(l);
        CurrentActor system = CurrentActor.system(SYSTEM);
        Map<String, Object> detail = LicenceService.snapshot(l);
        repo.history(l.id(), "SUSPENSION_ENDED", system.userId(), detail, clock.instant());
        audit.recordAs(system, "LICENCE_SUSPENSION_ENDED", LicenceService.ENTITY, l.licenceNumber(), before, detail,
                null);
        events.recordAs(system, "itms.licence.suspension-ended", l.licenceNumber(), detail);
        return true;
    }

    @Transactional(readOnly = true)
    public List<Records.Suspension> suspensions(UUID licenceId, Caller caller) {
        licences.licenceInRegion(licenceId, LicenceService.READ, caller, false);
        return repo.suspensions(licenceId);
    }

    // ---- status enquiry (DL-059)

    /** The holder's or a partner's enquiry by licence number; every enquiry is audited. */
    @Transactional
    public Status status(String licenceNumber, Caller caller) {
        LicenceClassService.require(caller, STATUS);
        Licence l = repo.licenceByNumber(licenceNumber).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "LICENCE_NOT_FOUND", "licence " + licenceNumber + " does not exist",
                List.of("DL-059")));
        LocalDate today = licences.today();
        audit.record("LICENCE_STATUS_ENQUIRED", LicenceService.ENTITY, l.licenceNumber(), null,
                Map.of("channel", String.valueOf(caller.channel())), null);
        return new Status(l.licenceNumber(), l.statusOn(today), l.expiresOn(), l.suspendedUntil(),
                l.probationaryOn(today), l.classes());
    }

    // ---- no-objection certificate (DL-059)

    @Transactional
    public LicenceRequest requestCertificate(UUID licenceId, String purpose, String addressee, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        String p = Records.text("purpose", purpose, "DL-059");
        String a = Records.text("addressee", addressee, "DL-059");
        Licence found = licences.licenceInRegion(licenceId, NOC, caller, false);
        repo.lockHolder(found.trafficFileNumber());
        Licence l = repo.licence(licenceId).orElseThrow();
        l.requireNotEnded();
        licences.requireNoOpenRequest(l);
        String serviceCode = licences.rules().serviceCode(LicenceRequest.Kind.NOC);
        licences.requireNoHolds(serviceCode, l.trafficFileNumber(), l.licenceNumber(), channel);
        Ports.FeeQuotes.Quote quote = licences.fees().quote(serviceCode, l.region(), l.customerCategory(), null,
                null, null);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("purpose", p);
        payload.put("addressee", a);
        return licences.open(LicenceRequest.Kind.NOC, l.id(), l.trafficFileNumber(), l.region(), quote, payload,
                List.of(), channel, caller.actor().id(), null);
    }

    @Override
    public boolean handles(LicenceRequest.Kind kind) {
        return kind == LicenceRequest.Kind.NOC;
    }

    @Override
    public Licence completed(LicenceRequest r, CurrentActor system) {
        Licence l = repo.licence(r.licenceId()).orElseThrow(() -> LicenceService.licenceNotFound(r.licenceId()));
        LocalDate today = licences.today();
        Instant now = clock.instant();
        Records.Certificate c = new Records.Certificate(UUID.randomUUID(), l.id(),
                repo.nextCertificateNumber(today.getYear()), r.value("purpose"), r.value("addressee"),
                verificationCode(), today, today.plusDays(rules.nocValidityDays()), r.id(), now);
        repo.insertCertificate(c);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("certificateNumber", c.number());
        detail.put("licenceNumber", l.licenceNumber());
        detail.put("purpose", c.purpose());
        detail.put("validUntil", c.validUntil().toString());
        detail.put("requestId", r.id().toString());
        repo.history(l.id(), "NOC_ISSUED", system.userId(), detail, now);
        audit.recordAs(system, "LICENCE_NOC_ISSUED", LicenceService.ENTITY, l.licenceNumber(), null, detail, null);
        events.recordAs(system, "itms.licence.noc-issued", l.licenceNumber(), detail);
        return l;
    }

    @Transactional(readOnly = true)
    public List<Records.Certificate> certificates(UUID licenceId, Caller caller) {
        licences.licenceInRegion(licenceId, LicenceService.READ, caller, false);
        return repo.certificates(licenceId);
    }

    /**
     * A partner checks a certificate with its number and verification code; the answer says only whether it is
     * genuine and valid today, with the licence status now.
     */
    @Transactional
    public Map<String, Object> verify(String number, String code, Caller caller) {
        LicenceClassService.require(caller, STATUS);
        Records.Certificate c = repo.certificate(number).filter(x -> x.verificationCode().equals(code))
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "CERTIFICATE_NOT_FOUND",
                        "no certificate with this number and code", List.of("DL-059")));
        Licence l = repo.licence(c.licenceId()).orElseThrow();
        LocalDate today = licences.today();
        audit.record("LICENCE_NOC_VERIFIED", "LicenceCertificate", c.number(), null, null, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("certificateNumber", c.number());
        m.put("licenceNumber", l.licenceNumber());
        m.put("issuedOn", c.issuedOn().toString());
        m.put("validUntil", c.validUntil().toString());
        m.put("valid", c.validOn(today));
        m.put("licenceStatus", l.statusOn(today));
        return m;
    }

    private static String verificationCode() {
        char[] out = new char[10];
        for (int i = 0; i < out.length; i++) {
            out[i] = CODE_CHARS[RANDOM.nextInt(CODE_CHARS.length)];
        }
        return new String(out);
    }
}
