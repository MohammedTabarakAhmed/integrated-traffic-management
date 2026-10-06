package gov.itms.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One audit record as every service writes it (CC-120): what was done to which record, by whom (user, roles), when,
 * through which channel, from which address, with old and new values and the reason. Stored once, never changed
 * (CC-122), chained to the previous record by SHA-256 so that any later change or removal is detectable.
 *
 * @param seq      position in the chain (assigned by the store)
 * @param oldValue JSON text or {@code null}
 * @param newValue JSON text or {@code null}
 */
public record AuditRecord(long seq, UUID auditId, Instant occurredAt, String service, String action,
                          String entityType, String entityId, String actorId, String actorUsername,
                          List<String> actorRoles, String channel, String ipAddress, String correlationId,
                          String oldValue, String newValue, String reason, Instant receivedAt, String prevHash,
                          String hash) {

    public static final String GENESIS = "0".repeat(64);

    public AuditRecord {
        Objects.requireNonNull(auditId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(service);
        Objects.requireNonNull(action);
        Objects.requireNonNull(entityType);
        Objects.requireNonNull(entityId);
        Objects.requireNonNull(actorId);
        actorRoles = actorRoles == null ? List.of() : List.copyOf(actorRoles);
    }

    /** The same record, placed in the chain after {@code previousHash} at {@code position}. */
    public AuditRecord chained(long position, String previousHash, Instant received) {
        AuditRecord placed = new AuditRecord(position, auditId, occurredAt, service, action, entityType, entityId,
                actorId, actorUsername, actorRoles, channel, ipAddress, correlationId, oldValue, newValue, reason,
                received, previousHash, null);
        return new AuditRecord(position, auditId, occurredAt, service, action, entityType, entityId, actorId,
                actorUsername, actorRoles, channel, ipAddress, correlationId, oldValue, newValue, reason, received,
                previousHash, placed.computeHash());
    }

    /**
     * SHA-256 over the previous hash and every field in a fixed order, each length-prefixed so that no two different
     * records give the same input.
     */
    public String computeHash() {
        StringBuilder b = new StringBuilder();
        for (String field : new String[] {prevHash, String.valueOf(seq), auditId.toString(), occurredAt.toString(),
                service, action, entityType, entityId, actorId, actorUsername, String.join(",", actorRoles), channel,
                ipAddress, correlationId, oldValue, newValue, reason}) {
            String v = field == null ? "\u0000" : field;
            b.append(v.length()).append(':').append(v).append('|');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
