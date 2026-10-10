package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.LicenceRepository;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.ExaminationTransfer;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Licences, examination transfers, requests and history in PostgreSQL; the database guards them (V4). */
class JdbcLicenceRepository implements LicenceRepository {

    private static final String TRANSFER = """
            select id, examination_file_ref, traffic_file_number, class_code, gearbox, passed_on, region, status,
                licence_id, received_at, received_by, reversal_reason, reversed_by, changed_at
            from examination_transfer""";
    private static final String LICENCE = """
            select id, licence_number, traffic_file_number, region, customer_category, status, issued_on, expires_on,
                probation_until, residency_expires_on, suspended_until, classes::text as classes,
                conditions::text as conditions, version
            from licence""";
    private static final String REQUEST = """
            select id, kind, status, licence_id, traffic_file_number, region, quote_id, payable_id, amount_minor,
                currency, payload::text as payload, transfer_ids::text as transfer_ids, requested_by, channel,
                requested_at, completed_at
            from licence_request""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcLicenceRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lockHolder(String trafficFileNumber) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('licence-holder:' || :t))").param("t", trafficFileNumber)
                .query().listOfRows();
    }

    // ---- examination transfers

    @Override
    public void insertTransfer(ExaminationTransfer t) {
        ExaminationTransfer.Spec s = t.spec();
        jdbc.sql("""
                        insert into examination_transfer (id, examination_file_ref, traffic_file_number, class_code,
                            gearbox, passed_on, region, status, received_at, received_by)
                        values (:id, :ref, :file, :cls, :gearbox, :passed, :region, :status, :at, :by)""")
                .param("id", t.id()).param("ref", s.examinationFileRef()).param("file", s.trafficFileNumber())
                .param("cls", s.classCode()).param("gearbox", s.gearbox().name())
                .param("passed", Date.valueOf(s.passedOn())).param("region", s.region())
                .param("status", t.status().name()).param("at", Timestamp.from(t.receivedAt()))
                .param("by", t.receivedBy()).update();
    }

    @Override
    public Optional<ExaminationTransfer> transfer(UUID id) {
        return jdbc.sql(TRANSFER + " where id = :id").param("id", id).query(this::mapTransfer).optional();
    }

    @Override
    public Optional<ExaminationTransfer> transferForUpdate(UUID id) {
        return jdbc.sql(TRANSFER + " where id = :id for update").param("id", id).query(this::mapTransfer).optional();
    }

    @Override
    public Optional<ExaminationTransfer> transferOf(String examinationFileRef, String classCode) {
        return jdbc.sql(TRANSFER + " where examination_file_ref = :ref and class_code = :cls")
                .param("ref", examinationFileRef).param("cls", classCode).query(this::mapTransfer).optional();
    }

    @Override
    public List<ExaminationTransfer> transfersOf(String trafficFileNumber) {
        return jdbc.sql(TRANSFER + " where traffic_file_number = :t order by received_at desc")
                .param("t", trafficFileNumber).query(this::mapTransfer).list();
    }

    @Override
    public void updateTransfer(ExaminationTransfer t) {
        jdbc.sql("""
                        update examination_transfer set status = :status, licence_id = :licence,
                            reversal_reason = :reason, reversed_by = :by, changed_at = :at
                        where id = :id""")
                .param("status", t.status().name()).param("licence", t.licenceId())
                .param("reason", t.reversalReason()).param("by", t.reversedBy())
                .param("at", t.changedAt() == null ? null : Timestamp.from(t.changedAt())).param("id", t.id())
                .update();
    }

    private ExaminationTransfer mapTransfer(ResultSet rs, int n) throws SQLException {
        return ExaminationTransfer.restore(rs.getObject("id", UUID.class),
                new ExaminationTransfer.Spec(rs.getString("examination_file_ref"), rs.getString("traffic_file_number"),
                        rs.getString("class_code"), ExaminationTransfer.Gearbox.valueOf(rs.getString("gearbox")),
                        rs.getDate("passed_on").toLocalDate(), rs.getString("region")),
                rs.getTimestamp("received_at").toInstant(), rs.getString("received_by"),
                ExaminationTransfer.Status.valueOf(rs.getString("status")), rs.getObject("licence_id", UUID.class),
                rs.getString("reversal_reason"), rs.getString("reversed_by"), instant(rs, "changed_at"));
    }

    // ---- licences

    @Override
    public void insertLicence(Licence l) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                        insert into licence (id, licence_number, traffic_file_number, region, customer_category,
                            status, issued_on, expires_on, probation_until, residency_expires_on, suspended_until,
                            classes, conditions, version, created_at, updated_at)
                        values (:id, :number, :file, :region, :category, :status, :issued, :expires, :probation,
                            :residency, :suspended, cast(:classes as jsonb), cast(:conditions as jsonb), 0, :now,
                            :now)""")
                .param("id", l.id()).param("number", l.licenceNumber()).param("file", l.trafficFileNumber())
                .param("region", l.region()).param("category", l.customerCategory())
                .param("status", l.status().name()).param("issued", Date.valueOf(l.issuedOn()))
                .param("expires", Date.valueOf(l.expiresOn())).param("probation", date(l.probationUntil()))
                .param("residency", date(l.residencyExpiresOn())).param("suspended", date(l.suspendedUntil()))
                .param("classes", classes(l)).param("conditions", conditions(l)).param("now", now).update();
    }

    @Override
    public void updateLicence(Licence l) {
        int n = jdbc.sql("""
                        update licence set customer_category = :category, status = :status, issued_on = :issued,
                            expires_on = :expires, probation_until = :probation, residency_expires_on = :residency,
                            suspended_until = :suspended, classes = cast(:classes as jsonb),
                            conditions = cast(:conditions as jsonb), version = version + 1, updated_at = :now
                        where id = :id and version = :version""")
                .param("category", l.customerCategory()).param("status", l.status().name())
                .param("issued", Date.valueOf(l.issuedOn())).param("expires", Date.valueOf(l.expiresOn()))
                .param("probation", date(l.probationUntil())).param("residency", date(l.residencyExpiresOn()))
                .param("suspended", date(l.suspendedUntil())).param("classes", classes(l))
                .param("conditions", conditions(l)).param("now", Timestamp.from(Instant.now()))
                .param("id", l.id()).param("version", l.version()).update();
        if (n != 1) {
            throw new OptimisticLockingFailureException("licence " + l.id() + " changed concurrently");
        }
    }

    @Override
    public Optional<Licence> licence(UUID id) {
        return jdbc.sql(LICENCE + " where id = :id").param("id", id).query(this::mapLicence).optional();
    }

    @Override
    public Optional<Licence> licenceForUpdate(UUID id) {
        return jdbc.sql(LICENCE + " where id = :id for update").param("id", id).query(this::mapLicence).optional();
    }

    @Override
    public Optional<Licence> licenceByNumber(String licenceNumber) {
        return jdbc.sql(LICENCE + " where licence_number = :n").param("n", licenceNumber).query(this::mapLicence)
                .optional();
    }

    @Override
    public List<Licence> licencesOf(String trafficFileNumber) {
        return jdbc.sql(LICENCE + " where traffic_file_number = :t order by created_at desc")
                .param("t", trafficFileNumber).query(this::mapLicence).list();
    }

    @Override
    public void history(UUID licenceId, String event, String actorId, Map<String, Object> detail, Instant at) {
        jdbc.sql("""
                        insert into licence_history (licence_id, event, actor_id, detail, at)
                        values (:l, :e, :a, cast(:d as jsonb), :at)""")
                .param("l", licenceId).param("e", event).param("a", actorId)
                .param("d", json.writeValueAsString(detail)).param("at", Timestamp.from(at)).update();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<HistoryEntry> history(UUID licenceId) {
        return jdbc.sql("select event, actor_id, detail::text as detail, at from licence_history"
                        + " where licence_id = :l order by id")
                .param("l", licenceId)
                .query((rs, n) -> new HistoryEntry(rs.getString("event"), rs.getString("actor_id"),
                        json.readValue(rs.getString("detail"), LinkedHashMap.class),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    private Licence mapLicence(ResultSet rs, int n) throws SQLException {
        List<Licence.LicenceClass> classes = new ArrayList<>();
        for (JsonNode c : json.readTree(rs.getString("classes"))) {
            JsonNode transfer = c.path("transferId");
            classes.add(new Licence.LicenceClass(c.path("classCode").asString(),
                    UUID.fromString(c.path("classVersionId").asString()),
                    LocalDate.parse(c.path("issuedOn").asString()),
                    Licence.ClassStatus.valueOf(c.path("status").asString()), c.path("automaticOnly").asBoolean(),
                    transfer.isMissingNode() || transfer.isNull() ? null : UUID.fromString(transfer.asString())));
        }
        List<Licence.Condition> conditions = new ArrayList<>();
        for (JsonNode c : json.readTree(rs.getString("conditions"))) {
            JsonNode note = c.path("note");
            conditions.add(new Licence.Condition(c.path("code").asString(),
                    note.isMissingNode() || note.isNull() ? null : note.asString(),
                    LocalDate.parse(c.path("since").asString())));
        }
        return Licence.restore(rs.getObject("id", UUID.class), rs.getString("licence_number"),
                rs.getString("traffic_file_number"), rs.getString("region"), rs.getString("customer_category"),
                Licence.Status.valueOf(rs.getString("status")), rs.getDate("issued_on").toLocalDate(),
                rs.getDate("expires_on").toLocalDate(), localDate(rs, "probation_until"),
                localDate(rs, "residency_expires_on"), localDate(rs, "suspended_until"), classes, conditions,
                rs.getLong("version"));
    }

    private String classes(Licence l) {
        return json.writeValueAsString(l.classes().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("classCode", c.classCode());
            m.put("classVersionId", c.classVersionId().toString());
            m.put("issuedOn", c.issuedOn().toString());
            m.put("status", c.status().name());
            m.put("automaticOnly", c.automaticOnly());
            m.put("transferId", c.transferId() == null ? null : c.transferId().toString());
            return m;
        }).toList());
    }

    private String conditions(Licence l) {
        return json.writeValueAsString(l.conditions().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("note", c.note());
            m.put("since", c.since().toString());
            return m;
        }).toList());
    }

    // ---- requests

    @Override
    public void insertRequest(LicenceRequest r) {
        jdbc.sql("""
                        insert into licence_request (id, kind, status, licence_id, traffic_file_number, region,
                            quote_id, payable_id, amount_minor, currency, payload, transfer_ids, requested_by, channel,
                            requested_at)
                        values (:id, :kind, :status, :licence, :file, :region, :quote, :payable, :amount, :currency,
                            cast(:payload as jsonb), cast(:transfers as jsonb), :by, :channel, :at)""")
                .param("id", r.id()).param("kind", r.kind().name()).param("status", r.status().name())
                .param("licence", r.licenceId()).param("file", r.trafficFileNumber()).param("region", r.region())
                .param("quote", r.quoteId()).param("payable", r.payableId()).param("amount", r.amountMinor())
                .param("currency", r.currency()).param("payload", json.writeValueAsString(r.payload()))
                .param("transfers", json.writeValueAsString(r.transferIds().stream().map(UUID::toString).toList()))
                .param("by", r.requestedBy()).param("channel", r.channel() == null ? null : r.channel().name())
                .param("at", Timestamp.from(r.requestedAt())).update();
    }

    @Override
    public Optional<LicenceRequest> request(UUID id) {
        return jdbc.sql(REQUEST + " where id = :id").param("id", id).query(this::mapRequest).optional();
    }

    @Override
    public Optional<LicenceRequest> requestForUpdate(UUID id) {
        return jdbc.sql(REQUEST + " where id = :id for update").param("id", id).query(this::mapRequest).optional();
    }

    @Override
    public void updateRequest(LicenceRequest r) {
        jdbc.sql("update licence_request set status = :status, completed_at = :at, licence_id = :licence"
                        + " where id = :id")
                .param("status", r.status().name())
                .param("at", r.completedAt() == null ? null : Timestamp.from(r.completedAt()))
                .param("licence", r.licenceId()).param("id", r.id()).update();
    }

    @Override
    public List<LicenceRequest> openRequests(String trafficFileNumber, LicenceRequest.Kind kind) {
        return jdbc.sql(REQUEST + " where traffic_file_number = :t and kind = :k and status = 'AWAITING_PAYMENT'")
                .param("t", trafficFileNumber).param("k", kind.name()).query(this::mapRequest).list();
    }

    @Override
    public List<LicenceRequest> openRequestsOf(UUID licenceId) {
        return jdbc.sql(REQUEST + " where licence_id = :l and status = 'AWAITING_PAYMENT'").param("l", licenceId)
                .query(this::mapRequest).list();
    }

    // ---- cards

    private static final String CARD = """
            select id, licence_id, serial, reason, delivery, address, status, tracking_ref, created_at, status_at
            from licence_card""";

    @Override
    public int nextCardSerial(UUID licenceId) {
        return jdbc.sql("select coalesce(max(serial), 0) + 1 from licence_card where licence_id = :l")
                .param("l", licenceId).query(Integer.class).single();
    }

    @Override
    public void insertCard(Records.Card c) {
        jdbc.sql("""
                        insert into licence_card (id, licence_id, serial, reason, delivery, address, status,
                            tracking_ref, created_at, status_at)
                        values (:id, :l, :serial, :reason, :delivery, :address, :status, :tracking, :created, :at)""")
                .param("id", c.id()).param("l", c.licenceId()).param("serial", c.serial())
                .param("reason", c.reason().name()).param("delivery", c.delivery().name())
                .param("address", c.address()).param("status", c.status().name()).param("tracking", c.trackingRef())
                .param("created", Timestamp.from(c.createdAt())).param("at", Timestamp.from(c.statusAt())).update();
    }

    @Override
    public void updateCard(Records.Card c) {
        jdbc.sql("update licence_card set status = :status, tracking_ref = :tracking, status_at = :at where id = :id")
                .param("status", c.status().name()).param("tracking", c.trackingRef())
                .param("at", Timestamp.from(c.statusAt())).param("id", c.id()).update();
    }

    @Override
    public Optional<Records.Card> card(UUID id) {
        return jdbc.sql(CARD + " where id = :id").param("id", id).query(this::mapCard).optional();
    }

    @Override
    public Optional<Records.Card> cardForUpdate(UUID id) {
        return jdbc.sql(CARD + " where id = :id for update").param("id", id).query(this::mapCard).optional();
    }

    @Override
    public List<Records.Card> cards(UUID licenceId) {
        return jdbc.sql(CARD + " where licence_id = :l order by serial desc").param("l", licenceId)
                .query(this::mapCard).list();
    }

    @Override
    public List<Records.Card> cardsIn(Records.CardStatus status, Records.Delivery delivery, int limit) {
        return jdbc.sql(CARD + " where status = :s and delivery = :d order by created_at limit :n")
                .param("s", status.name()).param("d", delivery.name()).param("n", limit).query(this::mapCard).list();
    }

    private Records.Card mapCard(ResultSet rs, int n) throws SQLException {
        return new Records.Card(rs.getObject("id", UUID.class), rs.getObject("licence_id", UUID.class),
                rs.getInt("serial"), Records.CardReason.valueOf(rs.getString("reason")),
                Records.Delivery.valueOf(rs.getString("delivery")), rs.getString("address"),
                Records.CardStatus.valueOf(rs.getString("status")), rs.getString("tracking_ref"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("status_at").toInstant());
    }

    // ---- eye tests

    private static final String EYE = """
            select id, traffic_file_number, centre_code, reference, tested_on, result, corrective_lenses,
                permitted_classes::text as permitted_classes, received_at, received_by
            from eye_test""";

    @Override
    public boolean insertEyeTest(Records.EyeTest t) {
        return jdbc.sql("""
                        insert into eye_test (id, traffic_file_number, centre_code, reference, tested_on, result,
                            corrective_lenses, permitted_classes, received_at, received_by)
                        values (:id, :file, :centre, :ref, :on, :result, :lenses, cast(:classes as jsonb), :at, :by)
                        on conflict (centre_code, reference) do nothing""")
                .param("id", t.id()).param("file", t.trafficFileNumber()).param("centre", t.centreCode())
                .param("ref", t.reference()).param("on", Date.valueOf(t.testedOn())).param("result", t.result().name())
                .param("lenses", t.correctiveLenses()).param("classes", json.writeValueAsString(t.permittedClasses()))
                .param("at", Timestamp.from(t.receivedAt())).param("by", t.receivedBy()).update() == 1;
    }

    @Override
    public Optional<Records.EyeTest> eyeTest(String centreCode, String reference) {
        return jdbc.sql(EYE + " where centre_code = :c and reference = :r").param("c", centreCode)
                .param("r", reference).query(this::mapEye).optional();
    }

    @Override
    public List<Records.EyeTest> eyeTests(String trafficFileNumber) {
        return jdbc.sql(EYE + " where traffic_file_number = :t order by tested_on desc, received_at desc")
                .param("t", trafficFileNumber).query(this::mapEye).list();
    }

    private Records.EyeTest mapEye(ResultSet rs, int n) throws SQLException {
        List<String> classes = new ArrayList<>();
        for (JsonNode c : json.readTree(rs.getString("permitted_classes"))) {
            classes.add(c.asString());
        }
        return new Records.EyeTest(rs.getObject("id", UUID.class), rs.getString("traffic_file_number"),
                rs.getString("centre_code"), rs.getString("reference"), rs.getDate("tested_on").toLocalDate(),
                Records.EyeResult.valueOf(rs.getString("result")), rs.getBoolean("corrective_lenses"), classes,
                rs.getTimestamp("received_at").toInstant(), rs.getString("received_by"));
    }

    // ---- automatic renewal

    private static final String AUTO = """
            select a.licence_id, a.payment_method_ref, a.status, a.consent_by, a.consent_at, a.notice_for_expiry,
                a.notice_at, a.attempt_for_expiry, a.attempt_at, a.withdrawn_by, a.withdrawn_at
            from auto_renewal a""";

    @Override
    public Optional<Records.AutoRenewal> autoRenewalForUpdate(UUID licenceId) {
        return jdbc.sql(AUTO + " where a.licence_id = :l for update").param("l", licenceId).query(this::mapAuto)
                .optional();
    }

    @Override
    public Optional<Records.AutoRenewal> autoRenewal(UUID licenceId) {
        return jdbc.sql(AUTO + " where a.licence_id = :l").param("l", licenceId).query(this::mapAuto).optional();
    }

    @Override
    public void saveAutoRenewal(Records.AutoRenewal c) {
        jdbc.sql("""
                        insert into auto_renewal (licence_id, payment_method_ref, status, consent_by, consent_at,
                            notice_for_expiry, notice_at, attempt_for_expiry, attempt_at, withdrawn_by, withdrawn_at)
                        values (:l, :ref, :status, :by, :at, :notice, :noticeAt, :attempt, :attemptAt, :wBy, :wAt)
                        on conflict (licence_id) do update set payment_method_ref = excluded.payment_method_ref,
                            status = excluded.status, consent_by = excluded.consent_by,
                            consent_at = excluded.consent_at, notice_for_expiry = excluded.notice_for_expiry,
                            notice_at = excluded.notice_at, attempt_for_expiry = excluded.attempt_for_expiry,
                            attempt_at = excluded.attempt_at, withdrawn_by = excluded.withdrawn_by,
                            withdrawn_at = excluded.withdrawn_at""")
                .param("l", c.licenceId()).param("ref", c.paymentMethodRef()).param("status", c.status().name())
                .param("by", c.consentBy()).param("at", ts(c.consentAt())).param("notice", date(c.noticeForExpiry()))
                .param("noticeAt", ts(c.noticeAt())).param("attempt", date(c.attemptForExpiry()))
                .param("attemptAt", ts(c.attemptAt())).param("wBy", c.withdrawnBy()).param("wAt", ts(c.withdrawnAt()))
                .update();
    }

    @Override
    public List<Records.AutoRenewal> autoRenewalsDue(LocalDate expiringBy, int limit) {
        return jdbc.sql(AUTO + " join licence l on l.id = a.licence_id where a.status = 'ACTIVE'"
                        + " and l.status = 'ACTIVE' and l.expires_on <= :by order by l.expires_on limit :n")
                .param("by", Date.valueOf(expiringBy)).param("n", limit).query(this::mapAuto).list();
    }

    private Records.AutoRenewal mapAuto(ResultSet rs, int n) throws SQLException {
        return new Records.AutoRenewal(rs.getObject("licence_id", UUID.class), rs.getString("payment_method_ref"),
                Records.ConsentStatus.valueOf(rs.getString("status")), rs.getString("consent_by"),
                instant(rs, "consent_at"), localDate(rs, "notice_for_expiry"), instant(rs, "notice_at"),
                localDate(rs, "attempt_for_expiry"), instant(rs, "attempt_at"), rs.getString("withdrawn_by"),
                instant(rs, "withdrawn_at"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    // ---- suspensions

    @Override
    public boolean insertSuspension(Records.Suspension s) {
        return jdbc.sql("""
                        insert into licence_suspension (id, licence_id, source, source_ref, ordinal, from_date,
                            until_date, received_at)
                        values (:id, :l, :source, :ref, :ordinal, :from, :until, :at)
                        on conflict (licence_id, source, source_ref) do nothing""")
                .param("id", s.id()).param("l", s.licenceId()).param("source", s.source())
                .param("ref", s.sourceRef()).param("ordinal", s.ordinal()).param("from", Date.valueOf(s.from()))
                .param("until", Date.valueOf(s.until())).param("at", Timestamp.from(s.receivedAt())).update() == 1;
    }

    @Override
    public List<Records.Suspension> suspensions(UUID licenceId) {
        return jdbc.sql("""
                        select id, licence_id, source, source_ref, ordinal, from_date, until_date, received_at
                        from licence_suspension where licence_id = :l order by from_date""")
                .param("l", licenceId)
                .query((rs, n) -> new Records.Suspension(rs.getObject("id", UUID.class),
                        rs.getObject("licence_id", UUID.class), rs.getString("source"), rs.getString("source_ref"),
                        rs.getInt("ordinal"), rs.getDate("from_date").toLocalDate(),
                        rs.getDate("until_date").toLocalDate(), rs.getTimestamp("received_at").toInstant()))
                .list();
    }

    @Override
    public List<UUID> suspensionsEndedBefore(LocalDate day, int limit) {
        return jdbc.sql("select id from licence where status = 'SUSPENDED' and suspended_until < :d"
                        + " order by suspended_until limit :n")
                .param("d", Date.valueOf(day)).param("n", limit).query(UUID.class).list();
    }

    // ---- no-objection certificates

    private static final String CERTIFICATE = """
            select id, licence_id, number, purpose, addressee, verification_code, issued_on, valid_until, request_id,
                issued_at
            from licence_certificate""";

    @Override
    public String nextCertificateNumber(int year) {
        long n = jdbc.sql("select nextval('licence_certificate_seq')").query(Long.class).single();
        return String.format("NOC-%d-%06d", year, n);
    }

    @Override
    public void insertCertificate(Records.Certificate c) {
        jdbc.sql("""
                        insert into licence_certificate (id, licence_id, number, purpose, addressee,
                            verification_code, issued_on, valid_until, request_id, issued_at)
                        values (:id, :l, :number, :purpose, :addressee, :code, :issued, :valid, :request, :at)""")
                .param("id", c.id()).param("l", c.licenceId()).param("number", c.number())
                .param("purpose", c.purpose()).param("addressee", c.addressee()).param("code", c.verificationCode())
                .param("issued", Date.valueOf(c.issuedOn())).param("valid", Date.valueOf(c.validUntil()))
                .param("request", c.requestId()).param("at", Timestamp.from(c.issuedAt())).update();
    }

    @Override
    public Optional<Records.Certificate> certificate(String number) {
        return jdbc.sql(CERTIFICATE + " where number = :n").param("n", number).query(this::mapCertificate)
                .optional();
    }

    @Override
    public List<Records.Certificate> certificates(UUID licenceId) {
        return jdbc.sql(CERTIFICATE + " where licence_id = :l order by issued_at desc").param("l", licenceId)
                .query(this::mapCertificate).list();
    }

    private Records.Certificate mapCertificate(ResultSet rs, int n) throws SQLException {
        return new Records.Certificate(rs.getObject("id", UUID.class), rs.getObject("licence_id", UUID.class),
                rs.getString("number"), rs.getString("purpose"), rs.getString("addressee"),
                rs.getString("verification_code"), rs.getDate("issued_on").toLocalDate(),
                rs.getDate("valid_until").toLocalDate(), rs.getObject("request_id", UUID.class),
                rs.getTimestamp("issued_at").toInstant());
    }

    @SuppressWarnings("unchecked")
    private LicenceRequest mapRequest(ResultSet rs, int n) throws SQLException {
        Map<String, String> payload = new LinkedHashMap<>();
        json.readTree(rs.getString("payload")).properties().forEach(e -> payload.put(e.getKey(),
                e.getValue().asString()));
        List<UUID> transfers = new ArrayList<>();
        for (JsonNode t : json.readTree(rs.getString("transfer_ids"))) {
            transfers.add(UUID.fromString(t.asString()));
        }
        String channel = rs.getString("channel");
        return new LicenceRequest(rs.getObject("id", UUID.class), LicenceRequest.Kind.valueOf(rs.getString("kind")),
                LicenceRequest.Status.valueOf(rs.getString("status")), rs.getObject("licence_id", UUID.class),
                rs.getString("traffic_file_number"), rs.getString("region"), rs.getString("quote_id"),
                rs.getObject("payable_id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency"),
                payload, transfers, rs.getString("requested_by"), channel == null ? null : Channel.valueOf(channel),
                rs.getTimestamp("requested_at").toInstant(), instant(rs, "completed_at"));
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
