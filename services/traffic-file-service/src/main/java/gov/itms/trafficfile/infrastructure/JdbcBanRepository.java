package gov.itms.trafficfile.infrastructure;

import gov.itms.trafficfile.application.BanRepository;
import gov.itms.trafficfile.domain.Actor;
import gov.itms.trafficfile.domain.BanEntry;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Identity;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The ban list in {@code ban_entry}; the database guards the lifecycle and writes the history (V4). */
class JdbcBanRepository implements BanRepository {

    private static final String SELECT = """
            select id, document_type, national_id_no, passport_no, passport_country, file_number, reason, source,
                source_ref, start_date, end_date, status, placed_at, placed_by, placed_by_username, placed_channel,
                lifted_at, lifted_by, lifted_by_username, lifted_channel, lift_reason, version
            from ban_entry""";

    private final JdbcClient jdbc;

    JdbcBanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(BanEntry ban) {
        BanEntry.Spec s = ban.spec();
        try {
            jdbc.sql("""
                            insert into ban_entry (id, document_type, national_id_no, passport_no, passport_country,
                                file_number, reason, source, source_ref, start_date, end_date, status, placed_at,
                                placed_by, placed_by_username, placed_channel, version)
                            values (:id, :type, :nationalId, :passportNo, :passportCountry, :fileNumber, :reason,
                                :source, :sourceRef, :start, :end, 'ACTIVE', :at, :by, :byName, :channel, :version)""")
                    .param("id", ban.id())
                    .param("type", s.person() instanceof Identity.NationalId ? "NATIONAL_ID" : "PASSPORT")
                    .param("nationalId", s.person() instanceof Identity.NationalId n ? n.number() : null)
                    .param("passportNo", s.person() instanceof Identity.Passport p ? p.number() : null)
                    .param("passportCountry", s.person() instanceof Identity.Passport p ? p.country() : null)
                    .param("fileNumber", ban.fileNumber())
                    .param("reason", s.reason())
                    .param("source", s.source())
                    .param("sourceRef", s.sourceRef())
                    .param("start", Date.valueOf(ban.startDate()))
                    .param("end", s.endDate() == null ? null : Date.valueOf(s.endDate()))
                    .param("at", Timestamp.from(ban.placed().at()))
                    .param("by", ban.placed().actor().id())
                    .param("byName", ban.placed().actor().username())
                    .param("channel", ban.placed().channel().name())
                    .param("version", ban.version())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateSourceRefException(e);
        }
    }

    @Override
    public Optional<BanEntry> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(JdbcBanRepository::map).optional();
    }

    @Override
    public Optional<BanEntry> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(JdbcBanRepository::map)
                .optional();
    }

    @Override
    public List<BanEntry> findByDocument(Identity.IdentityDocument document) {
        return switch (document) {
            case Identity.NationalId n -> jdbc.sql(SELECT + " where national_id_no = :n order by placed_at desc")
                    .param("n", n.number()).query(JdbcBanRepository::map).list();
            case Identity.Passport p -> jdbc.sql(SELECT
                            + " where passport_no = :n and passport_country = :c order by placed_at desc")
                    .param("n", p.number()).param("c", p.country()).query(JdbcBanRepository::map).list();
        };
    }

    @Override
    public void saveLift(BanEntry ban) {
        BanEntry.Change lifted = ban.lifted();
        int updated = jdbc.sql("""
                        update ban_entry set status = 'LIFTED', lifted_at = :at, lifted_by = :by,
                            lifted_by_username = :byName, lifted_channel = :channel, lift_reason = :reason,
                            version = :version
                        where id = :id and version = :previous""")
                .param("at", Timestamp.from(lifted.at()))
                .param("by", lifted.actor().id())
                .param("byName", lifted.actor().username())
                .param("channel", lifted.channel().name())
                .param("reason", lifted.reason())
                .param("version", ban.version())
                .param("previous", ban.version() - 1)
                .param("id", ban.id())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("ban entry " + ban.id() + " changed concurrently");
        }
    }

    private static BanEntry map(ResultSet rs, int row) throws SQLException {
        Identity.IdentityDocument person = "NATIONAL_ID".equals(rs.getString("document_type"))
                ? new Identity.NationalId(rs.getString("national_id_no"))
                : new Identity.Passport(rs.getString("passport_no"), rs.getString("passport_country"));
        Date end = rs.getDate("end_date");
        LocalDate start = rs.getDate("start_date").toLocalDate();
        BanEntry.Spec spec = new BanEntry.Spec(person, rs.getString("reason"), rs.getString("source"),
                rs.getString("source_ref"), start, end == null ? null : end.toLocalDate());
        BanEntry.Change placed = new BanEntry.Change(
                new Actor(rs.getString("placed_by"), rs.getString("placed_by_username")),
                Channel.valueOf(rs.getString("placed_channel")), rs.getTimestamp("placed_at").toInstant(),
                rs.getString("reason"));
        BanEntry.Change lifted = rs.getTimestamp("lifted_at") == null ? null : new BanEntry.Change(
                new Actor(rs.getString("lifted_by"), rs.getString("lifted_by_username")),
                Channel.valueOf(rs.getString("lifted_channel")), rs.getTimestamp("lifted_at").toInstant(),
                rs.getString("lift_reason"));
        return BanEntry.restore(rs.getObject("id", UUID.class), spec, start, rs.getString("file_number"), placed,
                lifted, rs.getLong("version"));
    }
}
