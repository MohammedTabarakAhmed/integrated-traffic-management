package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.LicenceNumberRepository;
import gov.itms.licensing.domain.Actor;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.LicenceClassVersion;
import gov.itms.licensing.domain.LicenceNumberRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Licence number ranges and used numbers in PostgreSQL; the database guards them (V3). */
class JdbcLicenceNumberRepository implements LicenceNumberRepository {

    private static final String SELECT = """
            select id, region, prefix, width, first_number, last_number, next_number, status, submitted_at,
                submitted_by, submitted_by_username, submitted_channel, submit_reason, decided_at, decided_by,
                decided_by_username, decided_channel, decision_reason
            from licence_number_range""";

    private final JdbcClient jdbc;

    JdbcLicenceNumberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockPrefix(String prefix) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('licence-number-prefix:' || :p))").param("p", prefix).query()
                .listOfRows();
    }

    @Override
    public void lockRegion(String region) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('licence-number-region:' || :r))").param("r", region).query()
                .listOfRows();
    }

    @Override
    public void insert(LicenceNumberRange r) {
        LicenceNumberRange.Spec s = r.spec();
        jdbc.sql("""
                        insert into licence_number_range (id, region, prefix, width, first_number, last_number,
                            next_number, status, submitted_at, submitted_by, submitted_by_username, submitted_channel,
                            submit_reason)
                        values (:id, :region, :prefix, :width, :first, :last, :next, 'PENDING', :at, :by, :byName,
                            :channel, :reason)""")
                .param("id", r.id())
                .param("region", s.region())
                .param("prefix", s.prefix())
                .param("width", s.width())
                .param("first", s.firstNumber())
                .param("last", s.lastNumber())
                .param("next", r.nextNumber())
                .param("at", Timestamp.from(r.submitted().at()))
                .param("by", r.submitted().actor().id())
                .param("byName", r.submitted().actor().username())
                .param("channel", r.submitted().channel().name())
                .param("reason", r.submitted().reason())
                .update();
    }

    @Override
    public Optional<LicenceNumberRange> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<LicenceNumberRange> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public List<LicenceNumberRange> openWithPrefix(String prefix) {
        return jdbc.sql(SELECT + " where prefix = :p and status in ('PENDING', 'ACTIVE')").param("p", prefix)
                .query(this::map).list();
    }

    @Override
    public List<LicenceNumberRange> byRegion(String region) {
        return jdbc.sql(SELECT + " where (cast(:r as varchar) is null or region = :r) order by region, submitted_at")
                .param("r", region).query(this::map).list();
    }

    @Override
    public List<LicenceNumberRange> activeInRegion(String region) {
        return jdbc.sql(SELECT + " where region = :r and status = 'ACTIVE' and next_number <= last_number"
                        + " order by decided_at, first_number")
                .param("r", region).query(this::map).list();
    }

    @Override
    public void saveDecision(LicenceNumberRange r) {
        LicenceClassVersion.Decision d = r.decided();
        int updated = jdbc.sql("""
                        update licence_number_range set status = :status, decided_at = :at, decided_by = :by,
                            decided_by_username = :byName, decided_channel = :channel, decision_reason = :reason
                        where id = :id and status = 'PENDING'""")
                .param("status", r.status().name())
                .param("at", Timestamp.from(d.at()))
                .param("by", d.actor().id())
                .param("byName", d.actor().username())
                .param("channel", d.channel().name())
                .param("reason", d.reason())
                .param("id", r.id())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("licence number range " + r.id() + " was decided concurrently");
        }
    }

    @Override
    public void saveNext(LicenceNumberRange r) {
        jdbc.sql("update licence_number_range set next_number = :next where id = :id")
                .param("next", r.nextNumber()).param("id", r.id()).update();
    }

    @Override
    public void insertUsed(UsedNumber u) {
        jdbc.sql("""
                        insert into licence_number (licence_number, range_id, region, number, allocated_at,
                            allocated_to)
                        values (:display, :range, :region, :number, :at, :to)""")
                .param("display", u.licenceNumber())
                .param("range", u.rangeId())
                .param("region", u.region())
                .param("number", u.number())
                .param("at", Timestamp.from(u.allocatedAt()))
                .param("to", u.allocatedTo())
                .update();
    }

    @Override
    public List<UsedNumber> usedInRange(UUID rangeId, int offset, int limit) {
        return jdbc.sql("""
                        select licence_number, range_id, region, number, allocated_at, allocated_to
                        from licence_number where range_id = :r order by number offset :o limit :l""")
                .param("r", rangeId).param("o", offset).param("l", limit)
                .query((rs, n) -> new UsedNumber(rs.getString("licence_number"), rs.getObject("range_id", UUID.class),
                        rs.getString("region"), rs.getLong("number"), rs.getTimestamp("allocated_at").toInstant(),
                        rs.getString("allocated_to")))
                .list();
    }

    private LicenceNumberRange map(ResultSet rs, int row) throws SQLException {
        LicenceNumberRange.Spec spec = new LicenceNumberRange.Spec(rs.getString("region"), rs.getString("prefix"),
                rs.getInt("width"), rs.getLong("first_number"), rs.getLong("last_number"));
        LicenceClassVersion.Decision submitted = new LicenceClassVersion.Decision(
                new Actor(rs.getString("submitted_by"), rs.getString("submitted_by_username")),
                Channel.valueOf(rs.getString("submitted_channel")), rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("submit_reason"));
        LicenceClassVersion.Decision decided = rs.getTimestamp("decided_at") == null ? null
                : new LicenceClassVersion.Decision(
                        new Actor(rs.getString("decided_by"), rs.getString("decided_by_username")),
                        Channel.valueOf(rs.getString("decided_channel")), rs.getTimestamp("decided_at").toInstant(),
                        rs.getString("decision_reason"));
        return LicenceNumberRange.restore(rs.getObject("id", UUID.class), spec, submitted,
                LicenceNumberRange.Status.valueOf(rs.getString("status")), decided, rs.getLong("next_number"));
    }
}
