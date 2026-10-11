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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paid changes to a licence (slice 3.4):
 * <ul>
 *   <li>renewal (DL-051): from the renewal window before expiry, with a passed eye test that covers every class in
 *       force; the fee is quoted with the current expiry so fees-service adds the late fee after expiry; the new
 *       expiry runs from the old one (from today when expired), never beyond a linked residency (DL-062);</li>
 *   <li>automatic renewal (DL-065): the holder consents with a payment method on file; for each expiry a notice is
 *       published first, and after the notice period the renewal runs with the same checks, opening the payable and
 *       publishing it for the payment method on file;</li>
 *   <li>replacement of a lost or damaged card (DL-052): the old card is cancelled at once, the new one issued on
 *       payment;</li>
 *   <li>change of details (DL-057): by a request with evidence (customer category, residency end, details printed on
 *       the card), or by an identity register change reported by traffic-file-service, which reissues the card.</li>
 * </ul>
 * Eye test results from approved optometry centres (DL-007) are kept here for renewal.
 */
public class LicenceTransactions implements LicenceService.PaidHandler {

    public static final String RENEW = "licensing.licence.renew";
    public static final String REPLACE = "licensing.licence.replace";
    public static final String CHANGE = "licensing.licence.change";
    public static final String AUTO_RENEWAL = "licensing.auto-renewal.manage";
    public static final String EYE_TEST = "licensing.eye-test.record";
    static final String AUTO_SYSTEM = "licensing-auto-renewal";
    static final String REGISTER_CONSUMER = "licensing-register-changes";
    private static final Pattern DOCUMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$");

    public enum ReplacementReason { LOST, DAMAGED }

    /** What a renewal asks for; null category or residency keeps the licence's own. */
    public record RenewSpec(String customerCategory, LocalDate residencyExpiresOn, Records.Delivery delivery,
                            String address) {
        public RenewSpec {
            address = Records.address(address);
        }
    }

    /** DL-057: what changes, why, and the evidence (a document-service id). */
    public record ChangeSpec(String customerCategory, LocalDate residencyExpiresOn, String description,
                             String evidenceDocumentId, Records.Delivery delivery, String address) {
        public ChangeSpec {
            address = Records.address(address);
        }
    }

    private final LicenceService licences;
    private final LicenceRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final ProcessedEvents processed;
    private final Clock clock;

    public LicenceTransactions(LicenceService licences, AuditRecorder audit, DomainEventRecorder events,
                               ProcessedEvents processed, Clock clock) {
        this.licences = licences;
        this.repo = licences.repo();
        this.audit = audit;
        this.events = events;
        this.processed = processed;
        this.clock = clock;
    }

    @Override
    public boolean handles(LicenceRequest.Kind kind) {
        return kind == LicenceRequest.Kind.RENEWAL || kind == LicenceRequest.Kind.REPLACEMENT
                || kind == LicenceRequest.Kind.CHANGE;
    }

    // ---- renewal (DL-051)

    @Transactional
    public LicenceRequest requestRenewal(UUID licenceId, RenewSpec spec, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        Licence l = licences.licenceInRegion(licenceId, RENEW, caller, false);
        return renewal(l, spec, channel, caller.actor().id(), null, Map.of());
    }

