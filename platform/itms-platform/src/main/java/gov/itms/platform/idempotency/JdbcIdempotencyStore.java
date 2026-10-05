package gov.itms.platform.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link IdempotencyStore} on the service's own {@code idempotency_key} table (DATA_MODEL §6). Runs outside the
 * business transaction: a claim is committed before the request runs, so other instances see it at once.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(StoredKey claim) {
        return jdbc.sql("""
                        insert into idempotency_key (principal_id, idempotency_key, request_hash, lock_token, locked_at,
                            expires_at)
                        values (:principal, :key, :hash, :token, :lockedAt, :expiresAt)
                        on conflict do nothing""")
                .param("principal", claim.principal())
                .param("key", claim.key())
                .param("hash", claim.requestHash())
                .param("token", claim.token())
                .param("lockedAt", Timestamp.from(claim.lockedAt()))
                .param("expiresAt", Timestamp.from(claim.expiresAt()))
                .update() == 1;
    }

    @Override
    public Optional<StoredKey> find(String principal, String key) {
        return jdbc.sql("""
                        select principal_id, idempotency_key, request_hash, lock_token, locked_at, expires_at,
                            response_status, response_content_type, response_location, response_body
                        from idempotency_key where principal_id = :principal and idempotency_key = :key""")
                .param("principal", principal)
                .param("key", key)
                .query(JdbcIdempotencyStore::row)
                .optional();
    }

    @Override
    public boolean takeOver(StoredKey claim, String expectedToken) {
        return jdbc.sql("""
                        update idempotency_key
                        set request_hash = :hash, lock_token = :token, locked_at = :lockedAt, expires_at = :expiresAt,
                            response_status = null, response_content_type = null, response_location = null,
                            response_body = null, completed_at = null
                        where principal_id = :principal and idempotency_key = :key and lock_token = :expected""")
                .param("principal", claim.principal())
                .param("key", claim.key())
                .param("hash", claim.requestHash())
                .param("token", claim.token())
                .param("lockedAt", Timestamp.from(claim.lockedAt()))
                .param("expiresAt", Timestamp.from(claim.expiresAt()))
                .param("expected", expectedToken)
                .update() == 1;
    }

    @Override
    public void complete(String principal, String key, String token, StoredResponse response) {
        jdbc.sql("""
                        update idempotency_key
                        set response_status = :status, response_content_type = :contentType,
                            response_location = :location, response_body = :body, completed_at = now()
                        where principal_id = :principal and idempotency_key = :key and lock_token = :token
                            and response_status is null""")
                .param("principal", principal)
                .param("key", key)
                .param("token", token)
                .param("status", response.status())
                .param("contentType", response.contentType())
                .param("location", response.location())
                .param("body", response.body())
                .update();
    }

    @Override
    public void release(String principal, String key, String token) {
        jdbc.sql("""
                        delete from idempotency_key
                        where principal_id = :principal and idempotency_key = :key and lock_token = :token
                            and response_status is null""")
                .param("principal", principal)
                .param("key", key)
                .param("token", token)
                .update();
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                        delete from idempotency_key where ctid in (
                            select ctid from idempotency_key where expires_at <= :now limit :limit)""")
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .update();
    }

    private static StoredKey row(ResultSet rs, int rowNum) throws SQLException {
        int status = rs.getInt("response_status");
        StoredResponse response = rs.wasNull() ? null : new StoredResponse(status,
                rs.getString("response_content_type"), rs.getString("response_location"),
                rs.getBytes("response_body"));
        return new StoredKey(rs.getString("principal_id"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getString("lock_token"), rs.getTimestamp("locked_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), response);
    }
}
