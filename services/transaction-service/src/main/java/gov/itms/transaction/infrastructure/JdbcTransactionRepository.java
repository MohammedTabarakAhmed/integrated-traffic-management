package gov.itms.transaction.infrastructure;

import gov.itms.transaction.application.TransactionRepository;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.ChangeChannel;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.StatusReason;
import gov.itms.transaction.domain.Subject;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionCursor;
import gov.itms.transaction.domain.TransactionReference;
import gov.itms.transaction.domain.TransactionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Transactions in {@code service_transaction}; the database writes the status history ({@code
 * transaction_status_history}) from the status columns in the same transaction and enforces the lifecycle (V2).
 */
class JdbcTransactionRepository implements TransactionRepository {

    private static final String SELECT = """
            select id, reference, service_code, region, reference_year, reference_sequence, service_version, module,
                   fee_code, allows_concurrent_open, subject_type, subject_id, applicant_file_number, home_region,
                   channel, created_at, created_by, created_by_username, submitted_at, status, status_reason,
                   status_note, status_actor_id, status_actor_username, status_channel, status_changed_at, version,
                   (select h.previous_status from transaction_status_history h
                     where h.transaction_id = t.id and h.sequence = t.version) as previous_status
              from service_transaction t""";

    private final JdbcClient jdbc;

    JdbcTransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextSequence(String serviceCode, String region, int year) {
        return jdbc.sql("""
                        insert into transaction_reference_sequence (service_code, region, year, last_value)
                        values (:code, :region, :year, 1)
                        on conflict (service_code, region, year)
                        do update set last_value = transaction_reference_sequence.last_value + 1
                        returning last_value""")
                .param("code", serviceCode)
                .param("region", region)
                .param("year", year)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Transaction> findOpen(String serviceCode, Subject subject) {
        return jdbc.sql(SELECT + " where t.service_code = :code and t.subject_type = :type and t.subject_id = :id"
                        + " and t.status not in ('COMPLETED', 'REJECTED', 'CANCELLED', 'EXPIRED')"
                        + " order by t.created_at limit 1")
                .param("code", serviceCode)
                .param("type", subject.type().name())
                .param("id", subject.id())
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public void insert(Transaction t) {
        StatusChange c = t.lastChange();
        try {
            jdbc.sql("""
                            insert into service_transaction (id, reference, service_code, reference_year,
                                reference_sequence, service_version, module, fee_code, payment_required,
                                allows_concurrent_open, subject_type, subject_id, applicant_file_number, home_region,
                                region, channel, created_at, created_by, created_by_username, submitted_at, status,
                                status_reason, status_note, status_actor_id, status_actor_username, status_channel,
                                status_changed_at, version)
                            values (:id, :reference, :code, :year, :sequence, :serviceVersion, :module, :fee,
                                :paymentRequired, :concurrent, :subjectType, :subjectId, :applicant, :homeRegion,
                                :region, :channel, :createdAt, :createdBy, :createdByName, :submittedAt, :status,
                                :reason, :note, :actor, :actorName, :statusChannel, :changedAt, :version)""")
                    .param("id", t.id())
                    .param("reference", t.reference().toString())
                    .param("code", t.serviceCode())
                    .param("year", t.reference().year())
                    .param("sequence", t.reference().sequence())
                    .param("serviceVersion", t.serviceVersion())
                    .param("module", t.module())
                    .param("fee", t.feeCode())
                    .param("paymentRequired", t.paymentRequired())
                    .param("concurrent", t.allowsConcurrentOpen())
                    .param("subjectType", t.subject().type().name())
                    .param("subjectId", t.subject().id())
                    .param("applicant", t.applicantFileNumber())
                    .param("homeRegion", t.homeRegion())
                    .param("region", t.region())
                    .param("channel", t.channel().name())
                    .param("createdAt", Timestamp.from(t.createdAt()))
                    .param("createdBy", t.createdBy().id())
                    .param("createdByName", t.createdBy().username())
                    .param("submittedAt", timestamp(t.submittedAt()))
                    .param("status", c.status().name())
                    .param("reason", c.reason().name())
                    .param("note", c.note())
                    .param("actor", c.actor().id())
                    .param("actorName", c.actor().username())
                    .param("statusChannel", c.channel().name())
                    .param("changedAt", Timestamp.from(c.changedAt()))
                    .param("version", c.sequence())
                    .update();
        } catch (DuplicateKeyException e) {
            // uq_transaction_open_subject: another open transaction of this service for the subject was opened at the
            // same moment (CC-027).
            throw new DomainException(DomainException.Kind.CONFLICT, "DUPLICATE_OPEN_TRANSACTION",
                    "the subject already has an open transaction of service " + t.serviceCode(), List.of("CC-027"),
                    t.serviceCode(), "");
        }
    }

    @Override
    public Optional<Transaction> find(TransactionReference reference) {
        return jdbc.sql(SELECT + " where t.reference = :reference").param("reference", reference.toString())
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<Transaction> findForUpdate(TransactionReference reference) {
        return jdbc.sql(SELECT + " where t.reference = :reference for update of t")
                .param("reference", reference.toString())
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<Transaction> lockDraftsOpenedBy(Instant openedBy, int limit) {
        // ix_transaction_draft_created (V4) holds only Drafts, oldest first.
        return jdbc.sql(SELECT + " where t.status = 'DRAFT' and t.created_at <= :openedBy"
                        + " order by t.created_at limit :limit for update of t skip locked")
                .param("openedBy", Timestamp.from(openedBy))
                .param("limit", limit)
                .query((rs, n) -> map(rs)).list();
    }

    @Override
    public void saveStatus(Transaction t) {
        StatusChange c = t.lastChange();
        int updated = jdbc.sql("""
                        update service_transaction
                           set status = :status, status_reason = :reason, status_note = :note,
                               status_actor_id = :actor, status_actor_username = :actorName,
                               status_channel = :channel, status_changed_at = :changedAt, version = :version,
                               submitted_at = :submittedAt
                         where id = :id and version = :previousVersion and status = :previousStatus""")
                .param("status", c.status().name())
                .param("reason", c.reason().name())
                .param("note", c.note())
                .param("actor", c.actor().id())
                .param("actorName", c.actor().username())
                .param("channel", c.channel().name())
                .param("changedAt", Timestamp.from(c.changedAt()))
                .param("version", c.sequence())
                .param("submittedAt", timestamp(t.submittedAt()))
                .param("id", t.id())
                .param("previousVersion", c.sequence() - 1)
                .param("previousStatus", c.previousStatus().name())
                .update();
        if (updated != 1) {
            throw new DomainException(DomainException.Kind.CONFLICT, "TRANSACTION_CHANGED",
                    "the transaction was changed by someone else; reload it and try again", List.of("CC-022"));
        }
    }

    @Override
    public List<Transaction> findByApplicant(String applicantFileNumber, TransactionStatus status,
                                             TransactionCursor after, int limit) {
        // Row comparison on (created_at, id) follows ix_transaction_applicant (V3), newest first.
        String sql = SELECT + " where t.applicant_file_number = :applicant"
                + (status == null ? "" : " and t.status = :status")
                + (after == null ? "" : " and (t.created_at, t.id) < (:afterAt, :afterId)")
                + " order by t.created_at desc, t.id desc limit :limit";
        var query = jdbc.sql(sql).param("applicant", applicantFileNumber).param("limit", limit);
        if (status != null) {
            query = query.param("status", status.name());
        }
        if (after != null) {
            query = query.param("afterAt", Timestamp.from(after.createdAt())).param("afterId", after.id());
        }
        return query.query((rs, n) -> map(rs)).list();
    }

    @Override
    public List<StatusChange> history(UUID transactionId) {
        return jdbc.sql("""
                        select sequence, previous_status, status, reason, note, actor_id, actor_username, channel,
                               changed_at
                          from transaction_status_history
                         where transaction_id = :id
                         order by sequence""")
                .param("id", transactionId)
                .query((rs, n) -> new StatusChange(rs.getLong("sequence"), status(rs.getString("previous_status")),
                        TransactionStatus.valueOf(rs.getString("status")), StatusReason.valueOf(rs.getString("reason")),
                        rs.getString("note"), new Actor(rs.getString("actor_id"), rs.getString("actor_username")),
                        ChangeChannel.valueOf(rs.getString("channel")), rs.getTimestamp("changed_at").toInstant()))
                .list();
    }

    private static Transaction map(ResultSet rs) throws SQLException {
        TransactionReference reference = new TransactionReference(rs.getString("service_code"),
                rs.getString("region"), rs.getInt("reference_year"), rs.getLong("reference_sequence"));
        StatusChange last = new StatusChange(rs.getLong("version"), status(rs.getString("previous_status")),
                TransactionStatus.valueOf(rs.getString("status")), StatusReason.valueOf(rs.getString("status_reason")),
                rs.getString("status_note"),
                new Actor(rs.getString("status_actor_id"), rs.getString("status_actor_username")),
                ChangeChannel.valueOf(rs.getString("status_channel")),
                rs.getTimestamp("status_changed_at").toInstant());
        return new Transaction(rs.getObject("id", UUID.class), reference, rs.getLong("service_version"),
                rs.getString("module"), rs.getString("fee_code"), rs.getBoolean("allows_concurrent_open"),
                new Subject(Subject.Type.valueOf(rs.getString("subject_type")), rs.getString("subject_id")),
                rs.getString("applicant_file_number"), rs.getString("home_region"),
                Channel.valueOf(rs.getString("channel")), rs.getTimestamp("created_at").toInstant(),
                new Actor(rs.getString("created_by"), rs.getString("created_by_username")),
                instant(rs.getTimestamp("submitted_at")), last);
    }

    private static TransactionStatus status(String value) {
        return value == null ? null : TransactionStatus.valueOf(value);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
