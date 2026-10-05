package gov.itms.platform.idempotency;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Same semantics as {@link JdbcIdempotencyStore}, in memory, for unit tests. */
class InMemoryIdempotencyStore implements IdempotencyStore {

    final Map<String, StoredKey> rows = new ConcurrentHashMap<>();

    private static String id(String principal, String key) {
        return principal + "\u0000" + key;
    }

    @Override
    public boolean insert(StoredKey claim) {
        return rows.putIfAbsent(id(claim.principal(), claim.key()), claim) == null;
    }

    @Override
    public Optional<StoredKey> find(String principal, String key) {
        return Optional.ofNullable(rows.get(id(principal, key)));
    }

    @Override
    public synchronized boolean takeOver(StoredKey claim, String expectedToken) {
        StoredKey current = rows.get(id(claim.principal(), claim.key()));
        if (current == null || !current.token().equals(expectedToken)) {
            return false;
        }
        rows.put(id(claim.principal(), claim.key()), claim);
        return true;
    }

    @Override
    public synchronized void complete(String principal, String key, String token, StoredResponse response) {
        StoredKey current = rows.get(id(principal, key));
        if (current != null && current.token().equals(token) && !current.completed()) {
            rows.put(id(principal, key), new StoredKey(principal, key, current.requestHash(), token,
                    current.lockedAt(), current.expiresAt(), response));
        }
    }

    @Override
    public synchronized void release(String principal, String key, String token) {
        StoredKey current = rows.get(id(principal, key));
        if (current != null && current.token().equals(token) && !current.completed()) {
            rows.remove(id(principal, key));
        }
    }

    @Override
    public synchronized int deleteExpired(Instant now, int limit) {
        int deleted = 0;
        for (var entry : rows.entrySet()) {
            if (deleted < limit && !entry.getValue().expiresAt().isAfter(now)) {
                rows.remove(entry.getKey());
                deleted++;
            }
        }
        return deleted;
    }
}
