package gov.itms.trafficfile.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.trafficfile.domain.BanEntry;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ban list (DL-013; ADR-043). The list is national: placing needs {@value #PLACE}, lifting {@value #LIFT} and
 * reading {@value #READ}, each in any grant. A ban in force bars the person from opening a traffic file (Q-059);
 * other services learn of bans through the {@code itms.ban.placed} / {@code itms.ban.lifted} events. Every change,
 * view and search is audited (CC-120, CC-102); events carry no identity numbers (SEC-017).
 */
public class BanListService {

    public static final String PLACE = "trafficfile.ban.place";
    public static final String LIFT = "trafficfile.ban.lift";
    public static final String READ = "trafficfile.ban.read";
    static final String ENTITY = "BanEntry";
    static final String EVENT_PLACED = "itms.ban.placed";
    static final String EVENT_LIFTED = "itms.ban.lifted";

    private final BanRepository bans;
    private final TrafficFileRepository files;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public BanListService(BanRepository bans, TrafficFileRepository files, AuditRecorder audit,
                          DomainEventRecorder events, Clock clock) {
        this.bans = bans;
        this.files = files;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public BanEntry place(BanEntry.Spec spec, Caller caller) {
        Channel channel = TrafficFileService.channelOf(caller);
        require(caller, PLACE);
        Instant now = clock.instant();
        String fileNumber = fileOf(spec.person());
        BanEntry ban = BanEntry.place(UUID.randomUUID(), spec, fileNumber, caller.actor(), channel, now, today(now));
        try {
            bans.insert(ban);
        } catch (BanRepository.DuplicateSourceRefException e) {
            throw DomainException.conflict("DUPLICATE_SOURCE_REF", "the source already entered this reference",
                    List.of("DL-013"));
        }
        audit.record("BAN_PLACED", ENTITY, ban.id().toString(), null, snapshot(ban), spec.reason());
        events.record(EVENT_PLACED, ban.id().toString(), eventData(ban));
        return ban;
    }

    @Transactional
    public BanEntry lift(UUID id, String reason, Caller caller) {
        Channel channel = TrafficFileService.channelOf(caller);
        require(caller, LIFT);
        BanEntry ban = bans.findForUpdate(id).orElseThrow(() -> notFound(id));
        Map<String, Object> before = snapshot(ban);
        Instant now = clock.instant();
        ban.lift(caller.actor(), channel, reason, now, today(now));
        bans.saveLift(ban);
        audit.record("BAN_LIFTED", ENTITY, id.toString(), before, snapshot(ban), ban.lifted().reason());
        events.record(EVENT_LIFTED, id.toString(), eventData(ban));
        return ban;
    }

    @Transactional
    public BanEntry get(UUID id, Caller caller) {
        require(caller, READ);
        BanEntry ban = bans.find(id).orElseThrow(() -> notFound(id));
        audit.record("BAN_VIEWED", ENTITY, id.toString(), null, null, null);
        return ban;
    }

    /** Every entry for an identity document, newest first; the search is audited, found or not (CC-102). */
    @Transactional
    public List<BanEntry> byDocument(Identity.IdentityDocument document, Caller caller) {
        require(caller, READ);
        List<BanEntry> found = bans.findByDocument(document);
        audit.record("BAN_LIST_SEARCHED", ENTITY, found.isEmpty() ? "-" : found.getFirst().id().toString(), null,
                Map.of("documentKind", document instanceof Identity.NationalId ? "NATIONAL_ID" : "PASSPORT",
                        "count", found.size()), null);
        return found;
    }

    /** True if a ban in force today bars the holder of this document (used when opening a file). */
    public boolean barred(Identity.IdentityDocument document) {
        LocalDate today = today(clock.instant());
        return bans.findByDocument(document).stream().anyMatch(b -> b.inForce(today));
    }

    public LocalDate today() {
        return today(clock.instant());
    }

    private LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, clock.getZone());
    }

    private String fileOf(Identity.IdentityDocument document) {
        return (switch (document) {
            case Identity.NationalId n -> files.findByNationalId(n.number());
            case Identity.Passport p -> files.findByPassport(p.number(), p.country());
        }).map(TrafficFile::fileNumber).orElse(null);
    }

    private static void require(Caller caller, String permission) {
        if (!caller.grants().holds(permission)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this on the ban list", List.of("CC-001"));
        }
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "BAN_NOT_FOUND", "ban entry " + id + " not found",
                List.of("DL-013"));
    }

    private static Map<String, Object> eventData(BanEntry ban) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("banId", ban.id().toString());
        m.put("fileNumber", ban.fileNumber());
        m.put("source", ban.spec().source());
        m.put("startDate", ban.startDate().toString());
        m.put("endDate", ban.spec().endDate() == null ? null : ban.spec().endDate().toString());
        m.put("status", ban.storedStatus().name());
        return m;
    }

    /** Audit snapshot without the identity number (SEC-014); the ban id and file number identify the person. */
    private static Map<String, Object> snapshot(BanEntry ban) {
        Map<String, Object> m = eventData(ban);
        m.put("sourceRef", ban.spec().sourceRef());
        m.put("version", ban.version());
        return m;
    }
}
