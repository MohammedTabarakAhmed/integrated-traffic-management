package gov.itms.trafficfile.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A traffic file (§6.1, DL-001..DL-013): the master record every licence, vehicle, violation and transaction refers to.
 * Its number never changes. An individual's file holds an {@link Identity} and a {@link Profile}; a company's file holds
 * a {@link Company} (DL-003, DL-004).
 *
 * <p>Lifecycle: opened ACTIVE; MERGED into a surviving file (DL-009), DECEASED on the register's report (DL-010) or
 * CLOSED. Only an ACTIVE file can be changed.
 */
public final class TrafficFile {

    public enum Kind { INDIVIDUAL, COMPANY }

    public enum Status { ACTIVE, MERGED, CLOSED, DECEASED }

    /** Where the identity came from: the identity register (fields locked, DL-002) or a document entered by staff. */
    public enum IdentitySource { REGISTER, DOCUMENT }

    /** Who did what, when, through which channel (CC-023 style; recorded on every change). */
    public record Change(Actor actor, Channel channel, Instant at) {
        public Change {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(channel);
            Objects.requireNonNull(at);
        }
    }

    /** Category and type of the file (DL-012): reference-data codes, each with its own rules. */
    public record Classification(String category, String type) {
        public Classification {
            category = Codes.code("category", category);
            type = Codes.code("type", type);
        }
    }

    private final UUID id;
    private final String fileNumber;
    private final Kind kind;
    private final Classification classification;
    private final String regionOpened;
    private final Identity identity;
    private final IdentitySource identitySource;
    private final Instant registerSyncedAt;
    private final Change opened;
    private Status status;
    private Profile profile;
    private Company company;
    private Change lastChange;
    private long version;

    private TrafficFile(UUID id, String fileNumber, Kind kind, Classification classification, String regionOpened,
                        Identity identity, IdentitySource identitySource, Instant registerSyncedAt, Change opened,
                        Status status, Profile profile, Company company, Change lastChange, long version) {
        this.id = Objects.requireNonNull(id);
        this.fileNumber = Objects.requireNonNull(fileNumber);
        this.kind = Objects.requireNonNull(kind);
        this.classification = Objects.requireNonNull(classification);
        this.regionOpened = Codes.code("regionOpened", regionOpened);
        if ((kind == Kind.INDIVIDUAL) != (identity != null && profile != null && company == null)) {
            throw new IllegalArgumentException("an individual has an identity and a profile; a company has neither");
        }
        this.identity = identity;
        this.identitySource = Objects.requireNonNull(identitySource);
        this.registerSyncedAt = registerSyncedAt;
        this.opened = Objects.requireNonNull(opened);
        this.status = Objects.requireNonNull(status);
        this.profile = profile;
        this.company = company;
        this.lastChange = Objects.requireNonNull(lastChange);
        this.version = version;
    }

    /**
     * Opens an individual's file (DL-001). A register identity must be the national identity number with a sync time;
     * a document identity is the passport.
     */
    public static TrafficFile openIndividual(UUID id, String fileNumber, Classification classification,
                                             String regionOpened, Identity identity, IdentitySource source,
                                             Instant registerSyncedAt, Profile profile, Actor actor, Channel channel,
                                             Instant now, LocalDate today) {
        boolean register = source == IdentitySource.REGISTER;
        if (register != identity.document() instanceof Identity.NationalId || register != (registerSyncedAt != null)) {
            throw new IllegalArgumentException("register identities are national ids with a sync time");
        }
        if (identity.bornAfter(today)) {
            throw DomainException.validation("BIRTH_DATE_IN_FUTURE", "the birth date lies in the future",
                    List.of("DL-005"));
        }
        Change change = new Change(actor, channel, now);
        return new TrafficFile(id, fileNumber, Kind.INDIVIDUAL, classification, regionOpened, identity, source,
                registerSyncedAt, change, Status.ACTIVE, profile == null ? Profile.EMPTY : profile, null, change, 1);
    }

    /**
     * Opens a company's file (DL-003) from the licence the licensing authority returned ({@code syncedAt}). The
     * licence must not have expired.
     */
    public static TrafficFile openCompany(UUID id, String fileNumber, Classification classification,
                                          String regionOpened, Company company, Instant syncedAt, Actor actor,
                                          Channel channel, Instant now, LocalDate today) {
        Objects.requireNonNull(company);
        Objects.requireNonNull(syncedAt);
        if (company.licenceExpiry().isBefore(today)) {
            throw DomainException.validation("COMPANY_LICENCE_EXPIRED",
                    "the commercial licence expired on " + company.licenceExpiry(), List.of("DL-003"),
                    company.licenceExpiry().toString());
        }
        Change change = new Change(actor, channel, now);
        return new TrafficFile(id, fileNumber, Kind.COMPANY, classification, regionOpened, null,
                IdentitySource.REGISTER, syncedAt, change, Status.ACTIVE, null, company, change, 1);
    }

    public static TrafficFile restore(UUID id, String fileNumber, Kind kind, Classification classification,
                                      String regionOpened, Identity identity, IdentitySource source,
                                      Instant registerSyncedAt, Change opened, Status status, Profile profile,
                                      Company company, Change lastChange, long version) {
        return new TrafficFile(id, fileNumber, kind, classification, regionOpened, identity, source, registerSyncedAt,
                opened, status, profile, company, lastChange, version);
    }

    /** Replaces the profile (DL-005). Identity fields are not part of the profile, so register fields stay locked. */
    public void updateProfile(Profile newProfile, Actor actor, Channel channel, Instant now) {
        requireKind(Kind.INDIVIDUAL);
        requireActive();
        this.profile = Objects.requireNonNull(newProfile);
        this.lastChange = new Change(actor, channel, now);
        this.version++;
    }

    /** Replaces the company's signatories and fleet managers (DL-004); the licence stays as the authority gave it. */
    public void updateRepresentatives(List<String> signatories, List<String> fleetManagers, Actor actor,
                                      Channel channel, Instant now) {
        requireKind(Kind.COMPANY);
        requireActive();
        this.company = company.withRepresentatives(signatories, fleetManagers);
        this.lastChange = new Change(actor, channel, now);
        this.version++;
    }

    private void requireKind(Kind expected) {
        if (kind != expected) {
            throw DomainException.validation("WRONG_FILE_KIND", "traffic file " + fileNumber + " is a " + kind + " file",
                    List.of("DL-012"), fileNumber, kind.name());
        }
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw DomainException.conflict("FILE_NOT_ACTIVE", "traffic file " + fileNumber + " is " + status,
                    List.of("DL-009", "DL-010"), fileNumber, status.name());
        }
    }

    public UUID id() {
        return id;
    }

    public String fileNumber() {
        return fileNumber;
    }

    public Kind kind() {
        return kind;
    }

    public Classification classification() {
        return classification;
    }

    public String regionOpened() {
        return regionOpened;
    }

    /** The individual's identity; {@code null} for a company. */
    public Identity identity() {
        return identity;
    }

    public IdentitySource identitySource() {
        return identitySource;
    }

    public Instant registerSyncedAt() {
        return registerSyncedAt;
    }

    public Change opened() {
        return opened;
    }

    public Status status() {
        return status;
    }

    /** The individual's profile; {@code null} for a company. */
    public Profile profile() {
        return profile;
    }

    /** The company; {@code null} for an individual. */
    public Company company() {
        return company;
    }

    public Change lastChange() {
        return lastChange;
    }

    public long version() {
        return version;
    }
}
