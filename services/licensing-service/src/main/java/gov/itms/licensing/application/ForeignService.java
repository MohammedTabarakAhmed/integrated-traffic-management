package gov.itms.licensing.application;

import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.Foreign;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foreign licences and permits (slice 3.6):
 * <ul>
 *   <li>exchange rules per issuing country under maker-checker: direct exchange (with the local class of each foreign
 *       class) or test required (DL-054);</li>
 *   <li>the master file of foreign licences with issuing country and expiry, and of temporary permits (DL-063);</li>
 *   <li>exchange of a valid foreign licence: refused for a test-required country, a certified translation needed when
 *       the licence is not in an accepted language, then fee and payable; the licence is issued on payment
 *       (DL-054);</li>
 *   <li>temporary driving permits for visitors on a valid foreign licence and for holders whose card is pending, with
 *       a fixed validity, numbered from the region's ranges (DL-055, DL-061);</li>
 *   <li>international driving permits against a valid national licence, printed at a centre or by an approved
 *       partner (DL-056).</li>
 * </ul>
 */
public class ForeignService implements LicenceService.PaidHandler {

    public static final String RULE_SUBMIT = "licensing.exchange-country.submit";
    public static final String RULE_APPROVE = "licensing.exchange-country.approve";
    public static final String RECORD = "licensing.foreign.record";
    public static final String EXCHANGE = "licensing.licence.exchange";
    public static final String PERMIT = "licensing.permit.issue";
    public static final String IDP = "licensing.idp.issue";
    public static final String IDP_PRINT = "licensing.idp.print";
    public static final String CENTRE = "CENTRE";
    static final int MAX_PAGE = 500;
    private static final Set<Records.CardStatus> CARD_PENDING = EnumSet.of(Records.CardStatus.PENDING_PRINT,
            Records.CardStatus.SENT_TO_PRINTER, Records.CardStatus.PRINTED, Records.CardStatus.DISPATCHED,
            Records.CardStatus.RETURNED);

    /**
     * {@code itms.licensing.foreign.*} (Q-080 for the periods).
     *
     * @param acceptedLanguages      languages that need no certified translation (DL-054)
     * @param visitorPermitDays      fixed validity of a visitor's temporary permit (DL-055)
     * @param cardPendingPermitDays  fixed validity of a temporary permit while the card is pending (DL-055)
     * @param idpValidityMonths      validity of an international driving permit (DL-056)
     * @param idpPrinters            approved partners that may print international permits (DL-056)
     * @param exchangeProbation      whether a licence issued by exchange is probationary (DL-050)
     */
    public record ForeignRules(Set<String> acceptedLanguages, int visitorPermitDays, int cardPendingPermitDays,
                               int idpValidityMonths, Set<String> idpPrinters, boolean exchangeProbation) {
        public ForeignRules {
            acceptedLanguages = acceptedLanguages == null ? Set.of() : Set.copyOf(acceptedLanguages);
            idpPrinters = idpPrinters == null ? Set.of() : Set.copyOf(idpPrinters);
            if (visitorPermitDays < 1 || visitorPermitDays > 366 || cardPendingPermitDays < 1
                    || cardPendingPermitDays > 366 || idpValidityMonths < 1 || idpValidityMonths > 36) {
                throw new IllegalArgumentException("itms.licensing.foreign periods are out of range (DL-055, DL-056)");
            }
        }
    }

    /** What an officer asks for when exchanging a foreign licence (DL-054). */
    public record ExchangeSpec(String region, String customerCategory, LocalDate residencyExpiresOn,
                               Records.Delivery delivery, String address) {
        public ExchangeSpec {
            address = Records.address(address);
        }
    }

