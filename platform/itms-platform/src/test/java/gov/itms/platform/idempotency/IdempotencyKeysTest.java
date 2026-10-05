package gov.itms.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.idempotency.IdempotencyKeys.Outcome;
import gov.itms.platform.idempotency.IdempotencyStore.StoredKey;
import gov.itms.platform.idempotency.IdempotencyStore.StoredResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FN-004")
class IdempotencyKeysTest {

    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");
    private static final StoredResponse OK = new StoredResponse(201, "application/json", "/v1/x/1", "{}".getBytes());

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final MutableClock clock = new MutableClock(T0);
    private final IdempotencyKeys keys = new IdempotencyKeys(store, clock, Duration.ofHours(24), Duration.ofSeconds(30));

    @Test
    void newKeyIsClaimedThenReplayedOnceCompleted() {
        Outcome first = keys.claim("u1", "key-00001", "h1");
        assertThat(first).isInstanceOf(Outcome.Claimed.class);
        assertThat(keys.claim("u1", "key-00001", "h1")).isInstanceOf(Outcome.InProgress.class);

        keys.complete("u1", "key-00001", ((Outcome.Claimed) first).token(), OK);
        Outcome again = keys.claim("u1", "key-00001", "h1");
        assertThat(again).isInstanceOf(Outcome.Replay.class);
        assertThat(((Outcome.Replay) again).response()).isSameAs(OK);
    }

    @Test
    void sameKeyWithAnotherRequestMismatchesWhetherRunningOrDone() {
        Outcome.Claimed claimed = (Outcome.Claimed) keys.claim("u1", "key-00002", "h1");
        assertThat(keys.claim("u1", "key-00002", "h2")).isInstanceOf(Outcome.Mismatch.class);
        keys.complete("u1", "key-00002", claimed.token(), OK);
        assertThat(keys.claim("u1", "key-00002", "h2")).isInstanceOf(Outcome.Mismatch.class);
    }

    @Test
    void keysBelongToOnePrincipal() {
        Outcome.Claimed claimed = (Outcome.Claimed) keys.claim("u1", "key-00003", "h1");
        keys.complete("u1", "key-00003", claimed.token(), OK);
        assertThat(keys.claim("u2", "key-00003", "h1")).isInstanceOf(Outcome.Claimed.class);
    }

    @Test
    void releasedKeyCanBeUsedAgain() {
        Outcome.Claimed claimed = (Outcome.Claimed) keys.claim("u1", "key-00004", "h1");
        keys.release("u1", "key-00004", claimed.token());
        assertThat(keys.claim("u1", "key-00004", "h2")).isInstanceOf(Outcome.Claimed.class);
    }

    @Test
    void staleClaimIsTakenOverAndTheOldHolderCanNoLongerCompleteIt() {
        Outcome.Claimed old = (Outcome.Claimed) keys.claim("u1", "key-00005", "h1");
        clock.now = T0.plusSeconds(29);
        assertThat(keys.claim("u1", "key-00005", "h1")).isInstanceOf(Outcome.InProgress.class);
        clock.now = T0.plusSeconds(30);
        Outcome.Claimed taken = (Outcome.Claimed) keys.claim("u1", "key-00005", "h1");
        assertThat(taken.token()).isNotEqualTo(old.token());

        keys.complete("u1", "key-00005", old.token(), OK);
        keys.release("u1", "key-00005", old.token());
        assertThat(store.find("u1", "key-00005")).get().extracting(StoredKey::token, StoredKey::completed)
                .containsExactly(taken.token(), false);
    }

    @Test
    void expiredKeyIsForgottenAndCleanedUp() {
        Outcome.Claimed claimed = (Outcome.Claimed) keys.claim("u1", "key-00006", "h1");
        keys.complete("u1", "key-00006", claimed.token(), OK);
        keys.claim("u1", "key-00007", "h1");
        clock.now = T0.plus(Duration.ofHours(24));
        assertThat(keys.claim("u1", "key-00006", "h2")).isInstanceOf(Outcome.Claimed.class);
        assertThat(new IdempotencyCleanupJob(keys, 1).run()).isEqualTo(1); // key-00007; key-00006 is new again
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void lostRacesAreDecidedAgainAndEndInProgress() {
        // A row that disappears between insert and find, then a take-over that always loses.
        IdempotencyStore racing = new InMemoryIdempotencyStore() {
            int finds;

            @Override
            public boolean insert(StoredKey claim) {
                return false;
            }

            @Override
            public Optional<StoredKey> find(String principal, String key) {
                return finds++ == 0 ? Optional.empty()
                        : Optional.of(new StoredKey(principal, key, "h1", "other", T0.minusSeconds(60), T0.plusSeconds(60),
                        null));
            }

            @Override
            public boolean takeOver(StoredKey claim, String expectedToken) {
                return false;
            }
        };
        IdempotencyKeys k = new IdempotencyKeys(racing, clock, Duration.ofHours(24), Duration.ofSeconds(30));
        assertThat(k.claim("u1", "key-00008", "h1")).isInstanceOf(Outcome.InProgress.class);
    }

    @Test
    void propertiesRejectUnsafeValues() {
        assertThat(new IdempotencyProperties(true, List.of("/v1/x"), Duration.ofHours(1), Duration.ofSeconds(5),
                Duration.ofMinutes(1), 10).paths()).containsExactly("/v1/x");
        assertThat(new IdempotencyProperties(false, null, Duration.ofHours(1), Duration.ofSeconds(5),
                Duration.ofMinutes(1), 10).paths()).isEmpty();
        assertThatThrownBy(() -> new IdempotencyProperties(true, List.of(), Duration.ofHours(1), Duration.ofSeconds(5),
                Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyProperties(false, null, Duration.ZERO, Duration.ofSeconds(5),
                Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyProperties(false, null, Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyProperties(false, null, Duration.ofHours(1), Duration.ofSeconds(5),
                Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyProperties(false, null, Duration.ofHours(1), Duration.ofSeconds(5),
                Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
