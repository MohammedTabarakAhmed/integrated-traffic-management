package gov.itms.licensing.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A person's driving licence (DL-021…023, DL-050, DL-062). It holds one or more classes, each with its own issue date
 * and status (DL-021); a class tested in an automatic vehicle is restricted to automatic gearboxes (DL-022). The
 * licence records conditions such as corrective lenses (DL-023). The first licence is probationary for a configurable
 * period (DL-050). Its expiry follows the class validity of the holder's customer category and never outlives a linked
 * residency (DL-062).
 * <p>
 * Expiry is not a stored status: an {@code ACTIVE} licence past its expiry date reads as expired ({@link #statusOn}).
 */
public final class Licence {

    public enum Status { ACTIVE, SUSPENDED, CANCELLED, SURRENDERED }

    public enum ClassStatus { ACTIVE, SUSPENDED, CANCELLED }

    private static final Pattern CONDITION = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    static final int MAX_NOTE = 200;

    /** One class of the licence (DL-021, DL-022). */
    public record LicenceClass(String classCode, UUID classVersionId, LocalDate issuedOn, ClassStatus status,
                               boolean automaticOnly, UUID transferId) {
        public LicenceClass {
            Objects.requireNonNull(classCode);
            Objects.requireNonNull(classVersionId);
            Objects.requireNonNull(issuedOn);
            Objects.requireNonNull(status);
        }

        LicenceClass withStatus(ClassStatus next) {
            return new LicenceClass(classCode, classVersionId, issuedOn, next, automaticOnly, transferId);
        }
    }

    /** A condition on the licence (DL-023), from a code list kept in configuration. */
    public record Condition(String code, String note, LocalDate since) {
        public Condition {
            if (code == null || !CONDITION.matcher(code).matches()) {
                throw DomainException.validation("FIELD_INVALID", "condition code is missing or malformed",
                        List.of("DL-023"), "conditions");
            }
            if (note != null && (note.isBlank() || note.strip().length() > MAX_NOTE
                    || note.chars().anyMatch(Character::isISOControl))) {
                throw DomainException.validation("FIELD_INVALID", "condition note is malformed", List.of("DL-023"),
                        "note");
            }
            note = note == null ? null : note.strip();
            Objects.requireNonNull(since);
        }
    }

    private final UUID id;
    private final String licenceNumber;
    private final String trafficFileNumber;
    private final String region;
    private final List<LicenceClass> classes;
    private final List<Condition> conditions;
    private final long version;
    private String customerCategory;
    private Status status;
    private LocalDate issuedOn;
    private LocalDate expiresOn;
    private LocalDate probationUntil;
    private LocalDate residencyExpiresOn;
    private LocalDate suspendedUntil;

    private Licence(UUID id, String licenceNumber, String trafficFileNumber, String region, String customerCategory,
                    Status status, LocalDate issuedOn, LocalDate expiresOn, LocalDate probationUntil,
                    LocalDate residencyExpiresOn, LocalDate suspendedUntil, List<LicenceClass> classes,
                    List<Condition> conditions, long version) {
        this.id = Objects.requireNonNull(id);
        this.licenceNumber = Objects.requireNonNull(licenceNumber);
        this.trafficFileNumber = Objects.requireNonNull(trafficFileNumber);
        this.region = Objects.requireNonNull(region);
        this.customerCategory = Objects.requireNonNull(customerCategory);
        this.status = Objects.requireNonNull(status);
        this.issuedOn = Objects.requireNonNull(issuedOn);
        this.expiresOn = Objects.requireNonNull(expiresOn);
        this.probationUntil = probationUntil;
        this.residencyExpiresOn = residencyExpiresOn;
        this.suspendedUntil = suspendedUntil;
        this.classes = new ArrayList<>(classes);
        this.conditions = new ArrayList<>(conditions);
        this.version = version;
    }

    /** Issues a licence from passed examination files (DL-050). */
    public static Licence issue(UUID id, String licenceNumber, String trafficFileNumber, String region,
                                String customerCategory, LocalDate issuedOn, LocalDate expiresOn,
                                LocalDate probationUntil, LocalDate residencyExpiresOn, List<LicenceClass> classes,
                                List<Condition> conditions) {
        if (classes == null || classes.isEmpty()) {
            throw DomainException.validation("LICENCE_CLASSES_REQUIRED", "a licence needs at least one class",
                    List.of("DL-021"));
        }
        requireDistinct(classes.stream().map(LicenceClass::classCode).toList(), "LICENCE_CLASS_DUPLICATE",
                "DL-021");
        requireDistinct(conditions.stream().map(Condition::code).toList(), "CONDITION_DUPLICATE", "DL-023");
        if (!expiresOn.isAfter(issuedOn)) {
            throw DomainException.validation("LICENCE_EXPIRY_INVALID", "the licence would expire on or before its"
                    + " issue date", List.of("DL-062"));
        }
        return new Licence(id, licenceNumber, trafficFileNumber, region, customerCategory, Status.ACTIVE, issuedOn,
                expiresOn, probationUntil, residencyExpiresOn, null, classes, conditions, 0);
    }

    public static Licence restore(UUID id, String licenceNumber, String trafficFileNumber, String region,
                                  String customerCategory, Status status, LocalDate issuedOn, LocalDate expiresOn,
                                  LocalDate probationUntil, LocalDate residencyExpiresOn, LocalDate suspendedUntil,
                                  List<LicenceClass> classes, List<Condition> conditions, long version) {
        return new Licence(id, licenceNumber, trafficFileNumber, region, customerCategory, status, issuedOn,
                expiresOn, probationUntil, residencyExpiresOn, suspendedUntil, classes, conditions, version);
    }

    /** DL-023. */
    public void addCondition(Condition condition) {
        requireNotEnded();
        if (conditions.stream().anyMatch(c -> c.code().equals(condition.code()))) {
            throw DomainException.conflict("CONDITION_EXISTS", "the licence already has condition " + condition.code(),
                    List.of("DL-023"), condition.code());
        }
        conditions.add(condition);
    }

    /** DL-023. */
    public void removeCondition(String code) {
        requireNotEnded();
        if (!conditions.removeIf(c -> c.code().equals(code))) {
            throw new DomainException(DomainException.Kind.NOT_FOUND, "CONDITION_NOT_FOUND",
                    "the licence has no condition " + code, List.of("DL-023"), code);
        }
    }

    /**
     * DL-051: the licence runs to {@code newExpiry}; the customer category and residency are those of the renewal.
     */
    public void renew(LocalDate newExpiry, String category, LocalDate residency) {
        requireActive();
        if (!newExpiry.isAfter(expiresOn)) {
            throw DomainException.validation("LICENCE_EXPIRY_INVALID", "a renewal must move the expiry date later",
                    List.of("DL-051"));
        }
        this.expiresOn = newExpiry;
        this.customerCategory = Objects.requireNonNull(category);
        this.residencyExpiresOn = residency;
    }

    /**
     * DL-057: a new customer category or residency end. A linked residency that now ends earlier brings the expiry
     * forward (DL-062); a later one never extends it (that is a renewal).
     */
    public void changeTerms(String category, LocalDate residency, boolean residencyLinked) {
        requireNotEnded();
        this.customerCategory = Objects.requireNonNull(category);
        this.residencyExpiresOn = residency;
        if (residencyLinked && residency != null && residency.isBefore(expiresOn)) {
            if (!residency.isAfter(issuedOn)) {
                throw DomainException.validation("RESIDENCY_ENDED", "the holder's residency has ended",
                        List.of("DL-062"));
            }
            this.expiresOn = residency;
        }
    }

    /** Renewal and similar changes need a licence in force, not suspended or ended. */
    public void requireActive() {
        requireNotEnded();
        if (status != Status.ACTIVE) {
            throw DomainException.conflict("LICENCE_NOT_ACTIVE", "licence " + licenceNumber + " is " + status,
                    List.of("DL-051", "DL-058"), status.name());
        }
    }

    /** The codes of the classes in force on the licence. */
    public List<String> activeClassCodes() {
        return classes.stream().filter(c -> c.status() == ClassStatus.ACTIVE).map(LicenceClass::classCode).toList();
    }

    /** The status as the holder sees it on {@code day}: an active licence past its expiry reads EXPIRED. */
    public String statusOn(LocalDate day) {
        return status == Status.ACTIVE && expiresOn.isBefore(day) ? "EXPIRED" : status.name();
    }

    public boolean probationaryOn(LocalDate day) {
        return probationUntil != null && !probationUntil.isBefore(day);
    }

    /** Cancelled and surrendered licences never change again. */
    public void requireNotEnded() {
        if (status == Status.CANCELLED || status == Status.SURRENDERED) {
            throw DomainException.conflict("LICENCE_ENDED", "licence " + licenceNumber + " is " + status,
                    List.of("DL-058"), status.name());
        }
    }

    private static void requireDistinct(List<String> codes, String code, String ref) {
        Set<String> seen = new HashSet<>();
        for (String c : codes) {
            if (!seen.add(c)) {
                throw DomainException.validation(code, c + " is given twice", List.of(ref), c);
            }
        }
    }

    public UUID id() {
        return id;
    }

    public String licenceNumber() {
        return licenceNumber;
    }

    public String trafficFileNumber() {
        return trafficFileNumber;
    }

    public String region() {
        return region;
    }

    public String customerCategory() {
        return customerCategory;
    }

    public Status status() {
        return status;
    }

    public LocalDate issuedOn() {
        return issuedOn;
    }

    public LocalDate expiresOn() {
        return expiresOn;
    }

    public LocalDate probationUntil() {
        return probationUntil;
    }

    public LocalDate residencyExpiresOn() {
        return residencyExpiresOn;
    }

    public LocalDate suspendedUntil() {
        return suspendedUntil;
    }

    public List<LicenceClass> classes() {
        return List.copyOf(classes);
    }

    public List<Condition> conditions() {
        return List.copyOf(conditions);
    }

    public long version() {
        return version;
    }
}
