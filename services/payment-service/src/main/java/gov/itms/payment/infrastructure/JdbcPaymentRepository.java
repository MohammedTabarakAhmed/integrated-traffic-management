package gov.itms.payment.infrastructure;

import gov.itms.payment.application.Ports;
import gov.itms.payment.domain.Basket;
import gov.itms.payment.domain.DomainException;
import gov.itms.payment.domain.Payable;
import gov.itms.payment.domain.Payment;
import gov.itms.payment.domain.Receipt;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Payments in PostgreSQL; guards keep payments, receipts and the callback log tamper-evident (V1). */
class JdbcPaymentRepository implements Ports.PaymentRepository {

    private static final String PAYABLE_SELECT = """
            select id, item_type, item_ref, traffic_file_number, region, currency, lines::text as lines, status,
                created_by, created_at
            from payable""";
    private static final String PAYMENT_SELECT = """
            select id, basket_id, method, amount_minor, currency, channel, region, payer_id, created_at, status,
                gateway_session_id, gateway_txn_id, confirmed_at, exception_reason, last_inquiry_at
            from payment""";
    private static final String RECEIPT_SELECT = """
            select id, number, payment_id, type, region, channel, method, currency, total_minor, lines::text as lines,
                payer_id, issued_at, business_day, status, cancelled_at, cancelled_by, cancellation_reason
            from receipt""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcPaymentRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- payables

    @Override
    public void insertPayable(Payable p) {
        ArrayNode lines = json.createArrayNode();
        p.lines().forEach(l -> lines.addObject().put("financialItem", l.financialItem())
                .put("beneficiary", l.beneficiary()).put("description", l.description())
                .put("amountMinor", l.amountMinor()));
        jdbc.sql("""
                        insert into payable (id, item_type, item_ref, traffic_file_number, region, currency, lines,
                            total_minor, status, created_by, created_at)
                        values (:id, :type, :ref, :file, :region, :currency, cast(:lines as jsonb), :total, :status,
                            :by, :at)""")
                .param("id", p.id()).param("type", p.itemType().name()).param("ref", p.itemRef())
                .param("file", p.trafficFileNumber()).param("region", p.region()).param("currency", p.currency())
                .param("lines", json.writeValueAsString(lines)).param("total", p.total())
                .param("status", p.status().name()).param("by", p.createdBy())
                .param("at", Timestamp.from(p.createdAt())).update();
    }

    @Override
    public Optional<Payable> findPayable(UUID id) {
        return jdbc.sql(PAYABLE_SELECT + " where id = :id").param("id", id).query(this::mapPayable).optional();
    }

    @Override
    public List<Payable> lockPayables(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(PAYABLE_SELECT + " where id = any(string_to_array(:ids, ',')::uuid[]) order by id for update")
                .param("ids", ids.stream().map(UUID::toString).collect(Collectors.joining(",")))
                .query(this::mapPayable).list();
    }

    @Override
    public Optional<Payable> findPayableByItem(Payable.ItemType type, String itemRef) {
        return jdbc.sql(PAYABLE_SELECT + " where item_type = :t and item_ref = :r order by created_at desc limit 1")
                .param("t", type.name()).param("r", itemRef).query(this::mapPayable).optional();
    }

    @Override
    public void savePayableStatus(Payable p) {
        jdbc.sql("update payable set status = :s where id = :id").param("s", p.status().name()).param("id", p.id())
                .update();
    }

    // ---- baskets

    @Override
    public void insertBasket(Basket b) {
        jdbc.sql("""
                        insert into basket (id, payable_ids, region, currency, total_minor, created_by, created_at,
                            status)
                        values (:id, string_to_array(:ids, ',')::uuid[], :region, :currency, :total, :by, :at,
                            :status)""")
                .param("id", b.id())
                .param("ids", b.payableIds().stream().map(UUID::toString).collect(Collectors.joining(",")))
                .param("region", b.region()).param("currency", b.currency()).param("total", b.totalMinor())
                .param("by", b.createdBy()).param("at", Timestamp.from(b.createdAt()))
                .param("status", b.status().name()).update();
    }

    @Override
    public Optional<Basket> findBasket(UUID id) {
        return jdbc.sql("""
                        select id, payable_ids, region, currency, total_minor, created_by, created_at, status
                        from basket where id = :id""")
                .param("id", id)
                .query((rs, n) -> new Basket(rs.getObject("id", UUID.class), uuids(rs.getArray("payable_ids")),
                        rs.getString("region"), rs.getString("currency"), rs.getLong("total_minor"),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                        Basket.Status.valueOf(rs.getString("status"))))
                .optional();
    }

    @Override
    public void saveBasketStatus(Basket b) {
        jdbc.sql("update basket set status = :s where id = :id").param("s", b.status().name()).param("id", b.id())
                .update();
    }

    // ---- payments

    @Override
    public void insertPayment(Payment p) {
        jdbc.sql("""
                        insert into payment (id, basket_id, method, amount_minor, currency, channel, region, payer_id,
                            created_at, status, gateway_session_id, gateway_txn_id, confirmed_at, exception_reason,
                            last_inquiry_at)
                        values (:id, :basket, :method, :amount, :currency, :channel, :region, :payer, :createdAt,
                            :status, :session, :txn, :confirmedAt, :exception, :inquiry)""")
                .param("id", p.id()).param("basket", p.basketId()).param("method", p.method().name())
                .param("amount", p.amountMinor()).param("currency", p.currency()).param("channel", p.channel())
                .param("region", p.region()).param("payer", p.payerId())
                .param("createdAt", Timestamp.from(p.createdAt())).param("status", p.status().name())
                .param("session", p.gatewaySessionId()).param("txn", p.gatewayTxnId())
                .param("confirmedAt", ts(p.confirmedAt())).param("exception", p.exceptionReason())
                .param("inquiry", ts(p.lastInquiryAt())).update();
    }

    @Override
    public Optional<Payment> findPayment(UUID id) {
        return jdbc.sql(PAYMENT_SELECT + " where id = :id").param("id", id)
                .query(JdbcPaymentRepository::mapPayment).optional();
    }

    @Override
    public Optional<Payment> findPaymentForUpdate(UUID id) {
        return jdbc.sql(PAYMENT_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcPaymentRepository::mapPayment).optional();
    }

    @Override
    public void updatePayment(Payment p) {
        try {
            jdbc.sql("""
                            update payment set status = :status, gateway_session_id = :session, gateway_txn_id = :txn,
                                confirmed_at = :confirmedAt, exception_reason = :exception, last_inquiry_at = :inquiry
                            where id = :id""")
                    .param("status", p.status().name()).param("session", p.gatewaySessionId())
                    .param("txn", p.gatewayTxnId()).param("confirmedAt", ts(p.confirmedAt()))
                    .param("exception", p.exceptionReason()).param("inquiry", ts(p.lastInquiryAt()))
                    .param("id", p.id()).update();
        } catch (DuplicateKeyException e) {
            // uq_payment_txn: the gateway transaction id or counter reference is already on another payment (Q-073).
            throw DomainException.conflict("TRANSACTION_REFERENCE_USED",
                    "the reference is already recorded on another payment", List.of("FN-001", "FN-004"));
        }
    }

    @Override
    public boolean pendingPaymentExists(UUID basketId) {
        return jdbc.sql("select exists (select 1 from payment where basket_id = :b and status = 'PENDING')")
                .param("b", basketId).query(Boolean.class).single();
    }

    @Override
    public List<Payment> pendingForInquiry(Instant olderThan, int limit) {
        return jdbc.sql(PAYMENT_SELECT + """
                          where status = 'PENDING' and created_at < :t
                         order by coalesce(last_inquiry_at, created_at) limit :l for update skip locked""")
                .param("t", Timestamp.from(olderThan)).param("l", limit)
                .query(JdbcPaymentRepository::mapPayment).list();
    }

    @Override
    public void logCallback(UUID paymentId, String gatewayTxnId, String status, String outcome, Instant at) {
        jdbc.sql("""
                        insert into payment_callback_log (payment_id, gateway_txn_id, status, outcome, received_at)
                        values (:p, :txn, :status, :outcome, :at)""")
                .param("p", paymentId).param("txn", gatewayTxnId).param("status", status).param("outcome", outcome)
                .param("at", Timestamp.from(at)).update();
    }

    // ---- receipts

    @Override
    public long nextReceiptNumber(String region, String channel, int year) {
        return jdbc.sql("""
                        insert into receipt_sequence (region, channel, year, next_no) values (:r, :c, :y, 2)
                        on conflict (region, channel, year) do update set next_no = receipt_sequence.next_no + 1
                        returning next_no - 1""")
                .param("r", region).param("c", channel).param("y", year).query(Long.class).single();
    }

    @Override
    public void insertReceipt(Receipt r) {
        ArrayNode lines = json.createArrayNode();
        for (Receipt.Line l : r.lines()) {
            ObjectNode n = lines.addObject();
            n.put("payableId", l.payableId().toString());
            n.put("itemType", l.itemType().name());
            n.put("itemRef", l.itemRef());
            n.put("trafficFileNumber", l.trafficFileNumber());
            n.put("financialItem", l.financialItem());
            n.put("beneficiary", l.beneficiary());
            n.put("description", l.description());
            n.put("amountMinor", l.amountMinor());
        }
        jdbc.sql("""
                        insert into receipt (id, number, payment_id, type, region, channel, method, currency,
                            total_minor, lines, payer_id, issued_at, business_day, status)
                        values (:id, :number, :payment, :type, :region, :channel, :method, :currency, :total,
                            cast(:lines as jsonb), :payer, :at, :day, :status)""")
                .param("id", r.id()).param("number", r.number()).param("payment", r.paymentId())
                .param("type", r.type().name()).param("region", r.region()).param("channel", r.channel())
                .param("method", r.method().name()).param("currency", r.currency()).param("total", r.totalMinor())
                .param("lines", json.writeValueAsString(lines)).param("payer", r.payerId())
                .param("at", Timestamp.from(r.issuedAt())).param("day", Date.valueOf(r.businessDay()))
                .param("status", r.status().name()).update();
    }

    @Override
    public Optional<Receipt> findReceipt(String number) {
        return jdbc.sql(RECEIPT_SELECT + " where number = :n").param("n", number).query(this::mapReceipt)
                .optional();
    }

    @Override
    public Optional<Receipt> findReceiptForUpdate(String number) {
        return jdbc.sql(RECEIPT_SELECT + " where number = :n for update").param("n", number)
                .query(this::mapReceipt).optional();
    }

    @Override
    public Optional<Receipt> findReceiptByPayment(UUID paymentId) {
        return jdbc.sql(RECEIPT_SELECT + " where payment_id = :p").param("p", paymentId).query(this::mapReceipt)
                .optional();
    }

    @Override
    public void saveReceiptCancellation(Receipt r) {
        jdbc.sql("""
                        update receipt set status = :s, cancelled_at = :at, cancelled_by = :by,
                            cancellation_reason = :reason
                        where number = :n""")
                .param("s", r.status().name()).param("at", ts(r.cancelledAt())).param("by", r.cancelledBy())
                .param("reason", r.cancellationReason()).param("n", r.number()).update();
    }

    @Override
    public List<Receipt> searchReceipts(ReceiptQuery q) {
        StringBuilder sql = new StringBuilder(RECEIPT_SELECT).append(" where true");
        List<Object[]> params = new ArrayList<>();
        if (q.trafficFileNumber() != null) {
            sql.append(" and exists (select 1 from jsonb_array_elements(lines) l "
                    + "where l->>'trafficFileNumber' = :file)");
            params.add(new Object[] {"file", q.trafficFileNumber()});
        }
        if (q.financialItem() != null) {
            sql.append(" and exists (select 1 from jsonb_array_elements(lines) l "
                    + "where l->>'financialItem' = :item)");
            params.add(new Object[] {"item", q.financialItem()});
        }
        add(sql, params, q.payerId(), " and payer_id = :payer", "payer");
        add(sql, params, q.from() == null ? null : Date.valueOf(q.from()), " and business_day >= :from", "from");
        add(sql, params, q.to() == null ? null : Date.valueOf(q.to()), " and business_day <= :to", "to");
        add(sql, params, q.amountMinor(), " and total_minor = :amount", "amount");
        add(sql, params, q.channel(), " and channel = :channel", "channel");
        add(sql, params, q.region(), " and region = :region", "region");
        sql.append(" order by issued_at desc, number limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", q.limit());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(this::mapReceipt).list();
    }

    private static void add(StringBuilder sql, List<Object[]> params, Object value, String clause, String name) {
        if (value != null) {
            sql.append(clause);
            params.add(new Object[] {name, value});
        }
    }

    // ---- mapping

    private Payable mapPayable(ResultSet rs, int row) throws SQLException {
        List<Payable.Line> lines = new ArrayList<>();
        for (JsonNode l : json.readTree(rs.getString("lines"))) {
            lines.add(new Payable.Line(l.get("financialItem").asString(), l.get("beneficiary").asString(),
                    l.hasNonNull("description") ? l.get("description").asString() : null,
                    l.get("amountMinor").asLong()));
        }
        return Payable.restore(rs.getObject("id", UUID.class), Payable.ItemType.valueOf(rs.getString("item_type")),
                rs.getString("item_ref"), rs.getString("traffic_file_number"), rs.getString("region"),
                rs.getString("currency"), lines, rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), Payable.Status.valueOf(rs.getString("status")));
    }

    private static Payment mapPayment(ResultSet rs, int row) throws SQLException {
        return Payment.restore(rs.getObject("id", UUID.class), rs.getObject("basket_id", UUID.class),
                Payment.Method.valueOf(rs.getString("method")), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("channel"), rs.getString("region"), rs.getString("payer_id"),
                rs.getTimestamp("created_at").toInstant(), Payment.Status.valueOf(rs.getString("status")),
                rs.getString("gateway_session_id"), rs.getString("gateway_txn_id"),
                instant(rs.getTimestamp("confirmed_at")), rs.getString("exception_reason"),
                instant(rs.getTimestamp("last_inquiry_at")));
    }

    private Receipt mapReceipt(ResultSet rs, int row) throws SQLException {
        List<Receipt.Line> lines = new ArrayList<>();
        for (JsonNode l : json.readTree(rs.getString("lines"))) {
            lines.add(new Receipt.Line(UUID.fromString(l.get("payableId").asString()),
                    Payable.ItemType.valueOf(l.get("itemType").asString()), l.get("itemRef").asString(),
                    l.hasNonNull("trafficFileNumber") ? l.get("trafficFileNumber").asString() : null,
                    l.get("financialItem").asString(), l.get("beneficiary").asString(),
                    l.hasNonNull("description") ? l.get("description").asString() : null,
                    l.get("amountMinor").asLong()));
        }
        return new Receipt(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getObject("payment_id", UUID.class), Receipt.Type.valueOf(rs.getString("type")),
                rs.getString("region"), rs.getString("channel"), Payment.Method.valueOf(rs.getString("method")),
                rs.getString("currency"), rs.getLong("total_minor"), lines, rs.getString("payer_id"),
                rs.getTimestamp("issued_at").toInstant(), rs.getDate("business_day").toLocalDate(),
                Receipt.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("cancelled_at")),
                rs.getString("cancelled_by"), rs.getString("cancellation_reason"));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.stream((Object[]) array.getArray()).map(o -> o instanceof UUID u ? u : UUID.fromString(
                o.toString())).toList();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
