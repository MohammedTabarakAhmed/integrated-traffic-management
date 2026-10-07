package gov.itms.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-122")
public class AuditRecordTest {

    static final Instant AT = Instant.parse("2026-10-06T08:00:00Z");

    public static AuditRecord record(UUID id, String entityId) {
        return new AuditRecord(0, id, AT, "vehicle-service", "VEHICLE_REGISTERED", "Vehicle", entityId, "u-officer",
                "officer", List.of("VEHICLE_OFFICER"), "BACKOFFICE", "10.0.0.1", "corr-1", null,
                "{\"status\":\"ACTIVE\"}", "first registration", null, null, null);
    }

    @Test
    @DisplayName("CC-122: the hash is stable for the same record and link, and links to the previous hash")
    void stableHash() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AuditRecord a = record(id, "VIN-1").chained(1, AuditRecord.GENESIS, AT);
        AuditRecord b = record(id, "VIN-1").chained(1, AuditRecord.GENESIS, AT.plusSeconds(5));
        assertThat(a.hash()).hasSize(64).isEqualTo(b.hash()).as("the receive time is not part of the hash");
        assertThat(a.computeHash()).isEqualTo(a.hash());
        assertThat(a.prevHash()).isEqualTo(AuditRecord.GENESIS);
        AuditRecord next = record(UUID.randomUUID(), "VIN-1").chained(2, a.hash(), AT);
        assertThat(next.prevHash()).isEqualTo(a.hash());
        assertThat(record(id, "VIN-1").chained(1, "f".repeat(64), AT).hash()).as("another previous link")
                .isNotEqualTo(a.hash());
        assertThat(record(id, "VIN-1").chained(2, AuditRecord.GENESIS, AT).hash()).as("another position")
                .isNotEqualTo(a.hash());
    }

    @Test
    @DisplayName("CC-122: changing any hashed field breaks the hash; null and empty and field shifts differ")
    void anyFieldChangeBreaksIt() {
        AuditRecord a = record(UUID.randomUUID(), "VIN-1").chained(7, AuditRecord.GENESIS, AT);
        List<UnaryOperator<AuditRecord>> edits = new ArrayList<>();
        edits.add(r -> copy(r, 0, "x"));
        edits.add(r -> copy(r, 1, "x"));
        edits.add(r -> copy(r, 2, "x"));
        edits.add(r -> copy(r, 3, "x"));
        edits.add(r -> copy(r, 4, "u-other"));
        edits.add(r -> copy(r, 5, null));
        edits.add(r -> copy(r, 6, "PORTAL"));
        edits.add(r -> copy(r, 7, "10.0.0.2"));
        edits.add(r -> copy(r, 8, "corr-2"));
        edits.add(r -> copy(r, 9, ""));
        edits.add(r -> copy(r, 10, "{\"status\":\"STOLEN\"}"));
        edits.add(r -> copy(r, 11, "other reason"));
        edits.add(r -> new AuditRecord(r.seq(), r.auditId(), r.occurredAt().plusMillis(1), r.service(), r.action(),
                r.entityType(), r.entityId(), r.actorId(), r.actorUsername(), r.actorRoles(), r.channel(),
                r.ipAddress(), r.correlationId(), r.oldValue(), r.newValue(), r.reason(), r.receivedAt(),
                r.prevHash(), r.hash()));
        edits.add(r -> new AuditRecord(r.seq(), r.auditId(), r.occurredAt(), r.service(), r.action(), r.entityType(),
                r.entityId(), r.actorId(), r.actorUsername(), List.of("ADMIN"), r.channel(), r.ipAddress(),
                r.correlationId(), r.oldValue(), r.newValue(), r.reason(), r.receivedAt(), r.prevHash(), r.hash()));
        for (UnaryOperator<AuditRecord> edit : edits) {
            AuditRecord tampered = edit.apply(a);
            assertThat(tampered.computeHash()).as("edited record still claims %s", a.hash()).isNotEqualTo(a.hash());
        }
        // Length prefixes: moving characters between neighbouring fields changes the hash.
        AuditRecord left = copy(copy(a, 2, "VehicleV"), 3, "IN-1");
        AuditRecord right = copy(copy(a, 2, "Vehicle"), 3, "VIN-1");
        assertThat(left.computeHash()).isNotEqualTo(right.computeHash());
    }

    /** Field index: 0 service, 1 action, 2 entityType, 3 entityId, 4 actorId, 5 actorUsername, 6 channel, 7 ip,
     * 8 correlation, 9 oldValue, 10 newValue, 11 reason. */
    private static AuditRecord copy(AuditRecord r, int field, String v) {
        String[] f = {r.service(), r.action(), r.entityType(), r.entityId(), r.actorId(), r.actorUsername(),
                r.channel(), r.ipAddress(), r.correlationId(), r.oldValue(), r.newValue(), r.reason()};
        f[field] = v;
        return new AuditRecord(r.seq(), r.auditId(), r.occurredAt(), f[0], f[1], f[2], f[3], f[4], f[5],
                r.actorRoles(), f[6], f[7], f[8], f[9], f[10], f[11], r.receivedAt(), r.prevHash(), r.hash());
    }
}
