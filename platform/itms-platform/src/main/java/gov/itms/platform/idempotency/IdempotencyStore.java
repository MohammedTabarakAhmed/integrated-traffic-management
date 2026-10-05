package gov.itms.platform.idempotency;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage primitives of the {@code idempotency_key} table (DATA_MODEL §6). Every write is a single atomic statement;
 * the decisions are taken by {@link IdempotencyKeys}. A key belongs to one principal: two callers never share one.
 */
public interface IdempotencyStore {

    /** Inserts a new claim; false when the principal already has a row for the key. */
    boolean insert(StoredKey claim);

    Optional<StoredKey> find(String principal, String key);

    /** Replaces the row with {@code claim} only if it still carries {@code expectedToken}. */
    boolean takeOver(StoredKey claim, String expectedToken);

    /** Stores the response of the claim holding {@code token}; a claim taken over in between is left alone. */
    void complete(String principal, String key, String token, StoredResponse response);

    /** Deletes the claim holding {@code token}, so the key can be used again. */
    void release(String principal, String key, String token);

    /** Deletes up to {@code limit} keys that expired before {@code now}. */
    int deleteExpired(Instant now, int limit);

    /** A row: an open claim ({@code response == null}) or a completed request with its stored response. */
    record StoredKey(String principal, String key, String requestHash, String token, Instant lockedAt,
                     Instant expiresAt, StoredResponse response) {

        boolean completed() {
            return response != null;
        }
    }

    /**
     * The parts of a successful response that are replayed. The body is copied in and out, so it cannot change after it
     * is stored. A class rather than a record: SpotBugs misreads a record that copies an array field.
     */
    final class StoredResponse {

        private final int status;
        private final String contentType;
        private final String location;
        private final byte[] body;

        StoredResponse(int status, String contentType, String location, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.location = location;
            this.body = body == null ? null : body.clone();
        }

        int status() {
            return status;
        }

        String contentType() {
            return contentType;
        }

        String location() {
            return location;
        }

        byte[] body() {
            return body == null ? null : body.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StoredResponse o && status == o.status && Objects.equals(contentType, o.contentType)
                    && Objects.equals(location, o.location) && Arrays.equals(body, o.body);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, contentType, location, Arrays.hashCode(body));
        }

        @Override
        public String toString() {
            return "StoredResponse[status=" + status + ", contentType=" + contentType + ", location=" + location
                    + ", body=" + (body == null ? 0 : body.length) + " bytes]";
        }
    }
}
