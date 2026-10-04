package gov.itms.transaction.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Position in a list of transactions ordered newest first by (created at, id) (API_SPECIFICATION §5 cursor
 * pagination). Opaque to clients: the creation time in microseconds and the id, URL-safe Base64.
 */
public record TransactionCursor(Instant createdAt, UUID id) {

    public TransactionCursor {
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(id);
    }

    public static TransactionCursor after(Transaction last) {
        return new TransactionCursor(last.createdAt(), last.id());
    }

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + id).getBytes(StandardCharsets.US_ASCII));
    }

    /** Decodes a cursor given back by a client; anything this service did not produce is refused. */
    public static TransactionCursor decode(String value) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int colon = text.indexOf(':');
            long micros = Long.parseLong(text.substring(0, colon));
            UUID id = UUID.fromString(text.substring(colon + 1));
            if (!(micros + ":" + id).equals(text)) {
                throw new IllegalArgumentException("not canonical");
            }
            return new TransactionCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (RuntimeException e) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CURSOR_INVALID",
                    "the cursor is not valid; start again from the first page", List.of("CC-025"));
        }
    }
}
