package gov.itms.hold.infrastructure;

import gov.itms.hold.application.HoldRepository;
import gov.itms.hold.domain.Actor;
import gov.itms.hold.domain.Channel;
import gov.itms.hold.domain.DomainException;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.HoldOverride;
import gov.itms.hold.domain.HoldStatus;
import gov.itms.hold.domain.Subject;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds in {@code hold}; the database guards the lifecycle (lifted once, nothing else changes, never deleted) and
 * writes the temporal history ({@code hold_history}) in the same transaction (V1). Overrides are rows of
 * {@code hold_override}, written once and never changed (V3).
 */
class JdbcHoldRepository implements HoldRepository {

    private static final String COLUMNS = """
            id, subject_type, subject_id, hold_type, module, source, source_ref, reason, start_date, end_date,
            blocked_services, liftable_by_roles, status, placed_at, placed_by, placed_by_username, placed_channel,
            lifted_at, lifted_by, lifted_by_username, lifted_channel, lift_reason, version""";
    private static final String SELECT = "select " + COLUMNS + " from hold";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcHoldRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(Hold hold) {
        Hold.Spec s = hold.spec();
        Hold.Change placed = hold.placed();
        try {
            jdbc.sql("""
                            insert into hold (id, subject_type, subject_id, hold_type, module, source, source_ref, reason,
                                start_date, end_date, blocked_services, liftable_by_roles, status, placed_at, placed_by,
                                placed_by_username, placed_channel, version)
                            values (:id, :subjectType, :subjectId, :holdType, :module, :source, :sourceRef, :reason,
                                :startDate, :endDate, string_to_array(:blocked, ','), string_to_array(:roles, ','),
                                :status, :placedAt, :placedBy, :placedByName, :placedChannel, :version)""")
                    .param("id", hold.id())
                    .param("subjectType", s.subject().type().name())
                    .param("subjectId", s.subject().id())
                    .param("holdType", s.holdType())
                    .param("module", s.module())
                    .param("source", s.source())
                    .param("sourceRef", s.sourceRef())
                    .param("reason", s.reason())
                    .param("startDate", Date.valueOf(hold.startDate()))
                    .param("endDate", s.endDate() == null ? null : Date.valueOf(s.endDate()))
                    .param("blocked", String.join(",", s.blockedServices()))
                    .param("roles", String.join(",", s.liftableByRoles()))
                    .param("status", hold.storedStatus().name())
                    .param("placedAt", Timestamp.from(placed.at()))
                    .param("placedBy", placed.actor().id())
                    .param("placedByName", placed.actor().username())
                    .param("placedChannel", placed.channel().name())
                    .param("version", hold.version())
                    .update();
        } catch (DuplicateKeyException e) {
            // uq_hold_source_ref: the source already placed a hold under this reference (CC-033), perhaps at the same
            // moment.
            throw new DomainException(DomainException.Kind.CONFLICT, "DUPLICATE_SOURCE_REF",
                    "source " + s.source() + " already placed a hold with reference " + s.sourceRef(),
                    List.of("CC-030", "CC-033"), s.source(), s.sourceRef());
        }
    }

    @Override
    public Optional<Hold> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<Hold> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<Hold> findBySubject(Subject subject) {
        return jdbc.sql(SELECT + " where subject_type = :type and subject_id = :id order by placed_at desc, id")
                .param("type", subject.type().name())
                .param("id", subject.id())
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public void saveLift(Hold hold) {
        Hold.Change lifted = hold.lifted();
        int updated = jdbc.sql("""
                        update hold set status = :status, lifted_at = :at, lifted_by = :by, lifted_by_username = :byName,
                            lifted_channel = :channel, lift_reason = :reason, version = :version
                        where id = :id and version = :previous""")
                .param("status", hold.storedStatus().name())
                .param("at", Timestamp.from(lifted.at()))
                .param("by", lifted.actor().id())
                .param("byName", lifted.actor().username())
                .param("channel", lifted.channel().name())
                .param("reason", lifted.reason())
                .param("version", hold.version())
                .param("id", hold.id())
                .param("previous", hold.version() - 1)
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("hold " + hold.id() + " changed in between");
        }
    }

    @Override
    public void insertOverride(HoldOverride o) {
        try {
            jdbc.sql("""
                            insert into hold_override (id, hold_id, transaction_reference, service_code, reason,
                                document_type, document_number, overridden_at, overridden_by, overridden_by_username,
                                overridden_channel)
                            values (:id, :holdId, :reference, :serviceCode, :reason, :documentType, :documentNumber,
                                :at, :by, :byName, :channel)""")
                    .param("id", o.id())
                    .param("holdId", o.holdId())
                    .param("reference", o.transactionReference())
                    .param("serviceCode", o.serviceCode())
                    .param("reason", o.reason())
                    .param("documentType", o.document().type())
                    .param("documentNumber", o.document().number())
                    .param("at", Timestamp.from(o.at()))
                    .param("by", o.actor().id())
                    .param("byName", o.actor().username())
                    .param("channel", o.channel().name())
                    .update();
        } catch (DuplicateKeyException e) {
            // uq_hold_override_transaction, perhaps by two requests at the same moment
            throw new DomainException(DomainException.Kind.CONFLICT, "OVERRIDE_EXISTS",
                    "this hold is already overridden for transaction " + o.transactionReference(), List.of("CC-034"),
                    o.transactionReference());
        }
    }

