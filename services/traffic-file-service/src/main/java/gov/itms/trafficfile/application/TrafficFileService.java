package gov.itms.trafficfile.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.trafficfile.application.IdentityRegister.RegisterPerson;
import gov.itms.trafficfile.application.TrafficFileRepository.FileVersion;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.PersonName;
import gov.itms.trafficfile.domain.Profile;
import gov.itms.trafficfile.domain.TrafficFile;
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
 * Traffic-file use cases for individuals (DL-001, DL-002, DL-005, DL-012; ADR-041):
 * <ul>
 *   <li>opening needs {@value #CREATE} in a grant that reaches the region the file is opened in (CC-001). With a
 *       national identity number the identity comes from the register and is locked (DL-002); a visitor's file is
 *       opened from the passport (DL-001). One file per identity document: a second answers 409 with the number of
 *       the existing file;</li>
 *   <li>reading and updating the profile need {@value #READ} / {@value #UPDATE} in any grant: a customer may use
 *       services in any region (CC-113), so files are not region-restricted for staff (Q-057);</li>
 *   <li>identity numbers are masked unless the caller holds {@value #SENSITIVE_READ}, and health-related fields
 *       (classified secret) are shown only to them (SEC-014; done in the API);</li>
 *   <li>a person with a ban in force (DL-013) cannot be given a file (Q-059);</li>
 *   <li>every change and every view is audited in the same transaction (CC-120); changes publish a domain event
 *       through the outbox (INT-005) carrying no personal data.</li>
 * </ul>
 */
public class TrafficFileService {

    public static final String CREATE = "trafficfile.create";
    public static final String READ = "trafficfile.read";
    public static final String UPDATE = "trafficfile.update";
    public static final String SENSITIVE_READ = "trafficfile.sensitive.read";
    /** A customer's own file (ADR-067 d); held only through the implicit CUSTOMER role. */
    public static final String CUSTOMER_READ = "customer.trafficfile.read";
    static final String ENTITY = "TrafficFile";
    static final String EVENT_OPENED = "itms.traffic-file.opened";
    static final String EVENT_PROFILE_UPDATED = "itms.traffic-file.profile-updated";

    private final TrafficFileRepository files;
    private final BanRepository bans;
    private final IdentityRegister register;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final Pattern nationalIdPattern;

    public TrafficFileService(TrafficFileRepository files, BanRepository bans, IdentityRegister register,
                              AuditRecorder audit, DomainEventRecorder events, Clock clock, Pattern nationalIdPattern) {
        this.files = files;
        this.bans = bans;
        this.register = register;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.nationalIdPattern = nationalIdPattern;
    }

    /** What staff give to open an individual's file. */
    public record OpenIndividual(
            Identity.IdentityDocument document,
            TrafficFile.Classification classification,
            String regionOpened,
            VisitorIdentity visitor,
            Profile profile) {
    }

    /** Identity fields entered from a visitor's passport; must be absent for a national identity number (DL-002). */
    public record VisitorIdentity(PersonName name, String nationality, LocalDate birthDate, String gender) {
    }

    @Transactional
    public TrafficFile openIndividual(OpenIndividual command, Caller caller) {
        Channel channel = channelOf(caller);
        if (!caller.grants().allowsInRegion(CREATE, command.regionOpened())) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you can only open files in your own regions", List.of("CC-001"));
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        if (bans.findByDocument(command.document()).stream().anyMatch(b -> b.inForce(today))) {
            throw DomainException.validation("PERSON_BANNED",
                    "this person is on the ban list and cannot be given a traffic file", List.of("DL-013"));
        }
        TrafficFile file = switch (command.document()) {
            case Identity.NationalId nationalId -> fromRegister(nationalId, command, channel, caller, now, today);
            case Identity.Passport passport -> fromPassport(passport, command, channel, caller, now, today);
        };
        try {
            files.insert(file);
        } catch (TrafficFileRepository.DuplicateIdentityException e) {
            throw existing(command.document()).orElseThrow(() -> e);
        }
        audit.record("TRAFFIC_FILE_OPENED", ENTITY, file.fileNumber(), null, snapshot(file), null);
        events.record(EVENT_OPENED, file.fileNumber(), eventData(file));
        return file;
    }

    private TrafficFile fromRegister(Identity.NationalId nationalId, OpenIndividual command, Channel channel,
                                     Caller caller, Instant now, LocalDate today) {
        if (!nationalIdPattern.matcher(nationalId.number()).matches()) {
            throw DomainException.validation("FIELD_INVALID", "nationalIdNo is malformed", List.of("DL-001"),
                    "nationalIdNo");
        }
        if (command.visitor() != null) {
            throw DomainException.validation("REGISTER_FIELDS_GIVEN",
                    "identity fields come from the identity register and cannot be entered", List.of("DL-002"));
        }
        existing(nationalId).ifPresent(e -> {
            throw e;
        });
        RegisterPerson person;
        try {
            person = register.lookup(nationalId.number()).orElseThrow(() -> DomainException.validation(
                    "REGISTER_PERSON_NOT_FOUND", "the identity register does not know this number",
                    List.of("DL-001", "DL-002")));
        } catch (IdentityRegister.RegisterUnavailableException e) {
            throw new DomainException(DomainException.Kind.UNAVAILABLE, "REGISTER_UNAVAILABLE",
                    "the identity register did not answer; try again later", List.of("DL-002", "INT-009"));
        }
        if (person.deceased()) {
            throw DomainException.validation("PERSON_DECEASED", "the identity register reports this person as deceased",
                    List.of("DL-010"));
        }
        Identity identity = new Identity(nationalId, person.name(), person.nationality(), person.birthDate(),
                person.gender(), person.residencyStatus(), person.residencyExpiry());
        return TrafficFile.openIndividual(UUID.randomUUID(), files.nextFileNumber(), command.classification(),
                command.regionOpened(), identity, TrafficFile.IdentitySource.REGISTER, now, command.profile(),
                caller.actor(), channel, now, today);
    }

    private TrafficFile fromPassport(Identity.Passport passport, OpenIndividual command, Channel channel, Caller caller,
                                     Instant now, LocalDate today) {
        VisitorIdentity v = command.visitor();
        if (v == null) {
            throw DomainException.validation("VISITOR_IDENTITY_REQUIRED",
                    "a passport file needs the name, nationality, birth date and gender from the passport",
                    List.of("DL-001"));
        }
        existing(passport).ifPresent(e -> {
            throw e;
        });
        Identity identity = new Identity(passport, v.name(), v.nationality(), v.birthDate(), v.gender(), null, null);
        return TrafficFile.openIndividual(UUID.randomUUID(), files.nextFileNumber(), command.classification(),
                command.regionOpened(), identity, TrafficFile.IdentitySource.DOCUMENT, null, command.profile(),
                caller.actor(), channel, now, today);
    }

    /** A 409 naming the existing file, if the identity document already has one (DL-009 exact duplicates). */
    private Optional<DomainException> existing(Identity.IdentityDocument document) {
        Optional<TrafficFile> found = switch (document) {
            case Identity.NationalId n -> files.findByNationalId(n.number());
            case Identity.Passport p -> files.findByPassport(p.number(), p.country());
        };
        return found.map(f -> DomainException.conflict("FILE_ALREADY_EXISTS",
                "this identity document already has traffic file " + f.fileNumber(), List.of("DL-001", "DL-009"),
                f.fileNumber()));
    }

    /**
     * The signed-in customer's own traffic file (ADR-067 d, CH-002). The national id comes from the token, never from
     * the request, so no one else's file is reachable; the view is audited like a staff view (CC-120).
     */
    @Transactional
    public TrafficFile own(String nationalId, Caller caller) {
        if (!caller.grants().holds(CUSTOMER_READ)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not read your traffic file here", List.of("CC-001"));
        }
        TrafficFile file = files.findByNationalId(nationalId).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "OWN_FILE_NOT_FOUND", "you have no traffic file yet",
                List.of("DL-001")));
        audit.record("TRAFFIC_FILE_VIEWED", ENTITY, file.fileNumber(), null, Map.of("viewer", "OWNER"), null);
        return file;
    }

    @Transactional
    public TrafficFile get(String fileNumber, Caller caller) {
        requireRead(caller);
        TrafficFile file = files.findByNumber(fileNumber).orElseThrow(() -> notFound(fileNumber));
        audit.record("TRAFFIC_FILE_VIEWED", ENTITY, fileNumber, null, null, null);
        return file;
    }

    /**
     * Finds the file of an identity document (CC-100 exact search). The search itself is audited, found or not, since
     * it is a search of personal data (CC-102).
     */
    @Transactional
    public Optional<TrafficFile> findByDocument(Identity.IdentityDocument document, Caller caller) {
        requireRead(caller);
        Optional<TrafficFile> found = switch (document) {
            case Identity.NationalId n -> files.findByNationalId(n.number());
            case Identity.Passport p -> files.findByPassport(p.number(), p.country());
        };
        String kind = document instanceof Identity.NationalId ? "NATIONAL_ID" : "PASSPORT";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentKind", kind);
        result.put("found", found.map(TrafficFile::fileNumber).orElse(null));
        audit.record("TRAFFIC_FILE_SEARCHED", ENTITY, found.map(TrafficFile::fileNumber).orElse("-"), null, result,
                null);
        return found;
    }

    @Transactional
    public TrafficFile updateProfile(String fileNumber, long expectedVersion, Profile profile, Caller caller) {
        Channel channel = channelOf(caller);
        if (!caller.grants().holds(UPDATE)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not change traffic files", List.of("CC-001"));
        }
        TrafficFile file = files.findByNumberForUpdate(fileNumber).orElseThrow(() -> notFound(fileNumber));
        if (file.version() != expectedVersion) {
            throw DomainException.conflict("VERSION_MISMATCH",
                    "the file changed since you read it (now version " + file.version() + ")", List.of("DAT-002"),
                    file.version());
        }
        Map<String, Object> before = snapshot(file);
        file.updateProfile(profile, caller.actor(), channel, clock.instant());
        files.saveProfile(file);
        audit.record("TRAFFIC_FILE_PROFILE_UPDATED", ENTITY, fileNumber, before, snapshot(file), null);
        events.record(EVENT_PROFILE_UPDATED, fileNumber, eventData(file));
        return file;
    }

    /** Every version of the file, oldest first (DAT-002, CC-121); the view is audited. */
    @Transactional
    public List<FileVersion> history(String fileNumber, Caller caller) {
        requireRead(caller);
        TrafficFile file = files.findByNumber(fileNumber).orElseThrow(() -> notFound(fileNumber));
        audit.record("TRAFFIC_FILE_HISTORY_VIEWED", ENTITY, fileNumber, null, null, null);
        return files.history(file.id());
    }

    static void requireRead(Caller caller) {
        if (!caller.grants().holds(READ)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not read traffic files", List.of("CC-001"));
        }
    }

    static Channel channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
        return caller.channel();
    }

    static DomainException notFound(String fileNumber) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "FILE_NOT_FOUND",
                "traffic file " + fileNumber + " does not exist", List.of("DL-001"), fileNumber);
    }

    /** Event payload: business keys only, no personal data (SEC-017 minimisation). */
    static Map<String, Object> eventData(TrafficFile file) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileNumber", file.fileNumber());
        m.put("kind", file.kind().name());
        m.put("category", file.classification().category());
        m.put("type", file.classification().type());
        m.put("regionOpened", file.regionOpened());
        m.put("status", file.status().name());
        m.put("version", file.version());
        return m;
    }

    /**
     * Audit snapshot. Identity numbers are kept out (the audit trail is read more widely than the file); the file
     * number identifies the record (SEC-014).
     */
    private static Map<String, Object> snapshot(TrafficFile file) {
        Map<String, Object> m = eventData(file);
        m.put("identitySource", file.identitySource().name());
        Profile p = file.profile();
        if (p == null) {
            return m;
        }
        m.put("mobileSet", p.mobile() != null);
        m.put("emailSet", p.email() != null);
        m.put("addressSet", p.address() != null);
        m.put("educationLevel", p.educationLevel());
        m.put("profession", p.profession());
        m.put("languages", p.languages());
        m.put("determinationStatus", p.determinationStatus());
        return m;
    }
}
