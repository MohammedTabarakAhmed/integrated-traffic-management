package gov.itms.violation.infrastructure;

import gov.itms.violation.application.Ports;
import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.UnpaidHold;
import gov.itms.violation.domain.Violation;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** Violations and black points in PostgreSQL (V1). */
class JdbcViolationRepository implements Ports.ViolationRepository {

    private static final String CATALOGUE_SELECT = """
            select code, version_no, region, legal_article, description_nl, description_en, fine_minor, black_points,
                impound_days, suspension_days, court_referral, applies_to, speed_bands::text as speed_bands,
                repeat_within_days, repeat_fine_minor, financial_item, beneficiary, effective_from, effective_to,
                published_by, published_at
            from violation_catalogue""";
    private static final String VIOLATION_SELECT = """
            select id, number, catalogue_code, catalogue_version, occurred_at, region, location, plate, vin,
                owner_file_number, driver_file_number, speed_limit_kmh, measured_speed_kmh, source, device_id,
                evidence_ids, fine_minor, black_points, repeat, recorded_by, recorded_at, status, payable_id,
                cancel_reason, closed_at, renter_file_number, rental_contract_id
            from violation""";

    private static final String UNPAID_HOLD_SELECT = """
            select h.id, h.violation_id, h.violation_number, h.subject_type, h.subject_id, h.hold_id, h.placed_at,
                h.lifted_at
            from unpaid_hold h""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcViolationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lockCatalogue(String code) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('violation-catalogue:' || :c))").param("c", code).query()
                .listOfRows();
    }

    @Override
    public int nextCatalogueVersion(String code) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from violation_catalogue where code = :c")
                .param("c", code).query(Integer.class).single();
    }

    @Override
    public void insertCatalogue(CatalogueEntry e) {
        ArrayNode bands = json.createArrayNode();
        e.speedBands().forEach(b -> {
            var n = bands.addObject().put("fromKmh", b.fromKmh()).put("fineMinor", b.fineMinor())
                    .put("blackPoints", b.blackPoints());
            if (b.toKmh() != null) {
                n.put("toKmh", b.toKmh());
            }
        });
        jdbc.sql("""
                        insert into violation_catalogue (code, version_no, region, legal_article, description_nl,
                            description_en, fine_minor, black_points, impound_days, suspension_days, court_referral,
                            applies_to, speed_bands, repeat_within_days, repeat_fine_minor, financial_item,
                            beneficiary, effective_from, effective_to, published_by, published_at)
                        values (:code, :version, :region, :article, :nl, :en, :fine, :points, :impound, :suspension,
                            :court, :applies, cast(:bands as jsonb), :repeatDays, :repeatFine, :item, :beneficiary,
                            :from, :to, :by, :at)""")
                .param("code", e.code()).param("version", e.versionNo()).param("region", e.region())
                .param("article", e.legalArticle()).param("nl", e.descriptionNl()).param("en", e.descriptionEn())
                .param("fine", e.fineMinor()).param("points", e.blackPoints()).param("impound", e.impoundDays())
                .param("suspension", e.suspensionDays()).param("court", e.courtReferral())
                .param("applies", e.appliesTo().name()).param("bands", json.writeValueAsString(bands))
                .param("repeatDays", e.repeatWithinDays()).param("repeatFine", e.repeatFineMinor())
                .param("item", e.financialItem()).param("beneficiary", e.beneficiary())
                .param("from", Date.valueOf(e.effectiveFrom()))
                .param("to", e.effectiveTo() == null ? null : Date.valueOf(e.effectiveTo()))
                .param("by", e.publishedBy()).param("at", Timestamp.from(e.publishedAt())).update();
    }

    @Override
    public List<CatalogueEntry> catalogueVersions(String code) {
        return jdbc.sql(CATALOGUE_SELECT + " where code = :c order by version_no desc").param("c", code)
                .query(this::mapCatalogue).list();
    }

    @Override
    public List<CatalogueEntry> latestCatalogue() {
        return jdbc.sql(CATALOGUE_SELECT + " " + """
                         c where version_no = (select max(version_no) from violation_catalogue x
                             where x.code = c.code and x.region is not distinct from c.region) order by code, region""")
                .query(this::mapCatalogue).list();
    }

    @Override
    public long nextViolationSequence() {
        return jdbc.sql("select nextval('violation_seq')").query(Long.class).single();
    }

    @Override
    public void insertViolation(Violation v) {
        Violation.Report r = v.report();
        jdbc.sql("""
                        insert into violation (id, number, catalogue_code, catalogue_version, occurred_at, region,
                            location, plate, vin, owner_file_number, driver_file_number, responsible_file_number,
                            speed_limit_kmh, measured_speed_kmh, source, device_id, evidence_ids, fine_minor,
                            black_points, repeat, recorded_by, recorded_at, status, payable_id, cancel_reason,
                            closed_at, renter_file_number, rental_contract_id)
                        values (:id, :number, :code, :version, :occurred, :region, :location, :plate, :vin, :owner,
                            :driver, :responsible, :limit, :measured, :source, :device, string_to_array(:evidence, ','),
                            :fine, :points, :repeat, :by, :at, :status, :payable, :cancel, :closed, :renter,
                            :contract)""")
                .param("id", v.id()).param("number", v.number()).param("code", v.catalogueCode())
                .param("version", v.catalogueVersion()).param("occurred", Timestamp.from(r.occurredAt()))
                .param("region", r.region()).param("location", r.location()).param("plate", r.plate())
                .param("vin", r.vin()).param("owner", r.ownerFileNumber()).param("driver", r.driverFileNumber())
                .param("responsible", r.responsibleFileNumber()).param("limit", r.speedLimitKmh())
                .param("measured", r.measuredSpeedKmh()).param("source", r.source().name())
                .param("device", r.deviceId()).param("evidence", String.join(",", r.evidenceIds()))
                .param("fine", v.fineMinor()).param("points", v.blackPoints()).param("repeat", v.repeat())
                .param("by", v.recordedBy()).param("at", Timestamp.from(v.recordedAt()))
                .param("status", v.status().name()).param("payable", v.payableId())
                .param("cancel", v.cancelReason()).param("closed", ts(v.closedAt()))
                .param("renter", r.renterFileNumber()).param("contract", r.rentalContractId()).update();
    }

    @Override
    public Optional<Violation> findByNumber(String number) {
        return jdbc.sql(VIOLATION_SELECT + " where number = :n").param("n", number)
                .query(JdbcViolationRepository::mapViolation).optional();
    }

    @Override
    public Optional<Violation> findForUpdate(String number) {
        return jdbc.sql(VIOLATION_SELECT + " where number = :n for update").param("n", number)
                .query(JdbcViolationRepository::mapViolation).optional();
    }

    @Override
    public void updateViolation(Violation v) {
        jdbc.sql("""
                        update violation set status = :status, payable_id = :payable, cancel_reason = :cancel,
                            closed_at = :closed
                        where id = :id""")
                .param("status", v.status().name()).param("payable", v.payableId())
                .param("cancel", v.cancelReason()).param("closed", ts(v.closedAt())).param("id", v.id()).update();
    }

    @Override
    public List<Violation> byFile(String fileNumber, Violation.Status status, int limit) {
        return jdbc.sql(VIOLATION_SELECT + """
                          where responsible_file_number = :f and (cast(:s as varchar) is null or status = :s)
                         order by recorded_at desc limit :l""")
                .param("f", fileNumber).param("s", status == null ? null : status.name()).param("l", limit)
                .query(JdbcViolationRepository::mapViolation).list();
    }

    @Override
    public boolean hadViolationSince(String fileNumber, String catalogueCode, Instant since) {
        return jdbc.sql("""
                        select exists (select 1 from violation where responsible_file_number = :f
                            and catalogue_code = :c and occurred_at >= :since and status <> 'CANCELLED')""")
                .param("f", fileNumber).param("c", catalogueCode).param("since", Timestamp.from(since))
                .query(Boolean.class).single();
    }

    @Override
    public void lockFile(String fileNumber) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('black-points:' || :f))").param("f", fileNumber).query()
                .listOfRows();
    }

    @Override
    public void insertPoints(BlackPoints.Entry e) {
        jdbc.sql("""
                        insert into black_point_entry (id, file_number, violation_id, points, occurred_on, expires_on,
                            status)
                        values (:id, :f, :v, :p, :on, :exp, :s)""")
                .param("id", e.id()).param("f", e.fileNumber()).param("v", e.violationId()).param("p", e.points())
                .param("on", Date.valueOf(e.occurredOn())).param("exp", Date.valueOf(e.expiresOn()))
                .param("s", e.status().name()).update();
    }

    @Override
    public List<BlackPoints.Entry> points(String fileNumber) {
        return jdbc.sql("""
                        select id, file_number, violation_id, points, occurred_on, expires_on, status
                        from black_point_entry where file_number = :f order by occurred_on, id""")
                .param("f", fileNumber)
                .query((rs, n) -> new BlackPoints.Entry(rs.getObject("id", UUID.class), rs.getString("file_number"),
                        rs.getObject("violation_id", UUID.class), rs.getInt("points"),
                        rs.getDate("occurred_on").toLocalDate(), rs.getDate("expires_on").toLocalDate(),
                        BlackPoints.EntryStatus.valueOf(rs.getString("status"))))
                .list();
    }

    @Override
    public void setPointsStatus(List<UUID> ids, BlackPoints.EntryStatus status) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("update black_point_entry set status = :s where id = any(string_to_array(:ids, ',')::uuid[])")
                .param("s", status.name())
                .param("ids", ids.stream().map(UUID::toString).collect(Collectors.joining(","))).update();
    }

    @Override
    public void cancelPointsOf(UUID violationId) {
        jdbc.sql("update black_point_entry set status = 'CANCELLED' where violation_id = :v and status = 'ACTIVE'")
                .param("v", violationId).update();
    }

    @Override
    public List<BlackPoints.Suspension> suspensions(String fileNumber) {
        return jdbc.sql("""
                        select id, file_number, ordinal, date_from, date_until, points_at_order, hold_id, triggered_by
                        from licence_suspension where file_number = :f order by ordinal""")
                .param("f", fileNumber)
                .query((rs, n) -> new BlackPoints.Suspension(rs.getObject("id", UUID.class),
                        rs.getString("file_number"), rs.getInt("ordinal"), rs.getDate("date_from").toLocalDate(),
                        rs.getDate("date_until").toLocalDate(), rs.getInt("points_at_order"), rs.getString("hold_id"),
                        rs.getObject("triggered_by", UUID.class)))
                .list();
    }

    @Override
    public void insertSuspension(BlackPoints.Suspension s) {
        jdbc.sql("""
                        insert into licence_suspension (id, file_number, ordinal, date_from, date_until,
                            points_at_order, hold_id, triggered_by)
                        values (:id, :f, :o, :from, :until, :p, :hold, :t)""")
                .param("id", s.id()).param("f", s.fileNumber()).param("o", s.ordinal())
                .param("from", Date.valueOf(s.from())).param("until", Date.valueOf(s.until()))
                .param("p", s.pointsAtOrder()).param("hold", s.holdId()).param("t", s.triggeredBy()).update();
    }

    @Override
    public List<Violation> overdueWithoutHold(Instant recordedBefore, int limit) {
        return jdbc.sql(VIOLATION_SELECT + """
                          where status = 'OPEN' and recorded_at < :c
                           and not exists (select 1 from unpaid_hold h
                                           where h.violation_id = violation.id and h.lifted_at is null)
                           and not exists (select 1 from violation_objection o
                                           where o.violation_id = violation.id and o.status in ('PENDING', 'COURT'))
                           and not exists (select 1 from instalment_plan_violation pv
                                           join instalment_plan p on p.id = pv.plan_id
                                           where pv.violation_id = violation.id and p.status = 'ACTIVE')
                         order by recorded_at limit :l""")
                .param("c", ts(recordedBefore)).param("l", limit)
                .query(JdbcViolationRepository::mapViolation).list();
    }

    @Override
    public List<Violation> dueForConfirmation(Instant recordedBefore, int limit) {
        return jdbc.sql(VIOLATION_SELECT + """
                          where status in ('OPEN', 'PAID') and black_points > 0 and source <> 'PARTNER'
                           and recorded_at < :c
                           and not exists (select 1 from black_point_entry e where e.violation_id = violation.id)
                           and not exists (select 1 from violation_objection o
                                           where o.violation_id = violation.id and o.status in ('PENDING', 'COURT'))
                         order by recorded_at limit :l""")
                .param("c", ts(recordedBefore)).param("l", limit)
                .query(JdbcViolationRepository::mapViolation).list();
    }

    @Override
    public boolean pointsAdded(UUID violationId) {
        return jdbc.sql("select exists (select 1 from black_point_entry where violation_id = :v)")
                .param("v", violationId).query(Boolean.class).single();
    }

    @Override
    public boolean underObjection(UUID violationId) {
        return jdbc.sql("""
                        select exists (select 1 from violation_objection
                                        where violation_id = :v and status in ('PENDING', 'COURT'))""")
                .param("v", violationId).query(Boolean.class).single();
    }

    @Override
    public boolean inInstalmentPlan(UUID violationId) {
        return jdbc.sql("""
                        select exists (select 1 from instalment_plan_violation pv
                                         join instalment_plan p on p.id = pv.plan_id
                                        where pv.violation_id = :v and p.status <> 'COMPLETED')""")
                .param("v", violationId).query(Boolean.class).single();
    }

    @Override
    public boolean coveredByPlan(UUID violationId) {
        return jdbc.sql("""
                        select exists (select 1 from instalment_plan_violation pv
                                         join instalment_plan p on p.id = pv.plan_id
                                        where pv.violation_id = :v and p.status = 'ACTIVE')""")
                .param("v", violationId).query(Boolean.class).single();
    }

    @Override
    public java.util.OptionalLong reducedFine(UUID violationId) {
        return jdbc.sql("""
                        select reduced_fine_minor from violation_objection
                         where violation_id = :v and decision = 'REDUCE'""")
                .param("v", violationId).query(Long.class).optional()
                .map(java.util.OptionalLong::of).orElse(java.util.OptionalLong.empty());
    }

    @Override
    public List<UnpaidHold> settledWithActiveHold(int limit) {
        return jdbc.sql(UNPAID_HOLD_SELECT + """
                          join violation v on v.id = h.violation_id
                         where h.lifted_at is null and (v.status <> 'OPEN' or exists (
                               select 1 from instalment_plan_violation pv join instalment_plan p on p.id = pv.plan_id
                                where pv.violation_id = v.id and p.status = 'ACTIVE'))
                         order by h.placed_at limit :l""")
                .param("l", limit).query(JdbcViolationRepository::mapUnpaidHold).list();
    }

    @Override
    public Optional<UnpaidHold> activeUnpaidHold(UUID violationId) {
        return jdbc.sql(UNPAID_HOLD_SELECT + " where h.violation_id = :v and h.lifted_at is null")
                .param("v", violationId).query(JdbcViolationRepository::mapUnpaidHold).optional();
    }

    @Override
    public void insertUnpaidHold(UnpaidHold h) {
        jdbc.sql("""
                        insert into unpaid_hold (id, violation_id, violation_number, subject_type, subject_id, hold_id,
                            placed_at)
                        values (:id, :v, :n, :type, :subject, :hold, :at)""")
                .param("id", h.id()).param("v", h.violationId()).param("n", h.violationNumber())
                .param("type", h.subject().type().name()).param("subject", h.subject().id())
                .param("hold", h.holdId()).param("at", ts(h.placedAt())).update();
    }

    @Override
    public void unpaidHoldLifted(UUID id, Instant at) {
        jdbc.sql("update unpaid_hold set lifted_at = :at where id = :id").param("at", ts(at)).param("id", id)
                .update();
    }

    private static UnpaidHold mapUnpaidHold(ResultSet rs, int row) throws SQLException {
        Timestamp lifted = rs.getTimestamp("lifted_at");
        return new UnpaidHold(rs.getObject("id", UUID.class), rs.getObject("violation_id", UUID.class),
                rs.getString("violation_number"), new UnpaidHold.Subject(
                        UnpaidHold.SubjectType.valueOf(rs.getString("subject_type")), rs.getString("subject_id")),
                rs.getString("hold_id"), rs.getTimestamp("placed_at").toInstant(),
                lifted == null ? null : lifted.toInstant());
    }

    private CatalogueEntry mapCatalogue(ResultSet rs, int row) throws SQLException {
        List<CatalogueEntry.SpeedBand> bands = new ArrayList<>();
        for (JsonNode b : json.readTree(rs.getString("speed_bands"))) {
            bands.add(new CatalogueEntry.SpeedBand(b.get("fromKmh").asInt(),
                    b.hasNonNull("toKmh") ? b.get("toKmh").asInt() : null, b.get("fineMinor").asLong(),
                    b.get("blackPoints").asInt()));
        }
        int repeatDays = rs.getInt("repeat_within_days");
        boolean noRepeat = rs.wasNull();
        long repeatFine = rs.getLong("repeat_fine_minor");
        Date to = rs.getDate("effective_to");
        return new CatalogueEntry(rs.getString("code"), rs.getInt("version_no"), rs.getString("region"),
                rs.getString("legal_article"), rs.getString("description_nl"), rs.getString("description_en"),
                rs.getLong("fine_minor"), rs.getInt("black_points"), rs.getInt("impound_days"),
                rs.getInt("suspension_days"), rs.getBoolean("court_referral"),
                CatalogueEntry.AppliesTo.valueOf(rs.getString("applies_to")), bands,
                noRepeat ? null : repeatDays, noRepeat ? null : repeatFine, rs.getString("financial_item"),
                rs.getString("beneficiary"), rs.getDate("effective_from").toLocalDate(),
                to == null ? null : to.toLocalDate(), rs.getString("published_by"),
                rs.getTimestamp("published_at").toInstant());
    }

    @Override public void insertPaperBook(gov.itms.violation.application.ViolationBooks.Book b) {
        jdbc.sql("insert into violation_paper_book(serial,region,officer_id,status,issued_at) values (:serial,:region,:officer,'ISSUED',:at)")
                .param("serial",b.serial()).param("region",b.region()).param("officer",b.officerId()).param("at",ts(b.issuedAt())).update();
    }
    @Override public Optional<gov.itms.violation.application.ViolationBooks.Book> paperBook(String serial) {
        return jdbc.sql("select * from violation_paper_book where serial=:serial").param("serial",serial)
                .query((rs,row)->new gov.itms.violation.application.ViolationBooks.Book(rs.getString("serial"),rs.getString("region"),rs.getString("officer_id"),rs.getString("status"),rs.getTimestamp("issued_at").toInstant(),instant(rs,"reconciled_at"),rs.getString("reconciled_by"))).optional();
    }
    @Override public void insertPaperBookEntry(String serial,String reportSerial,String violationNumber,Instant at) {
        jdbc.sql("insert into violation_paper_book_entry(book_serial,report_serial,violation_number,entered_at) values (:book,:report,:violation,:at)")
                .param("book",serial).param("report",reportSerial).param("violation",violationNumber).param("at",ts(at)).update();
    }
    @Override public List<String> paperBookReports(String serial) {
        return jdbc.sql("select report_serial from violation_paper_book_entry where book_serial=:serial order by report_serial")
                .param("serial",serial).query(String.class).list();
    }
    @Override public void finishPaperBook(String serial,String status,String actor,Instant at) {
        int changed=jdbc.sql("update violation_paper_book set status=:status,reconciled_at=case when :status='RECONCILED' then :at else reconciled_at end,reconciled_by=case when :status='RECONCILED' then :actor else reconciled_by end where serial=:serial and status='ISSUED'")
                .param("status",status).param("at",ts(at)).param("actor",actor).param("serial",serial).update();
        if(changed!=1)throw gov.itms.violation.domain.DomainException.conflict("VIOLATION_BOOK_CLOSED","paper book is no longer issued",List.of("VF-013"));
    }

    private static Violation mapViolation(ResultSet rs, int row) throws SQLException {
        int limit = rs.getInt("speed_limit_kmh");
        Integer speedLimit = rs.wasNull() ? null : limit;
        int measured = rs.getInt("measured_speed_kmh");
        Integer measuredSpeed = rs.wasNull() ? null : measured;
        Violation.Report report = new Violation.Report(rs.getString("catalogue_code"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("region"), rs.getString("location"),
                rs.getString("plate"), rs.getString("vin"), rs.getString("owner_file_number"),
                rs.getString("driver_file_number"), speedLimit, measuredSpeed,
                Violation.Source.valueOf(rs.getString("source")), rs.getString("device_id"),
                strings(rs.getArray("evidence_ids")), rs.getString("renter_file_number"),
                rs.getObject("rental_contract_id", UUID.class));
        return new Violation(rs.getObject("id", UUID.class), rs.getString("number"), report,
                rs.getString("catalogue_code"), rs.getInt("catalogue_version"), rs.getLong("fine_minor"),
                rs.getInt("black_points"), rs.getBoolean("repeat"), rs.getString("recorded_by"),
                rs.getTimestamp("recorded_at").toInstant(), Violation.Status.valueOf(rs.getString("status")),
                rs.getObject("payable_id", UUID.class), rs.getString("cancel_reason"),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) a.getArray()).map(String::valueOf).filter(s -> !s.isEmpty()).toList();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
    private static Instant instant(ResultSet rs,String column)throws SQLException {
        java.sql.Timestamp value=rs.getTimestamp(column);
        return value==null?null:value.toInstant();
    }
}