    private final LicenceService licences;
    private final LicenceRepository repo;
    private final ForeignRepository foreign;
    private final LicenceNumberService numbers;
    private final ForeignRules rules;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public ForeignService(LicenceService licences, ForeignRepository foreign, LicenceNumberService numbers,
                          ForeignRules rules, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.licences = licences;
        this.repo = licences.repo();
        this.foreign = foreign;
        this.numbers = numbers;
        this.rules = rules;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---- country exchange rules (DL-054)

    @Transactional
    public Foreign.CountryRule submitRule(String countryCode, Foreign.ExchangeMode mode, Map<String, String> classMap,
                                          String reason, Caller caller) {
        LicenceClassService.channelOf(caller);
        LicenceClassService.require(caller, RULE_SUBMIT);
        String why = LicenceService.reasonOf(reason, "DL-054");
        Foreign.CountryRule rule = new Foreign.CountryRule(UUID.randomUUID(), countryCode, mode, classMap,
                Foreign.RuleStatus.PENDING, caller.actor().id(), clock.instant(), why, null, null, null);
        foreign.lockCountry(countryCode);
        foreign.insertRule(rule);
        audit.record("EXCHANGE_COUNTRY_RULE_SUBMITTED", "ExchangeCountryRule", rule.id().toString(), null,
                snapshot(rule), why);
        return rule;
    }

    @Transactional
    public Foreign.CountryRule decideRule(UUID id, boolean approve, String reason, Caller caller) {
        LicenceClassService.channelOf(caller);
        Foreign.CountryRule found = foreign.rule(id).orElseThrow(() -> ruleNotFound(id));
        boolean withdrawal = !approve && found.submittedBy().equals(caller.actor().id());
        LicenceClassService.require(caller, withdrawal ? RULE_SUBMIT : RULE_APPROVE);
        String why = approve ? (reason == null ? null : LicenceService.reasonOf(reason, "DL-054"))
                : LicenceService.reasonOf(reason, "DL-054");
        foreign.lockCountry(found.countryCode());
        Foreign.CountryRule rule = foreign.ruleForUpdate(id).orElseThrow(() -> ruleNotFound(id))
                .decided(approve ? Foreign.RuleStatus.APPROVED : Foreign.RuleStatus.REJECTED, caller.actor().id(),
                        clock.instant(), why);
        if (approve) {
            foreign.approvedRule(rule.countryCode()).ifPresent(old -> foreign.updateRule(old.superseded()));
        }
        foreign.updateRule(rule);
        audit.record(approve ? "EXCHANGE_COUNTRY_RULE_APPROVED" : withdrawal ? "EXCHANGE_COUNTRY_RULE_WITHDRAWN"
                : "EXCHANGE_COUNTRY_RULE_REJECTED", "ExchangeCountryRule", id.toString(), null, snapshot(rule), why);
        if (approve) {
            events.record("itms.licensing.exchange-country-approved", rule.countryCode(), snapshot(rule));
        }
        return rule;
    }

    @Transactional(readOnly = true)
    public List<Foreign.CountryRule> rules(String countryCode, Caller caller) {
        LicenceClassService.require(caller, LicenceService.READ);
        return foreign.rules(countryCode);
    }

    // ---- foreign licence master file (DL-063)

    @Transactional
    public Foreign.ForeignLicence record(Foreign.ForeignLicence given, Caller caller) {
        LicenceClassService.channelOf(caller);
        LicenceClassService.require(caller, RECORD);
        Foreign.ForeignLicence f = new Foreign.ForeignLicence(UUID.randomUUID(), given.trafficFileNumber(),
                given.countryCode(), given.foreignNumber(), given.classes(), given.issuedOn(), given.expiresOn(),
                given.language(), given.documentId(), given.translationDocumentId(), Foreign.ForeignStatus.RECORDED,
                null, caller.actor().id(), clock.instant());
        if (!foreign.insertForeign(f)) {
            throw DomainException.conflict("FOREIGN_LICENCE_EXISTS", "licence " + f.foreignNumber() + " of "
                    + f.countryCode() + " is already recorded", List.of("DL-063"));
        }
        audit.record("FOREIGN_LICENCE_RECORDED", "ForeignLicence", f.id().toString(), null, snapshot(f), null);
        return f;
    }

    @Transactional(readOnly = true)
    public List<Foreign.ForeignLicence> foreignOf(String trafficFileNumber, Caller caller) {
        LicenceClassService.require(caller, LicenceService.READ);
        return foreign.foreignOf(trafficFileNumber);
    }

    @Transactional(readOnly = true)
    public List<Foreign.ForeignLicence> foreignMasterFile(String countryCode, LocalDate expiringBefore, int offset,
                                                          int limit, Caller caller) {
        LicenceClassService.require(caller, LicenceService.READ);
        page(offset, limit);
        return foreign.foreignMasterFile(countryCode, expiringBefore, offset, limit);
    }

    @Transactional(readOnly = true)
    public List<Foreign.TemporaryPermit> permitMasterFile(LocalDate expiringBefore, int offset, int limit,
                                                          Caller caller) {
        LicenceClassService.require(caller, LicenceService.READ);
        page(offset, limit);
        return foreign.permitMasterFile(expiringBefore, offset, limit);
    }

    // ---- exchange (DL-054)

    @Transactional
    public LicenceRequest requestExchange(UUID foreignId, ExchangeSpec spec, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        LicenceService.requireIn(caller, EXCHANGE, spec.region());
        Foreign.ForeignLicence f = foreign.foreign(foreignId).orElseThrow(() -> foreignNotFound(foreignId));
        repo.lockHolder(f.trafficFileNumber());
        LocalDate today = licences.today();
        if (f.status() != Foreign.ForeignStatus.RECORDED) {
            throw DomainException.conflict("FOREIGN_LICENCE_USED", "this foreign licence was already " + f.status(),
                    List.of("DL-054"), f.status().name());
        }
        if (!f.validOn(today)) {
            throw DomainException.validation("FOREIGN_LICENCE_EXPIRED", "the foreign licence expired on "
                    + f.expiresOn(), List.of("DL-054"), f.expiresOn().toString());
        }
        Foreign.CountryRule rule = foreign.approvedRule(f.countryCode()).orElseThrow(() ->
                DomainException.validation("COUNTRY_RULE_MISSING", "no exchange rule is set for " + f.countryCode(),
                        List.of("DL-054"), f.countryCode()));
        if (rule.mode() == Foreign.ExchangeMode.TEST_REQUIRED) {
            throw DomainException.validation("TEST_REQUIRED", "licences of " + f.countryCode() + " are exchanged only"
                    + " after the tests: open a learning file", List.of("DL-054"), f.countryCode());
        }
        if (!rules.acceptedLanguages().contains(f.language()) && f.translationDocumentId() == null) {
            throw DomainException.validation("TRANSLATION_REQUIRED", "a certified translation of the licence is"
                    + " needed", List.of("DL-054"));
        }
        List<String> local = localClasses(f, rule);
        if (repo.licencesOf(f.trafficFileNumber()).stream().anyMatch(l -> l.status() == Licence.Status.ACTIVE
                || l.status() == Licence.Status.SUSPENDED)) {
            throw DomainException.conflict("LICENCE_EXISTS", "this person already holds a licence",
                    List.of("DL-054"));
        }
        if (!repo.openRequests(f.trafficFileNumber(), LicenceRequest.Kind.EXCHANGE).isEmpty()) {
            throw DomainException.conflict("REQUEST_OPEN", "an exchange of this person waits for payment",
                    List.of("DL-054"));
        }
        Ports.TrafficFiles.Holder holder = licences.holder(f.trafficFileNumber(), spec.customerCategory(),
                spec.residencyExpiresOn());
        licences.terms(local, holder.customerCategory(), holder.residencyExpiresOn(), today, today);
        String serviceCode = licences.rules().serviceCode(LicenceRequest.Kind.EXCHANGE);
        licences.requireNoHolds(serviceCode, f.trafficFileNumber(), null, channel);
        Ports.FeeQuotes.Quote quote = licences.fees().quote(serviceCode, spec.region(), holder.customerCategory(),
                f.countryCode(), Map.of("CLASSES", local.size()), null);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("foreignLicenceId", f.id().toString());
        payload.put("customerCategory", holder.customerCategory());
        if (holder.residencyExpiresOn() != null) {
            payload.put("residencyExpiresOn", holder.residencyExpiresOn().toString());
        }
        payload.put("classes", String.join(",", local));
        LicenceService.delivery(payload, spec.delivery(), spec.address());
        return licences.open(LicenceRequest.Kind.EXCHANGE, null, f.trafficFileNumber(), spec.region(), quote,
                payload, List.of(), channel, caller.actor().id(), null);
    }

    private static List<String> localClasses(Foreign.ForeignLicence f, Foreign.CountryRule rule) {
        Set<String> local = new LinkedHashSet<>();
        for (String c : f.classes()) {
            String mapped = rule.classMap().get(c);
            if (mapped != null) {
                local.add(mapped);
            }
        }
        if (local.isEmpty()) {
            throw DomainException.validation("NO_CLASS_MAPPED", "none of the foreign classes has a local class",
                    List.of("DL-054"));
        }
        return List.copyOf(local);
    }

    // ---- temporary permit (DL-055)

    @Transactional
    public LicenceRequest requestPermit(Foreign.PermitKind kind, String trafficFileNumber, String region,
                                        UUID licenceId, UUID foreignLicenceId, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        LicenceService.requireIn(caller, PERMIT, region);
        if (kind == null) {
            throw DomainException.validation("FIELD_INVALID", "kind is missing", List.of("DL-055"), "kind");
        }
        repo.lockHolder(trafficFileNumber);
        LocalDate today = licences.today();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("permitKind", kind.name());
        LocalDate until;
        String category;
        UUID subjectLicence = null;
        if (kind == Foreign.PermitKind.VISITOR) {
            if (foreignLicenceId == null) {
                throw DomainException.validation("FIELD_INVALID", "a visitor's permit needs foreignLicenceId",
                        List.of("DL-055"), "foreignLicenceId");
            }
            Foreign.ForeignLicence f = foreign.foreign(foreignLicenceId)
                    .orElseThrow(() -> foreignNotFound(foreignLicenceId));
            if (!f.trafficFileNumber().equals(trafficFileNumber)) {
                throw DomainException.validation("FOREIGN_OTHER_PERSON", "the foreign licence belongs to another"
                        + " traffic file", List.of("DL-055"));
            }
            if (!f.validOn(today)) {
                throw DomainException.validation("FOREIGN_LICENCE_EXPIRED", "the foreign licence expired on "
                        + f.expiresOn(), List.of("DL-055"), f.expiresOn().toString());
            }
            until = min(today.plusDays(rules.visitorPermitDays() - 1L), f.expiresOn());
            payload.put("foreignLicenceId", f.id().toString());
            payload.put("classes", String.join(",", f.classes()));
            category = "VISITOR";
        } else {
            if (licenceId == null) {
                throw DomainException.validation("FIELD_INVALID", "a card-pending permit needs licenceId",
                        List.of("DL-055"), "licenceId");
            }
            Licence l = repo.licence(licenceId).orElseThrow(() -> LicenceService.licenceNotFound(licenceId));
            if (!l.trafficFileNumber().equals(trafficFileNumber)) {
                throw DomainException.validation("LICENCE_OTHER_PERSON", "the licence belongs to another traffic"
                        + " file", List.of("DL-055"));
            }
            l.requireActive();
            if (repo.cards(l.id()).stream().noneMatch(c -> CARD_PENDING.contains(c.status()))) {
                throw DomainException.conflict("CARD_NOT_PENDING", "the licence has no card waiting to be handed"
                        + " over", List.of("DL-055"));
            }
            until = min(today.plusDays(rules.cardPendingPermitDays() - 1L), l.expiresOn());
            payload.put("licenceId", l.id().toString());
            payload.put("classes", String.join(",", l.activeClassCodes()));
            category = l.customerCategory();
            subjectLicence = l.id();
        }
        if (foreign.permitsOf(trafficFileNumber).stream().anyMatch(p -> p.kind() == kind
                && "ACTIVE".equals(p.statusOn(today)))) {
            throw DomainException.conflict("PERMIT_EXISTS", "a temporary permit of this kind is still in force",
                    List.of("DL-055"));
        }
        if (!repo.openRequests(trafficFileNumber, LicenceRequest.Kind.TEMP_PERMIT).isEmpty()) {
            throw DomainException.conflict("REQUEST_OPEN", "a permit request of this person waits for payment",
                    List.of("DL-055"));
        }
        payload.put("validUntil", until.toString());
        String serviceCode = licences.rules().serviceCode(LicenceRequest.Kind.TEMP_PERMIT);
        licences.requireNoHolds(serviceCode, trafficFileNumber, null, channel);
        Ports.FeeQuotes.Quote quote = licences.fees().quote(serviceCode, region, category, kind.name(), null, null);
        return licences.open(LicenceRequest.Kind.TEMP_PERMIT, subjectLicence, trafficFileNumber, region, quote,
                payload, List.of(), channel, caller.actor().id(), null);
    }

    @Transactional
    public Foreign.TemporaryPermit cancelPermit(UUID id, String reason, Caller caller) {
        LicenceClassService.channelOf(caller);
        String why = LicenceService.reasonOf(reason, "DL-055");
        Foreign.TemporaryPermit p = foreign.permitForUpdate(id).orElseThrow(() -> permitNotFound(id));
        LicenceService.requireIn(caller, PERMIT, p.region());
        Foreign.TemporaryPermit cancelled = p.cancelled();
        foreign.updatePermit(cancelled);
        audit.record("TEMPORARY_PERMIT_CANCELLED", "TemporaryPermit", p.permitNumber(), null, snapshot(cancelled),
                why);
        events.record("itms.licence.temporary-permit-cancelled", p.permitNumber(), snapshot(cancelled));
        return cancelled;
    }

    @Transactional(readOnly = true)
    public List<Foreign.TemporaryPermit> permitsOf(String trafficFileNumber, Caller caller) {
        LicenceClassService.require(caller, LicenceService.READ);
        return foreign.permitsOf(trafficFileNumber);
    }

    // ---- international driving permit (DL-056)

    @Transactional
    public LicenceRequest requestIdp(UUID licenceId, String printer, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        String by = printer == null ? CENTRE : printer;
        if (!CENTRE.equals(by) && !rules.idpPrinters().contains(by)) {
            throw DomainException.validation("PRINTER_NOT_APPROVED", by + " is not an approved permit printer",
                    List.of("DL-056"), by);
        }
        Licence found = licences.licenceInRegion(licenceId, IDP, caller, false);
        repo.lockHolder(found.trafficFileNumber());
        Licence l = repo.licence(licenceId).orElseThrow();
        l.requireActive();
        LocalDate today = licences.today();
        if (l.expiresOn().isBefore(today)) {
            throw DomainException.validation("LICENCE_EXPIRED", "the national licence has expired",
                    List.of("DL-056"));
        }
        if (foreign.idpsOf(l.id()).stream().anyMatch(p -> p.status() != Foreign.IdpStatus.CANCELLED
                && !p.validUntil().isBefore(today))) {
            throw DomainException.conflict("IDP_EXISTS", "an international permit of this licence is still valid",
                    List.of("DL-056"));
        }
        licences.requireNoOpenRequest(l);
        String serviceCode = licences.rules().serviceCode(LicenceRequest.Kind.IDP);
        licences.requireNoHolds(serviceCode, l.trafficFileNumber(), l.licenceNumber(), channel);
        Ports.FeeQuotes.Quote quote = licences.fees().quote(serviceCode, l.region(), l.customerCategory(), null,
                null, null);
        return licences.open(LicenceRequest.Kind.IDP, l.id(), l.trafficFileNumber(), l.region(), quote,
                Map.of("printer", by), List.of(), channel, caller.actor().id(), null);
    }

    /** The approved partner (or centre) reports the permit printed (DL-056). */
    @Transactional
    public Foreign.InternationalPermit markPrinted(UUID id, String printer, Caller caller) {
        LicenceClassService.channelOf(caller);
        LicenceClassService.require(caller, IDP_PRINT);
        Foreign.InternationalPermit p = foreign.idpForUpdate(id).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "IDP_NOT_FOUND", "international permit " + id + " does not exist",
                List.of("DL-056")));
        if (!p.printer().equals(printer)) {
            throw DomainException.validation("PRINTER_MISMATCH", "this permit is printed by " + p.printer(),
                    List.of("DL-056"), p.printer());
        }
        Foreign.InternationalPermit printed = p.printed(caller.actor().id(), clock.instant());
        foreign.updateIdp(printed);
        audit.record("IDP_PRINTED", "InternationalPermit", p.permitNumber(), null, Map.of("printer", printer), null);
        return printed;
    }

    @Transactional(readOnly = true)
    public List<Foreign.InternationalPermit> idpsOf(UUID licenceId, Caller caller) {
        licences.licenceInRegion(licenceId, LicenceService.READ, caller, false);
        return foreign.idpsOf(licenceId);
    }

    // ---- completion on payment

    @Override
    public boolean handles(LicenceRequest.Kind kind) {
        return kind == LicenceRequest.Kind.EXCHANGE || kind == LicenceRequest.Kind.TEMP_PERMIT
                || kind == LicenceRequest.Kind.IDP;
    }

    @Override
    public Licence completed(LicenceRequest r, CurrentActor system) {
        repo.lockHolder(r.trafficFileNumber());
        Instant now = clock.instant();
        LocalDate today = licences.today();
        return switch (r.kind()) {
            case EXCHANGE -> {
                UUID foreignId = UUID.fromString(r.value("foreignLicenceId"));
                Foreign.ForeignLicence f = foreign.foreignForUpdate(foreignId).orElseThrow(() ->
                        foreignNotFound(foreignId));
                if (f.status() != Foreign.ForeignStatus.RECORDED) {
                    throw DomainException.conflict("FOREIGN_LICENCE_USED", "the foreign licence was already used",
                            List.of("DL-054"), f.status().name());
                }
                List<LicenceService.Grant> grants = new ArrayList<>();
                for (String c : r.value("classes").split(",")) {
                    grants.add(new LicenceService.Grant(c, false, null));
                }
                Licence l = licences.createLicence(r, grants, rules.exchangeProbation(), "ISSUED_BY_EXCHANGE",
                        "itms.licence.issued", system);
                foreign.updateForeign(f.withStatus(Foreign.ForeignStatus.EXCHANGED, l.id()));
                yield l;
            }
            case TEMP_PERMIT -> {
                UUID id = UUID.randomUUID();
                String number = numbers.allocate(r.region(), id.toString(), system).licenceNumber();
                Foreign.PermitKind kind = Foreign.PermitKind.valueOf(r.value("permitKind"));
                Foreign.TemporaryPermit p = new Foreign.TemporaryPermit(id, number, kind, r.trafficFileNumber(),
                        r.region(), r.licenceId(), r.value("foreignLicenceId") == null ? null
                        : UUID.fromString(r.value("foreignLicenceId")), List.of(r.value("classes").split(",")),
                        today, LocalDate.parse(r.value("validUntil")), Foreign.PermitStatus.ACTIVE, r.id(), now);
                foreign.insertPermit(p);
                Map<String, Object> detail = snapshot(p);
                if (r.licenceId() != null) {
                    repo.history(r.licenceId(), "TEMPORARY_PERMIT_ISSUED", system.userId(), detail, now);
                }
                audit.recordAs(system, "TEMPORARY_PERMIT_ISSUED", "TemporaryPermit", number, null, detail, null);
                events.recordAs(system, "itms.licence.temporary-permit-issued", number, detail);
                yield r.licenceId() == null ? null : repo.licence(r.licenceId()).orElse(null);
            }
            case IDP -> {
                Licence l = repo.licence(r.licenceId()).orElseThrow(() -> LicenceService.licenceNotFound(
                        r.licenceId()));
                Foreign.InternationalPermit p = new Foreign.InternationalPermit(UUID.randomUUID(),
                        foreign.nextIdpNumber(today.getYear()), l.id(), today,
                        min(today.plusMonths(rules.idpValidityMonths()), l.expiresOn()), r.value("printer"),
                        Foreign.IdpStatus.ISSUED, null, null, r.id(), now);
                foreign.insertIdp(p);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("permitNumber", p.permitNumber());
                detail.put("licenceNumber", l.licenceNumber());
                detail.put("validUntil", p.validUntil().toString());
                detail.put("printer", p.printer());
                repo.history(l.id(), "IDP_ISSUED", system.userId(), detail, now);
                audit.recordAs(system, "IDP_ISSUED", "InternationalPermit", p.permitNumber(), null, detail, null);
                events.recordAs(system, "itms.licence.idp-issued", l.licenceNumber(), detail);
                yield l;
            }
            default -> throw new IllegalStateException("not a foreign licence transaction: " + r.kind());
        };
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static void page(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE) {
            throw DomainException.validation("PAGE_INVALID", "offset must be 0 or more and limit 1 to " + MAX_PAGE,
                    List.of("DL-063"), MAX_PAGE);
        }
    }

    private static DomainException ruleNotFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "COUNTRY_RULE_NOT_FOUND",
                "exchange country rule " + id + " does not exist", List.of("DL-054"));
    }

    private static DomainException foreignNotFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "FOREIGN_LICENCE_NOT_FOUND",
                "foreign licence " + id + " does not exist", List.of("DL-063"));
    }

    private static DomainException permitNotFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "PERMIT_NOT_FOUND",
                "temporary permit " + id + " does not exist", List.of("DL-055"));
    }

    static Map<String, Object> snapshot(Foreign.CountryRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("countryCode", r.countryCode());
        m.put("mode", r.mode().name());
        m.put("classMap", r.classMap());
        m.put("status", r.status().name());
        return m;
    }

    static Map<String, Object> snapshot(Foreign.ForeignLicence f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trafficFileNumber", f.trafficFileNumber());
        m.put("countryCode", f.countryCode());
        m.put("classes", f.classes());
        m.put("expiresOn", f.expiresOn().toString());
        m.put("language", f.language());
        m.put("translated", f.translationDocumentId() != null);
        m.put("status", f.status().name());
        return m;
    }

    static Map<String, Object> snapshot(Foreign.TemporaryPermit p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("permitNumber", p.permitNumber());
        m.put("kind", p.kind().name());
        m.put("trafficFileNumber", p.trafficFileNumber());
        m.put("classes", p.classes());
        m.put("validFrom", p.validFrom().toString());
        m.put("validUntil", p.validUntil().toString());
        m.put("status", p.status().name());
        return m;
    }
}