    private LicenceRequest renewal(Licence found, RenewSpec spec, Channel channel, String actorId, CurrentActor as,
                                   Map<String, String> extra) {
        repo.lockHolder(found.trafficFileNumber());
        Licence l = repo.licence(found.id()).orElseThrow();
        l.requireActive();
        licences.requireNoOpenRequest(l);
        LocalDate today = licences.today();
        LicensingRules rules = licences.rules();
        if (today.isBefore(l.expiresOn().minusDays(rules.renewalWindowDays()))) {
            throw DomainException.validation("RENEWAL_TOO_EARLY", "the licence can be renewed from "
                    + l.expiresOn().minusDays(rules.renewalWindowDays()), List.of("DL-051"),
                    l.expiresOn().minusDays(rules.renewalWindowDays()).toString());
        }
        List<String> classCodes = l.activeClassCodes();
        Records.EyeTest eye = repo.eyeTests(l.trafficFileNumber()).stream()
                .filter(t -> t.covers(classCodes, today, rules.eyeTestValidityDays())).findFirst()
                .orElseThrow(() -> DomainException.validation("EYE_TEST_REQUIRED", "a passed eye test of the last "
                        + rules.eyeTestValidityDays() + " days covering every class is needed", List.of("DL-051"),
                        rules.eyeTestValidityDays()));
        Ports.TrafficFiles.Holder holder = licences.holder(l.trafficFileNumber(), spec.customerCategory(),
                spec.residencyExpiresOn());
        String category = holder.customerCategory();
        LocalDate residency = holder.residencyExpiresOn();
        LocalDate from = l.expiresOn().isBefore(today) ? today : l.expiresOn();
        LocalDate newExpiry = licences.terms(classCodes, category, residency, from, today).expiresOn();
        if (!newExpiry.isAfter(l.expiresOn())) {
            throw DomainException.validation("RESIDENCY_TOO_SHORT", "the residency ends before the licence would be"
                    + " extended", List.of("DL-051", "DL-062"));
        }
        String serviceCode = rules.serviceCode(LicenceRequest.Kind.RENEWAL);
        licences.requireNoHolds(serviceCode, l.trafficFileNumber(), l.licenceNumber(),
                channel == null ? Channel.BACKOFFICE : channel);
        Ports.FeeQuotes.Quote quote = licences.fees().quote(serviceCode, l.region(), category, null,
                Map.of("CLASSES", classCodes.size()), l.expiresOn());
        Map<String, String> payload = new LinkedHashMap<>(extra);
        payload.put("customerCategory", category);
        if (residency != null) {
            payload.put("residencyExpiresOn", residency.toString());
        }
        payload.put("newExpiry", newExpiry.toString());
        payload.put("eyeTestId", eye.id().toString());
        payload.put("correctiveLenses", String.valueOf(eye.correctiveLenses()));
        LicenceService.delivery(payload, spec.delivery(), spec.address());
        return licences.open(LicenceRequest.Kind.RENEWAL, l.id(), l.trafficFileNumber(), l.region(), quote, payload,
                List.of(), channel, actorId, as);
    }

    // ---- replacement (DL-052)

    @Transactional
    public LicenceRequest requestReplacement(UUID licenceId, ReplacementReason reason, Records.Delivery delivery,
                                             String address, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        if (reason == null) {
            throw DomainException.validation("FIELD_INVALID", "reason is missing", List.of("DL-052"), "reason");
        }
        Licence found = licences.licenceInRegion(licenceId, REPLACE, caller, false);
        repo.lockHolder(found.trafficFileNumber());
        Licence l = repo.licence(licenceId).orElseThrow();
        l.requireNotEnded();
        licences.requireNoOpenRequest(l);
        String serviceCode = licences.rules().serviceCode(LicenceRequest.Kind.REPLACEMENT);
        licences.requireNoHolds(serviceCode, l.trafficFileNumber(), l.licenceNumber(), channel);
        Ports.FeeQuotes.Quote quote = licences.fees().quote(serviceCode, l.region(), l.customerCategory(), null,
                null, null);
        Instant now = clock.instant();
        int cancelled = licences.cancelCards(l, now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason.name());
        detail.put("cardsCancelled", cancelled);
        repo.history(l.id(), "CARD_CANCELLED", caller.actor().id(), detail, now);
        audit.record("LICENCE_CARD_CANCELLED", LicenceService.ENTITY, l.licenceNumber(), null, detail,
                reason.name());
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("reason", reason.name());
        LicenceService.delivery(payload, delivery, Records.address(address));
        return licences.open(LicenceRequest.Kind.REPLACEMENT, l.id(), l.trafficFileNumber(), l.region(), quote,
                payload, List.of(), channel, caller.actor().id(), null);
    }

    // ---- change of details (DL-057)

