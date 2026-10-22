package gov.itms.violation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A driver's signed undertaking recorded on a violation (VF-046): who signed, when they signed, and the evidence
 * references (for example a scan of the signed form). A record only: it is never changed or deleted once written.
 */
public record ViolationPledge(UUID id, UUID violationId, String violationNumber, String signedBy, Instant signedAt,
                              String note, List<String> documentIds, String recordedBy, Instant recordedAt) {

    public ViolationPledge {
        Objects.requireNonNull(id);
        Objects.requireNonNull(violationId);
        Objects.requireNonNull(violationNumber);
        Objects.requireNonNull(signedAt);
        Objects.requireNonNull(recordedBy);
        Objects.requireNonNull(recordedAt);
        if (signedBy == null || signedBy.isBlank() || signedBy.length() > 200) {
            throw DomainException.validation("PLEDGE_INVALID",
                    "a pledge says who signed it (1 to 200 characters)", List.of("VF-046"));
        }
        if (note != null && note.isBlank()) {
            note = null;
        }
        if (note != null && note.length() > 500) {
            throw DomainException.validation("PLEDGE_INVALID", "a pledge's note is at most 500 characters",
                    List.of("VF-046"));
        }
        documentIds = List.copyOf(documentIds == null ? List.of() : documentIds);
        if (documentIds.size() > 20 || documentIds.stream().anyMatch(e -> e == null || !e.matches("^[A-Za-z0-9-]{1,64}$"))) {
            throw DomainException.validation("PLEDGE_INVALID", "a pledge's document references are invalid",
                    List.of("VF-046"));
        }
    }

    /** A pledge recorded now on {@code violationNumber}. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ViolationPledge recorded(UUID id, UUID violationId, String violationNumber, String signedBy,
                                           Instant signedAt, String note, List<String> documentIds, String by,
                                           Instant at) {
        return new ViolationPledge(id, violationId, violationNumber, signedBy, signedAt, note, documentIds, by, at);
    }
}
