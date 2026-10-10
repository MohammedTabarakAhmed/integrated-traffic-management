package gov.itms.crossborder.infrastructure;

import gov.itms.crossborder.application.CrossborderService.Message;
import gov.itms.crossborder.application.CrossborderService.Partner;
import gov.itms.crossborder.application.Ports;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class JdbcExchangeRepository implements Ports.ExchangeRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String PARTNER_SELECT = "select code,name,exchange_mode,commission_basis_points,status,"
            + "endpoint_url,allows_accident,allows_permit,allows_licence from partner_state";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcExchangeRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- partners and mappings

    @Override
    public void savePartner(Partner p, String actor, Instant at) {
        jdbc.sql("""
                insert into partner_state (code, name, exchange_mode, commission_basis_points, status, endpoint_url,
                    allows_accident, allows_permit, allows_licence, updated_by, updated_at)
                values (:code, :name, :mode, :commission, :status, :endpoint, :accident, :permit, :licence, :actor, :at)
                on conflict (code) do update set name = excluded.name, exchange_mode = excluded.exchange_mode,
                    commission_basis_points = excluded.commission_basis_points, status = excluded.status,
                    endpoint_url = excluded.endpoint_url, allows_accident = excluded.allows_accident,
                    allows_permit = excluded.allows_permit, allows_licence = excluded.allows_licence,
                    updated_by = excluded.updated_by, updated_at = excluded.updated_at
                """).param("code", p.code()).param("name", p.name()).param("mode", p.mode())
                .param("commission", p.commissionBasisPoints()).param("status", p.status())
                .param("endpoint", p.endpointUrl()).param("accident", p.allowsAccident())
                .param("permit", p.allowsPermit()).param("licence", p.allowsLicence()).param("actor", actor)
                .param("at", Timestamp.from(at)).update();
    }

    @Override
    public Optional<Partner> partner(String code) {
        return jdbc.sql(PARTNER_SELECT + " where code = :code").param("code", code)
                .query(JdbcExchangeRepository::mapPartner).optional();
    }

    @Override
    public List<Partner> partners(String mode) {
        return jdbc.sql(PARTNER_SELECT + " where status = 'ACTIVE' and (cast(:mode as text) is null or exchange_mode = :mode)"
                        + " order by code").param("mode", mode).query(JdbcExchangeRepository::mapPartner).list();
    }

    @Override
    public void saveMapping(String partner, String domain, String local, String external, String actor, Instant at) {
        jdbc.sql("""
                insert into partner_code_mapping (partner_code, domain, local_code, external_code, updated_by, updated_at)
                values (:partner, :domain, :local, :external, :actor, :at)
                on conflict (partner_code, domain, local_code) do update set external_code = excluded.external_code,
                    updated_by = excluded.updated_by, updated_at = excluded.updated_at
                """).param("partner", partner).param("domain", domain).param("local", local)
                .param("external", external).param("actor", actor).param("at", Timestamp.from(at)).update();
    }

    @Override
    public Optional<String> localCode(String partner, String domain, String external) {
        return jdbc.sql("select local_code from partner_code_mapping where partner_code = :partner and domain = :domain"
                        + " and external_code = :external").param("partner", partner).param("domain", domain)
                .param("external", external).query(String.class).optional();
    }

    @Override
    public Optional<String> externalCode(String partner, String domain, String local) {
        return jdbc.sql("select external_code from partner_code_mapping where partner_code = :partner and domain = :domain"
                        + " and local_code = :local").param("partner", partner).param("domain", domain)
                .param("local", local).query(String.class).optional();
    }

    // ---- messages and history

    @Override
    public void insert(Message m, String actor) {
        jdbc.sql("""
                insert into exchange_message (id, partner_code, external_id, message_type, direction, status,
                    local_reference, external_code, mapped_code, vehicle_plate, owner_file, currency, amount_minor,
                    occurred_at, received_at, detail, related_message_id, payload)
                values (:id, :partner, :external, :type, :direction, :status, :local, :externalCode, :mapped, :plate,
                    :owner, :currency, :amount, :occurred, :received, :detail, :related, cast(:payload as jsonb))
                """).param("id", m.id()).param("partner", m.partner()).param("external", m.externalId())
                .param("type", m.type()).param("direction", m.direction()).param("status", m.status())
                .param("local", m.localReference()).param("externalCode", m.externalCode())
                .param("mapped", m.mappedCode()).param("plate", m.vehiclePlate()).param("owner", m.ownerFile())
                .param("currency", m.currency()).param("amount", m.amountMinor())
                .param("occurred", Timestamp.from(m.occurredAt())).param("received", Timestamp.from(m.receivedAt()))
                .param("detail", m.detail()).param("related", m.relatedMessageId())
                .param("payload", m.payload() == null ? null : json.writeValueAsString(m.payload())).update();
        history(m.id(), null, m.status(), m.detail(), actor, m.receivedAt());
    }

    @Override
    public Optional<Message> message(UUID id) {
        return jdbc.sql("select * from exchange_message where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Message> message(String partner, String externalId) {
        return jdbc.sql("select * from exchange_message where partner_code = :partner and external_id = :external")
                .param("partner", partner).param("external", externalId).query(this::map).optional();
    }

    @Override
    public Optional<Message> violationByLocalReference(String localReference) {
        return jdbc.sql("select * from exchange_message where local_reference = :ref and message_type = 'VIOLATION'"
                        + " order by received_at desc limit 1").param("ref", localReference).query(this::map)
                .optional();
    }

    @Override
    public List<Message> messages(String partner, String type, String status, int limit) {
        return jdbc.sql("""
                select * from exchange_message where (cast(:partner as text) is null or partner_code = :partner)
                    and (cast(:type as text) is null or message_type = :type) and (cast(:status as text) is null or status = :status)
                order by received_at desc limit :n
                """).param("partner", partner).param("type", type).param("status", status).param("n", limit)
                .query(this::map).list();
    }

    @Override
    public List<Ports.History> history(UUID messageId) {
        return jdbc.sql("select from_status, to_status, detail, changed_by, changed_at from exchange_message_history"
                        + " where message_id = :id order by id").param("id", messageId)
                .query((rs, row) -> new Ports.History(rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("detail"), rs.getString("changed_by"), instant(rs, "changed_at"))).list();
    }

    @Override
    public void mark(UUID id, String fromStatus, String status, Instant at, String detail, String actor) {
        int updated = jdbc.sql("update exchange_message set status = :status, detail = :detail, updated_at = :at"
                        + " where id = :id and status = :from").param("status", status).param("detail", cut(detail))
                .param("at", Timestamp.from(at)).param("id", id).param("from", fromStatus).update();
        if (updated == 1) {
            history(id, fromStatus, status, detail, actor, at);
        }
    }

    @Override
    public void setLocalReference(UUID id, String localReference, Instant at) {
        jdbc.sql("update exchange_message set local_reference = :ref, updated_at = :at where id = :id"
                        + " and local_reference is null").param("ref", localReference).param("at", Timestamp.from(at))
                .param("id", id).update();
    }

    @Override
    public void markPaid(UUID id, String fromStatus, String paidIn, String paymentReference, int commissionBasisPoints,
                         Instant at, String actor) {
        int updated = jdbc.sql("""
                update exchange_message set status = 'PAID', paid_at = :at, paid_in = :paidIn,
                    payment_reference = :ref, commission_basis_points = :bps, updated_at = :at
                where id = :id and status = :from and paid_at is null
                """).param("at", Timestamp.from(at)).param("paidIn", paidIn).param("ref", paymentReference)
                .param("bps", commissionBasisPoints).param("id", id).param("from", fromStatus).update();
        if (updated == 1) {
            history(id, fromStatus, "PAID", "paid " + ("HERE".equals(paidIn) ? "here" : "in partner state") + ": "
                    + paymentReference, actor, at);
        }
    }

    private void history(UUID id, String from, String to, String detail, String actor, Instant at) {
        jdbc.sql("insert into exchange_message_history (message_id, from_status, to_status, detail, changed_by,"
                        + " changed_at) values (:id, :from, :to, :detail, :by, :at)").param("id", id)
                .param("from", from).param("to", to).param("detail", cut(detail)).param("by", actor)
                .param("at", Timestamp.from(at)).update();
    }

    // ---- delivery

    @Override
    public void createDelivery(UUID messageId, Instant dueAt) {
        jdbc.sql("insert into exchange_delivery (message_id, next_attempt_at) values (:id, :due)")
                .param("id", messageId).param("due", Timestamp.from(dueAt)).update();
    }

    @Override
    public List<Message> due(String partner, Instant from, Instant to, Instant dueAt, int limit) {
        return jdbc.sql("""
                select m.* from exchange_message m join exchange_delivery d on d.message_id = m.id
                where m.partner_code = :partner and m.direction = 'OUTBOUND' and d.status = 'PENDING'
                    and d.next_attempt_at <= :due
                    and (cast(:from as timestamptz) is null or m.occurred_at >= :from)
                    and (cast(:to as timestamptz) is null or m.occurred_at < :to)
                order by m.received_at limit :n
                """).param("partner", partner).param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to)).param("due", Timestamp.from(dueAt))
                .param("n", limit).query(this::map).list();
    }

    @Override
    public int claimDelivery(UUID messageId, Instant at, long retryBaseSeconds, long retryMaxSeconds,
                             int maxAttempts) {
        return jdbc.sql("""
                update exchange_delivery set attempt_count = attempt_count + 1, last_attempt_at = :at,
                    next_attempt_at = :at + make_interval(secs => least(:max, :base * power(2, attempt_count)))
                where message_id = :id and status = 'PENDING' and next_attempt_at <= :at and attempt_count < :attempts
                """).param("at", Timestamp.from(at)).param("base", retryBaseSeconds).param("max", retryMaxSeconds)
                .param("id", messageId).param("attempts", maxAttempts).update();
    }

    @Override
    public String acknowledgeDelivery(UUID messageId, boolean accepted, boolean permanent, String response,
                                      String partnerReference, Instant at, int maxAttempts) {
        return jdbc.sql("""
                update exchange_delivery set
                    status = case when :accepted then 'ACKNOWLEDGED'
                                  when :permanent or attempt_count >= :attempts then 'FAILED' else 'PENDING' end,
                    acknowledged_at = case when :accepted then :at else null end,
                    last_response = :response, partner_reference = :partnerRef
                where message_id = :id and status = 'PENDING' returning status
                """).param("accepted", accepted).param("permanent", permanent).param("attempts", maxAttempts)
                .param("at", Timestamp.from(at)).param("response", response).param("partnerRef", partnerReference)
                .param("id", messageId).query(String.class).optional().orElse(null);
    }

    // ---- settlement

    @Override
    public List<Message> unsettled(String partner, Instant from, Instant to) {
        // Money moves only when a state collects the other's fine: received violations paid here, and violations
        // sent from here paid in the partner state.
        return jdbc.sql("""
                select m.* from exchange_message m
                where m.partner_code = :partner and m.message_type = 'VIOLATION' and m.paid_at >= :from
                    and m.paid_at < :to
                    and ((m.direction = 'INBOUND' and m.paid_in = 'HERE')
                         or (m.direction = 'OUTBOUND' and m.paid_in = 'PARTNER'))
                    and not exists (select 1 from settlement_line l where l.message_id = m.id)
                order by m.paid_at
                """).param("partner", partner).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query(this::map).list();
    }

    @Override
    public void insertStatement(Ports.Statement s, List<Ports.StatementLine> lines) {
        jdbc.sql("""
                insert into settlement_statement (id, partner_code, period_from, period_to, currency,
                    collected_here_minor, commission_here_minor, owed_to_partner_minor, collected_by_partner_minor,
                    commission_partner_minor, owed_by_partner_minor, net_minor, line_count, generated_by, generated_at)
                values (:id, :partner, :from, :to, :currency, :here, :hereC, :owedTo, :there, :thereC, :owedBy, :net,
                    :count, :by, :at)
                """).param("id", s.id()).param("partner", s.partner()).param("from", Timestamp.from(s.from()))
                .param("to", Timestamp.from(s.to())).param("currency", s.currency())
                .param("here", s.collectedHereMinor()).param("hereC", s.commissionHereMinor())
                .param("owedTo", s.owedToPartnerMinor()).param("there", s.collectedByPartnerMinor())
                .param("thereC", s.commissionPartnerMinor()).param("owedBy", s.owedByPartnerMinor())
                .param("net", s.netMinor()).param("count", s.lineCount()).param("by", s.generatedBy())
                .param("at", Timestamp.from(s.generatedAt())).update();
        for (Ports.StatementLine l : lines) {
            jdbc.sql("""
                    insert into settlement_line (statement_id, message_id, paid_in, amount_minor,
                        commission_basis_points, commission_minor)
                    values (:statement, :message, :paidIn, :amount, :bps, :commission)
                    """).param("statement", s.id()).param("message", l.messageId()).param("paidIn", l.paidIn())
                    .param("amount", l.amountMinor()).param("bps", l.commissionBasisPoints())
                    .param("commission", l.commissionMinor()).update();
        }
    }

    @Override
    public Optional<Ports.Statement> statement(UUID id) {
        return jdbc.sql("select * from settlement_statement where id = :id").param("id", id)
                .query(JdbcExchangeRepository::mapStatement).optional();
    }

    @Override
    public List<Ports.StatementLine> statementLines(UUID id) {
        return jdbc.sql("select * from settlement_line where statement_id = :id order by message_id").param("id", id)
                .query((rs, row) -> new Ports.StatementLine(rs.getObject("message_id", UUID.class),
                        rs.getString("paid_in"), rs.getLong("amount_minor"), rs.getInt("commission_basis_points"),
                        rs.getLong("commission_minor"))).list();
    }

    @Override
    public List<Ports.Statement> statements(String partner, int limit) {
        return jdbc.sql("select * from settlement_statement where partner_code = :partner"
                        + " order by period_from desc, currency limit :n").param("partner", partner).param("n", limit)
                .query(JdbcExchangeRepository::mapStatement).list();
    }

    // ---- maker-checker proposals

    @Override
    public void insertProposal(Ports.Proposal p) {
        jdbc.sql("""
                insert into config_proposal (id, kind, subject, payload, status, proposed_by, proposed_at, reason)
                values (:id, :kind, :subject, cast(:payload as jsonb), 'PROPOSED', :by, :at, :reason)
                """).param("id", p.id()).param("kind", p.kind()).param("subject", p.subject())
                .param("payload", json.writeValueAsString(p.payload())).param("by", p.proposedBy())
                .param("at", Timestamp.from(p.proposedAt())).param("reason", p.reason()).update();
    }

    @Override
    public Optional<Ports.Proposal> proposalForUpdate(UUID id) {
        return jdbc.sql("select * from config_proposal where id = :id for update").param("id", id)
                .query(this::mapProposal).optional();
    }

    @Override
    public List<Ports.Proposal> openProposals(int limit) {
        return jdbc.sql("select * from config_proposal where status = 'PROPOSED' order by proposed_at limit :n")
                .param("n", limit).query(this::mapProposal).list();
    }

    @Override
    public void decideProposal(UUID id, String status, String actor, Instant at, String reason) {
        jdbc.sql("update config_proposal set status = :status, decided_by = :by, decided_at = :at,"
                        + " decision_reason = :reason where id = :id and status = 'PROPOSED'")
                .param("status", status).param("by", actor).param("at", Timestamp.from(at)).param("reason", reason)
                .param("id", id).update();
    }

    // ---- mapping

    private static Partner mapPartner(ResultSet rs, int row) throws SQLException {
        return new Partner(rs.getString("code"), rs.getString("name"), rs.getString("exchange_mode"),
                rs.getInt("commission_basis_points"), rs.getString("status"), rs.getString("endpoint_url"),
                rs.getBoolean("allows_accident"), rs.getBoolean("allows_permit"), rs.getBoolean("allows_licence"));
    }

    private Message map(ResultSet r, int row) throws SQLException {
        String payload = r.getString("payload");
        int bps = r.getInt("commission_basis_points");
        Integer commission = r.wasNull() ? null : bps;
        return new Message(r.getObject("id", UUID.class), r.getString("partner_code"), r.getString("external_id"),
                r.getString("message_type"), r.getString("direction"), r.getString("status"),
                r.getString("local_reference"), r.getString("external_code"), r.getString("mapped_code"),
                r.getString("vehicle_plate"), r.getString("owner_file"), r.getString("currency"),
                r.getLong("amount_minor"), instant(r, "occurred_at"), instant(r, "received_at"), r.getString("detail"),
                r.getObject("related_message_id", UUID.class), payload == null ? null : json.readValue(payload, MAP),
                instant(r, "paid_at"), r.getString("paid_in"), r.getString("payment_reference"), commission);
    }

    private static Ports.Statement mapStatement(ResultSet r, int row) throws SQLException {
        return new Ports.Statement(r.getObject("id", UUID.class), r.getString("partner_code"),
                instant(r, "period_from"), instant(r, "period_to"), r.getString("currency"),
                r.getLong("collected_here_minor"), r.getLong("commission_here_minor"),
                r.getLong("owed_to_partner_minor"), r.getLong("collected_by_partner_minor"),
                r.getLong("commission_partner_minor"), r.getLong("owed_by_partner_minor"), r.getLong("net_minor"),
                r.getInt("line_count"), r.getString("generated_by"), instant(r, "generated_at"));
    }

    private Ports.Proposal mapProposal(ResultSet r, int row) throws SQLException {
        return new Ports.Proposal(r.getObject("id", UUID.class), r.getString("kind"), r.getString("subject"),
                json.readValue(r.getString("payload"), MAP), r.getString("status"), r.getString("proposed_by"),
                instant(r, "proposed_at"), r.getString("reason"), r.getString("decided_by"),
                instant(r, "decided_at"), r.getString("decision_reason"));
    }

    private static Instant instant(ResultSet r, String col) throws SQLException {
        Timestamp t = r.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String cut(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