    @Transactional
    public LicenceRequest requestChange(UUID licenceId, ChangeSpec spec, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        if (spec.evidenceDocumentId() == null || !DOCUMENT.matcher(spec.evidenceDocumentId()).matches()) {
            throw DomainException.validation("EVIDENCE_REQUIRED", "a change of details needs evidence",
                    List.of("DL-057"));
        }
        String description = LicenceService.reasonOf(spec.description(), "DL-057");
        Licence found = licences.licenceInRegion(licenceId, CHANGE, caller, false);
        repo.lockHolder(found.trafficFileNumber());
        Licence l = repo.licence(licenceId).orElseThrow();
        l.requireNotEnded();
        licences.requireNoOpenRequest(l);
        Ports.TrafficFiles.Holder holder = licences.holder(l.trafficFileNumber(), spec.customerCategory(),
                spec.residencyExpiresOn());
        String category = holder.customerCategory();
        LocalDate residency = holder.residencyExpiresOn();
        if (residency != null && !residency.isAfter(licences.today())
                && residencyLinked(l, category, licences.today())) {
            throw DomainException.validation("RESIDENCY_ENDED", "the holder's residency has ended", List.of("DL-062"));
        }
        String serviceCode = licences.rules().serviceCode(LicenceRequest.Kind.CHANGE);
        licences.requireNoHolds(serviceCode, l.trafficFileNumber(), l.licenceNumber(), channel);
        Ports.FeeQuotes.Quote quote = licences.fees().quote(serviceCode, l.region(), category, null, null, null);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("customerCategory", category);
        if (residency != null) {
            payload.put("residencyExpiresOn", residency.toString());
        }
        payload.put("description", description);
        payload.put("evidenceDocumentId", spec.evidenceDocumentId());
        LicenceService.delivery(payload, spec.delivery(), spec.address());
        return licences.open(LicenceRequest.Kind.CHANGE, l.id(), l.trafficFileNumber(), l.region(), quote, payload,
                List.of(), channel, caller.actor().id(), null);
    }

    /**
     * DL-057, DL-010: traffic-file-service reports an identity register change ({@code itms.traffic-file.
     * identity-updated}). A new name or nationality reissues the card of every licence in force (printed from the
     * traffic file, no fee); a residency change is recorded so staff can apply the new residency end. Each event is
     * applied once.
     *
     * @return how many licences were touched
     */
    @Transactional
    public int onRegisterChange(String eventId, String trafficFileNumber, String changeType) {
        if (!processed.firstDelivery(REGISTER_CONSUMER, eventId)) {
            return 0;
        }
        CurrentActor system = CurrentActor.system(REGISTER_CONSUMER);
        Instant now = clock.instant();
        int n = 0;
        for (Licence l : repo.licencesOf(trafficFileNumber)) {
            if (l.status() == Licence.Status.CANCELLED || l.status() == Licence.Status.SURRENDERED) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("source", "IDENTITY_REGISTER");
            detail.put("changeType", changeType);
            detail.put("registerEventId", eventId);
            if ("NAME".equals(changeType) || "NATIONALITY".equals(changeType)) {
                Records.Card card = licences.issueCard(l, Records.CardReason.CHANGE, Records.Delivery.CENTRE, null,
                        now);
                detail.put("cardSerial", card.serial());
            }
            repo.history(l.id(), "DETAILS_CHANGED", system.userId(), detail, now);
            audit.recordAs(system, "LICENCE_DETAILS_CHANGED", LicenceService.ENTITY, l.licenceNumber(), null, detail,
                    null);
            Map<String, Object> event = LicenceService.snapshot(l);
            event.putAll(detail);
            events.recordAs(system, "itms.licence.changed", l.licenceNumber(), event);
            n++;
        }
        return n;
    }

    // ---- completion on payment

    @Override
    public Licence completed(LicenceRequest r, CurrentActor system) {
        repo.lockHolder(r.trafficFileNumber());
        Licence l = repo.licenceForUpdate(r.licenceId()).orElseThrow(() -> LicenceService.licenceNotFound(
                r.licenceId()));
        Instant now = clock.instant();
        LocalDate today = licences.today();
        Map<String, Object> before = LicenceService.snapshot(l);
        String event;
        Records.CardReason cardReason;
        switch (r.kind()) {
            case RENEWAL -> {
                LocalDate residency = date(r.value("residencyExpiresOn"));
                l.renew(LocalDate.parse(r.value("newExpiry")), r.value("customerCategory"), residency);
                String lenses = licences.rules().lensesCondition();
                if (Boolean.parseBoolean(r.value("correctiveLenses")) && lenses != null
                        && l.conditions().stream().noneMatch(c -> c.code().equals(lenses))) {
                    l.addCondition(new Licence.Condition(lenses, null, today));
                }
                event = "RENEWED";
                cardReason = Records.CardReason.RENEWAL;
            }
            case REPLACEMENT -> {
                l.requireNotEnded();
                event = "REPLACED";
                cardReason = Records.CardReason.REPLACEMENT;
            }
            case CHANGE -> {
                String category = r.value("customerCategory");
                LocalDate residency = date(r.value("residencyExpiresOn"));
                l.changeTerms(category, residency, residencyLinked(l, category, today));
                event = "CHANGED";
                cardReason = Records.CardReason.CHANGE;
            }
            default -> throw new IllegalStateException("not a licence transaction: " + r.kind());
        }
        repo.updateLicence(l);
        Records.Card card = licences.issueCard(l, cardReason, r, now);
        Map<String, Object> detail = LicenceService.snapshot(l);
        detail.put("requestId", r.id().toString());
        detail.put("cardSerial", card.serial());
        if (r.value("reason") != null) {
            detail.put("reason", r.value("reason"));
        }
        if (r.value("evidenceDocumentId") != null) {
            detail.put("evidenceDocumentId", r.value("evidenceDocumentId"));
        }
        repo.history(l.id(), event, system.userId(), detail, now);
        audit.recordAs(system, "LICENCE_" + event, LicenceService.ENTITY, l.licenceNumber(), before, detail,
                r.value("description"));
        events.recordAs(system, switch (r.kind()) {
            case RENEWAL -> "itms.licence.renewed";
            case REPLACEMENT -> "itms.licence.card-replaced";
            default -> "itms.licence.changed";
        }, l.licenceNumber(), detail);
        return l;
    }