    @Override
    public List<HoldOverride> findOverrides(UUID holdId) {
        return jdbc.sql("""
                        select id, hold_id, transaction_reference, service_code, reason, document_type, document_number,
                            overridden_at, overridden_by, overridden_by_username, overridden_channel
                        from hold_override where hold_id = :holdId order by overridden_at, id""")
                .param("holdId", holdId)
                .query((rs, n) -> new HoldOverride(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("hold_id")), rs.getString("transaction_reference"),
                        rs.getString("service_code"), rs.getString("reason"),
                        new HoldOverride.Document(rs.getString("document_type"), rs.getString("document_number")),
                        new Actor(rs.getString("overridden_by"), rs.getString("overridden_by_username")),
                        Channel.valueOf(rs.getString("overridden_channel")),
                        rs.getTimestamp("overridden_at").toInstant()))
                .list();
    }

    @Override
    public Set<UUID> overriddenFor(String transactionReference) {
        return new LinkedHashSet<>(jdbc.sql("select hold_id from hold_override where transaction_reference = :reference")
                .param("reference", transactionReference)
                .query((rs, n) -> UUID.fromString(rs.getString("hold_id")))
                .list());
    }

    @Override
    public List<HoldVersion> history(UUID id) {
        return jdbc.sql("""
                        select row_image, valid_from, valid_to, changed_by from hold_history
                        where hold_id = :id order by version""")
                .param("id", id)
                .query((rs, n) -> new HoldVersion(fromImage(json.readTree(rs.getString("row_image"))),
                        rs.getTimestamp("valid_from").toInstant(),
                        rs.getTimestamp("valid_to") == null ? null : rs.getTimestamp("valid_to").toInstant(),
                        rs.getString("changed_by")))
                .list();
    }

    private static Hold map(ResultSet rs) throws SQLException {
        Hold.Spec spec = new Hold.Spec(
                new Subject(Subject.Type.valueOf(rs.getString("subject_type")), rs.getString("subject_id")),
                rs.getString("hold_type"), rs.getString("module"), rs.getString("source"), rs.getString("source_ref"),
                rs.getString("reason"), rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate(),
                strings(rs.getArray("blocked_services")), strings(rs.getArray("liftable_by_roles")));
        Hold.Change placed = new Hold.Change(new Actor(rs.getString("placed_by"), rs.getString("placed_by_username")),
                Channel.valueOf(rs.getString("placed_channel")), rs.getTimestamp("placed_at").toInstant(), null);
        Hold.Change lifted = rs.getTimestamp("lifted_at") == null ? null : new Hold.Change(
                new Actor(rs.getString("lifted_by"), rs.getString("lifted_by_username")),
                Channel.valueOf(rs.getString("lifted_channel")), rs.getTimestamp("lifted_at").toInstant(),
                rs.getString("lift_reason"));
        return Hold.restore(UUID.fromString(rs.getString("id")), spec, rs.getDate("start_date").toLocalDate(), placed,
                HoldStatus.valueOf(rs.getString("status")), lifted, rs.getLong("version"));
    }

    /** A history row image ({@code to_jsonb} of the hold row) back into a hold. */
    private static Hold fromImage(JsonNode r) {
        LocalDate start = LocalDate.parse(r.get("start_date").asString());
        Hold.Spec spec = new Hold.Spec(
                new Subject(Subject.Type.valueOf(r.get("subject_type").asString()), r.get("subject_id").asString()),
                r.get("hold_type").asString(), r.get("module").asString(), r.get("source").asString(),
                text(r, "source_ref"), r.get("reason").asString(), start,
                text(r, "end_date") == null ? null : LocalDate.parse(text(r, "end_date")),
                list(r.get("blocked_services")), list(r.get("liftable_by_roles")));
        Hold.Change placed = new Hold.Change(new Actor(r.get("placed_by").asString(), text(r, "placed_by_username")),
                Channel.valueOf(r.get("placed_channel").asString()), instant(text(r, "placed_at")), null);
        Hold.Change lifted = text(r, "lifted_at") == null ? null : new Hold.Change(
                new Actor(r.get("lifted_by").asString(), text(r, "lifted_by_username")),
                Channel.valueOf(r.get("lifted_channel").asString()), instant(text(r, "lifted_at")),
                text(r, "lift_reason"));
        return Hold.restore(UUID.fromString(r.get("id").asString()), spec, start, placed,
                HoldStatus.valueOf(r.get("status").asString()), lifted, r.get("version").asLong());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> list(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return List.copyOf(values);
    }

    /** {@code to_jsonb} writes timestamptz as ISO-8601 with an offset, e.g. {@code 2026-10-05T09:00:00.123+00:00}. */
    private static Instant instant(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
