package gov.itms.licensing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A passed examination file handed to licensing (DL-036): one class of one person, with the gearbox of the test
 * vehicle (DL-022). A licence is issued only from a transfer that is still {@code TRANSFERRED} (DL-050); an authorised
 * user may reverse it, with a reason, while no licence uses it (DL-036). Once used it never changes.
 */
public final class ExaminationTransfer {

    public enum Status { TRANSFERRED, REVERSED, USED }

    public enum Gearbox { MANUAL, AUTOMATIC }

    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,59}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern CLASS = Pattern.compile(LicenceClassVersion.CODE);

    /** What examination-service sends. */
    public record Spec(String examinationFileRef, String trafficFileNumber, String classCode, Gearbox gearbox,
                       LocalDate passedOn, String region) {
        public Spec {
            if (examinationFileRef == null || !REF.matcher(examinationFileRef).matches()) {
                throw invalid("examinationFileRef");
            }
            if (trafficFileNumber == null || !REF.matcher(trafficFileNumber).matches()) {
                throw invalid("trafficFileNumber");
            }
            if (classCode == null || !CLASS.matcher(classCode).matches()) {
                throw invalid("classCode");
            }
            if (gearbox == null) {
                throw invalid("gearbox");
            }
            if (passedOn == null) {
                throw invalid("passedOn");
            }
            if (region == null || !CODE.matcher(region).matches()) {
                throw invalid("region");
            }
        }
    }

    private final UUID id;
    private final Spec spec;
    private final Instant receivedAt;
    private final String receivedBy;
    private Status status;
    private UUID licenceId;
    private String reversalReason;
    private String reversedBy;
    private Instant changedAt;

    private ExaminationTransfer(UUID id, Spec spec, Instant receivedAt, String receivedBy, Status status,
                                UUID licenceId, String reversalReason, String reversedBy, Instant changedAt) {
        this.id = Objects.requireNonNull(id);
        this.spec = Objects.requireNonNull(spec);
        this.receivedAt = Objects.requireNonNull(receivedAt);
        this.receivedBy = Objects.requireNonNull(receivedBy);
        this.status = Objects.requireNonNull(status);
        this.licenceId = licenceId;
        this.reversalReason = reversalReason;
        this.reversedBy = reversedBy;
        this.changedAt = changedAt;
    }

    public static ExaminationTransfer receive(UUID id, Spec spec, Actor by, Instant now, LocalDate today) {
        if (spec.passedOn().isAfter(today)) {
            throw DomainException.validation("PASSED_IN_FUTURE", "the pass date lies in the future",
                    List.of("DL-036"));
        }
        return new ExaminationTransfer(id, spec, now, by.id(), Status.TRANSFERRED, null, null, null, null);
    }

    public static ExaminationTransfer restore(UUID id, Spec spec, Instant receivedAt, String receivedBy,
                                              Status status, UUID licenceId, String reversalReason,
                                              String reversedBy, Instant changedAt) {
        return new ExaminationTransfer(id, spec, receivedAt, receivedBy, status, licenceId, reversalReason,
                reversedBy, changedAt);
    }

    /** DL-036: only while no licence has been issued from it. */
    public void reverse(Actor by, String reason, Instant now) {
        requireTransferred();
        this.reversalReason = LicenceClassVersion.reason(reason);
        this.reversedBy = by.id();
        this.status = Status.REVERSED;
        this.changedAt = now;
    }

    public void usedBy(UUID licence, Instant now) {
        requireTransferred();
        this.licenceId = Objects.requireNonNull(licence);
        this.status = Status.USED;
        this.changedAt = now;
    }

    public void requireTransferred() {
        if (status != Status.TRANSFERRED) {
            throw DomainException.conflict("TRANSFER_NOT_OPEN", "examination transfer " + id + " is " + status,
                    List.of("DL-036", "DL-050"), status.name());
        }
    }

    private static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("DL-036"),
                field);
    }

    public UUID id() {
        return id;
    }

    public Spec spec() {
        return spec;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public String receivedBy() {
        return receivedBy;
    }

    public Status status() {
        return status;
    }

    public UUID licenceId() {
        return licenceId;
    }

    public String reversalReason() {
        return reversalReason;
    }

    public String reversedBy() {
        return reversedBy;
    }

    public Instant changedAt() {
        return changedAt;
    }
}