    // ---- eye tests (DL-007)

    @Transactional
    public Records.EyeTest recordEyeTest(Records.EyeTest given, Caller caller) {
        LicenceClassService.channelOf(caller);
        LicenceClassService.require(caller, EYE_TEST);
        if (given.testedOn().isAfter(licences.today())) {
            throw DomainException.validation("TESTED_IN_FUTURE", "the test date lies in the future",
                    List.of("DL-007"));
        }
        Records.EyeTest t = new Records.EyeTest(UUID.randomUUID(), given.trafficFileNumber(), given.centreCode(),
                given.reference(), given.testedOn(), given.result(), given.correctiveLenses(),
                given.permittedClasses(), clock.instant(), caller.actor().id());
        if (!repo.insertEyeTest(t)) {
            throw DomainException.conflict("EYE_TEST_EXISTS", "centre " + t.centreCode() + " already sent result "
                    + t.reference(), List.of("DL-007"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trafficFileNumber", t.trafficFileNumber());
        m.put("centreCode", t.centreCode());
        m.put("reference", t.reference());
        m.put("testedOn", t.testedOn().toString());
        m.put("result", t.result().name());
        m.put("correctiveLenses", t.correctiveLenses());
        m.put("permittedClasses", t.permittedClasses());
        audit.record("EYE_TEST_RECEIVED", "EyeTest", t.id().toString(), null, m, null);
        return t;
    }

    @Transactional(readOnly = true)
    public List<Records.EyeTest> eyeTests(String trafficFileNumber, Caller caller) {
        LicenceClassService.require(caller, LicenceService.READ);
        return repo.eyeTests(trafficFileNumber);
    }

    // ---- automatic renewal (DL-065)

    @Transactional
    public Records.AutoRenewal consent(UUID licenceId, String paymentMethodRef, Caller caller) {
        LicenceClassService.channelOf(caller);
        Licence l = licences.licenceInRegion(licenceId, AUTO_RENEWAL, caller, false);
        l.requireActive();
        Instant now = clock.instant();
        Optional<Records.AutoRenewal> existing = repo.autoRenewalForUpdate(licenceId);
        Records.AutoRenewal c = Records.AutoRenewal.consent(licenceId, paymentMethodRef, caller.actor().id(), now);
        if (existing.isPresent()) {
            Records.AutoRenewal e = existing.get();
            c = new Records.AutoRenewal(licenceId, paymentMethodRef, Records.ConsentStatus.ACTIVE,
                    caller.actor().id(), now, e.noticeForExpiry(), e.noticeAt(), e.attemptForExpiry(), e.attemptAt(),
                    null, null);
        }
        repo.saveAutoRenewal(c);
        Map<String, Object> detail = Map.of("paymentMethodRef", paymentMethodRef);
        repo.history(licenceId, "AUTO_RENEWAL_CONSENTED", caller.actor().id(), detail, now);
        audit.record("LICENCE_AUTO_RENEWAL_CONSENTED", LicenceService.ENTITY, l.licenceNumber(), null, detail, null);
        return c;
    }

    @Transactional
    public Records.AutoRenewal withdraw(UUID licenceId, Caller caller) {
        LicenceClassService.channelOf(caller);
        Licence l = licences.licenceInRegion(licenceId, AUTO_RENEWAL, caller, false);
        Records.AutoRenewal c = repo.autoRenewalForUpdate(licenceId).orElseThrow(() -> DomainException.conflict(
                "CONSENT_NOT_ACTIVE", "automatic renewal is not switched on", List.of("DL-065")))
                .withdrawn(caller.actor().id(), clock.instant());
        repo.saveAutoRenewal(c);
        repo.history(licenceId, "AUTO_RENEWAL_WITHDRAWN", caller.actor().id(), Map.of(), clock.instant());
        audit.record("LICENCE_AUTO_RENEWAL_WITHDRAWN", LicenceService.ENTITY, l.licenceNumber(), null, null, null);
        return c;
    }

    @Transactional(readOnly = true)
    public Optional<Records.AutoRenewal> autoRenewal(UUID licenceId, Caller caller) {
        licences.licenceInRegion(licenceId, LicenceService.READ, caller, false);
        return repo.autoRenewal(licenceId);
    }

    /** The licences whose consent may need a notice or a renewal now. */
    @Transactional(readOnly = true)
    public List<UUID> autoRenewalsDue(int limit) {
        return repo.autoRenewalsDue(licences.today().plusDays(licences.rules().renewalWindowDays()), limit).stream()
                .map(Records.AutoRenewal::licenceId).toList();
    }

    /**
     * One step of automatic renewal for a licence (DL-065): the consent notice for the current expiry first; after the
     * notice period, one renewal attempt for that expiry. A failed check is published so the holder can renew at a
     * counter; a partner that did not answer is retried on the next run.
     *
     * @return what was done: NOTICE, RENEWAL, FAILED or NOTHING
     */
    @Transactional
    public String autoRenewOne(UUID licenceId) {
        Optional<Records.AutoRenewal> found = repo.autoRenewalForUpdate(licenceId);
        Licence l = repo.licence(licenceId).orElse(null);
        if (found.isEmpty() || l == null || found.get().status() != Records.ConsentStatus.ACTIVE
                || l.status() != Licence.Status.ACTIVE) {
            return "NOTHING";
        }
        Records.AutoRenewal c = found.get();
        LocalDate today = licences.today();
        LicensingRules rules = licences.rules();
        if (today.isBefore(l.expiresOn().minusDays(rules.renewalWindowDays()))) {
            return "NOTHING";
        }
        CurrentActor system = CurrentActor.system(AUTO_SYSTEM);
        Instant now = clock.instant();
        if (!l.expiresOn().equals(c.noticeForExpiry())) {
            repo.saveAutoRenewal(c.noticed(l.expiresOn(), now));
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("licenceNumber", l.licenceNumber());
            notice.put("trafficFileNumber", l.trafficFileNumber());
            notice.put("expiresOn", l.expiresOn().toString());
            notice.put("renewsOn", today.plusDays(rules.autoRenewalNoticeDays()).toString());
            repo.history(l.id(), "AUTO_RENEWAL_NOTICE", system.userId(), notice, now);
            events.recordAs(system, "itms.licence.auto-renewal-notice", l.licenceNumber(), notice);
            return "NOTICE";
        }
        if (l.expiresOn().equals(c.attemptForExpiry())
                || today.isBefore(LocalDate.ofInstant(c.noticeAt(), clock.getZone())
                .plusDays(rules.autoRenewalNoticeDays()))) {
            return "NOTHING";
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("licenceNumber", l.licenceNumber());
        detail.put("trafficFileNumber", l.trafficFileNumber());
        try {
            LicenceRequest r = renewal(l, new RenewSpec(null, null, Records.Delivery.CENTRE, null), null,
                    system.userId(), system, Map.of("autoRenewal", "true"));
            detail.put("requestId", r.id().toString());
            detail.put("payableId", String.valueOf(r.payableId()));
            detail.put("amountMinor", r.amountMinor());
            detail.put("currency", r.currency());
            detail.put("paymentMethodRef", c.paymentMethodRef());
            repo.saveAutoRenewal(c.attempted(l.expiresOn(), now));
            events.recordAs(system, "itms.licence.auto-renewal-due", l.licenceNumber(), detail);
            return "RENEWAL";
        } catch (Ports.UnavailableException e) {
            return "NOTHING";
        } catch (DomainException e) {
            repo.saveAutoRenewal(c.attempted(l.expiresOn(), now));
            detail.put("code", e.code());
            repo.history(l.id(), "AUTO_RENEWAL_FAILED", system.userId(), detail, now);
            events.recordAs(system, "itms.licence.auto-renewal-failed", l.licenceNumber(), detail);
            return "FAILED";
        }
    }

    private boolean residencyLinked(Licence l, String category, LocalDate today) {
        return licences.residencyLinked(l.activeClassCodes(), category, today);
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
