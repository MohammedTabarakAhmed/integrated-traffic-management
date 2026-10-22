package gov.itms.violation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An administrative preservation order recorded on a violation (VF-047): the order's own reference as issued by the
 * ordering authority, its window, and a note. A record only: it is never changed or deleted once written.
 */
public record PreservationOrder(UUID id, UUID violationId, String violationNumber, String reference, String source,
                                Instant startAt, Instant endAt, String note, String recordedBy, Instant recordedAt) {

    public PreservationOrder {
        Objects.requireNonNull(id);
        Objects.requireNonNull(violationId);
        Objects.requireNonNull(violationNumber);
        Objects.requireNonNull(startAt);
        Objects.requireNonNull(recordedBy);
        Objects.requireNonNull(recordedAt);
        if (reference == null || !reference.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{1,59}$")) {
            throw DomainException.validation("ORDER_INVALID", "a preservation order needs its own reference",
                    List.of("VF-047"));
        }
        if (source == null || source.isBlank() || source.length() > 200) {
            throw DomainException.validation("ORDER_INVALID", "a preservation order says which authority ordered it",
                    List.of("VF-047"));
        }
        if (endAt != null && !endAt.isAfter(startAt)) {
            throw DomainException.validation("ORDER_INVALID",
                    "a preservation order ends after it starts", List.of("VF-047"));
        }
        if (note != null && note.isBlank()) {
            note = null;
        }
        if (note != null && note.length() > 500) {
            throw DomainException.validation("ORDER_INVALID", "a preservation order's note is at most 500 characters",
                    List.of("VF-047"));
        }
    }

    /** An order recorded now on {@code violationNumber} ({@code endAt} null while it runs). */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static PreservationOrder recorded(UUID id, UUID violationId, String violationNumber, String reference,
                                             String source, Instant startAt, Instant endAt, String note, String by,
                                             Instant at) {
        return new PreservationOrder(id, violationId, violationNumber, reference, source, startAt, endAt, note, by, at);
    }
}
