package gov.itms.trafficfile.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A traffic file (§6.1, DL-001..DL-013): the master record every licence, vehicle, violation and transaction refers to.
 * Its number never changes. This version holds individuals; companies follow (DL-003, DL-004).
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
    private Change lastChange;
    private long version;

    private TrafficFile(UUID id, String fileNumber, Kind kind, Classification classification, String regionOpened,
                        Identity identity, IdentitySource identitySource, Instant registerSyncedAt, Change opened,
                        Status status, Profile profile, Change lastChange, long version) {
        this.id = Objects.requireNonNull(id);
        this.fileNumber = Objects.requireNonNull(fileNumber);
        this.kind = Objects.requireNonNull(kind);
        this.classification = Objects.requireNonNull(classification);
        this.regionOpened = Codes.code("regionOpened", regionOpened);
        this.identity = Objects.requireNonNull(identity);
        this.identitySource = Objects.requireNonNull(identitySource);
        this.registerSyncedAt = registerSyncedAt;
        this.opened = Objects.requireNonNull(opened);
        this.status = Objects.requireNonNull(status);
        this.profile = Objects.requireNonNull(profile);
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
                registerSyncedAt, change, Status.ACTIVE, profile == null ? Profile.EMPTY : profile, change, 1);
    }

    public static TrafficFile restore(UUID id, String fileNumber, Kind kind, Classification classification,
                                      String regionOpened, Identity identity, IdentitySource source,
                                      Instant registerSyncedAt, Change opened, Status status, Profile profile,
                                      Change lastChange, long version) {
        return new TrafficFile(id, fileNumber, kind, classification, regionOpened, identity, source, registerSyncedAt,
                opened, status, profile, lastChange, version);
    }

    /** Replaces the profile (DL-005). Identity fields are not part of the profile, so register fields stay locked. */
    public void updateProfile(Profile newProfile, Actor actor, Channel channel, Instant now) {
        requireActive();
        this.profile = Objects.requireNonNull(newProfile);
        this.lastChange = new Change(actor, channel, now);
        this.version++;
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

    public Profile profile() {
        return profile;
    }

    public Change lastChange() {
        return lastChange;
    }

    public long version() {
        return version;
    }
}
