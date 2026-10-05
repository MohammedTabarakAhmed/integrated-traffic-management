package gov.itms.platform.idempotency;

import gov.itms.platform.idempotency.IdempotencyStore.StoredKey;
import gov.itms.platform.idempotency.IdempotencyStore.StoredResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides what a request carrying an {@code Idempotency-Key} may do (API_SPECIFICATION §2, ADR-032):
 * <ul>
 *   <li>a new key is claimed and the request runs;</li>
 *   <li>the same key with the same request replays the stored response, or waits while the first request runs;</li>
 *   <li>the same key with another request is refused;</li>
 *   <li>a claim held longer than the lock timeout (its request crashed or hung) is taken over; the business rules
 *       (for example CC-027 and the lifecycle) still stop a second effect;</li>
 *   <li>after the retention the key is forgotten and may be used again.</li>
 * </ul>
 * Races between instances are settled by the store's single-statement writes; a lost race is decided again.
 */
public class IdempotencyKeys {

    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyStore store;
    private final Clock clock;
    private final Duration retention;
    private final Duration lockTimeout;

    public IdempotencyKeys(IdempotencyStore store, Clock clock, Duration retention, Duration lockTimeout) {
        this.store = store;
        this.clock = clock;
        this.retention = retention;
        this.lockTimeout = lockTimeout;
    }

    public Outcome claim(String principal, String key, String requestHash) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            StoredKey claim = new StoredKey(principal, key, requestHash, UUID.randomUUID().toString(), now,
                    now.plus(retention), null);
            if (store.insert(claim)) {
                return new Outcome.Claimed(claim.token());
            }
            Optional<StoredKey> found = store.find(principal, key);
            if (found.isEmpty()) {
                continue; // expired and cleaned up in between: try to insert again
            }
            StoredKey existing = found.get();
            boolean expired = !existing.expiresAt().isAfter(now);
            boolean stale = !existing.completed() && !existing.lockedAt().plus(lockTimeout).isAfter(now);
            // constant-time comparison (SpotBugs UNSAFE_HASH_EQUALS)
            if (!expired && !MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.UTF_8),
                    requestHash.getBytes(StandardCharsets.UTF_8))) {
                return new Outcome.Mismatch();
            }
            if (!expired && existing.completed()) {
                return new Outcome.Replay(existing.response());
            }
            if (!expired && !stale) {
                return new Outcome.InProgress();
            }
            if (store.takeOver(claim, existing.token())) {
                return new Outcome.Claimed(claim.token());
            }
        }
        return new Outcome.InProgress();
    }

    public void complete(String principal, String key, String token, StoredResponse response) {
        store.complete(principal, key, token, response);
    }

    public void release(String principal, String key, String token) {
        store.release(principal, key, token);
    }

    public int deleteExpired(int limit) {
        return store.deleteExpired(clock.instant(), limit);
    }

    /** What the filter does with the request. */
    public sealed interface Outcome {

        /** Run the request; {@code token} identifies this claim when completing or releasing it. */
        record Claimed(String token) implements Outcome {
        }

        /** Return the stored response; the request is not run again. */
        record Replay(StoredResponse response) implements Outcome {
        }

        /** The key was used for another request (409). */
        record Mismatch() implements Outcome {
        }

        /** The first request with the key is still running (409, try again shortly). */
        record InProgress() implements Outcome {
        }
    }
}
