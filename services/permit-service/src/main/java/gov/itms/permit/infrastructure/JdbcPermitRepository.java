package gov.itms.permit.infrastructure;

import gov.itms.permit.application.PermitService;
import gov.itms.permit.application.Ports;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcPermitRepository implements Ports.PermitRepository {
    private final JdbcClient jdbc;
    public JdbcPermitRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public void saveType(PermitService.PermitType t, String actor, Instant at) {
        jdbc.sql("insert into permit_type(code,category,licence_class,minimum_driving_years,minimum_age,clean_record_days,medical_fit_required,test_required,validity_days,renewal_window_days,fee_minor,status,updated_by,updated_at) values (:code,:category,:licence,:years,:age,:clean,:medical,:test,:validity,:renewal,:fee,'ACTIVE',:actor,:at) on conflict(code) do update set category=excluded.category, licence_class=excluded.licence_class, minimum_driving_years=excluded.minimum_driving_years, minimum_age=excluded.minimum_age, clean_record_days=excluded.clean_record_days, medical_fit_required=excluded.medical_fit_required, test_required=excluded.test_required, validity_days=excluded.validity_days, renewal_window_days=excluded.renewal_window_days, fee_minor=excluded.fee_minor, status='ACTIVE', updated_by=excluded.updated_by, updated_at=excluded.updated_at")
                .param("code", t.code()).param("category", t.category()).param("licence", t.licenceClass())
                .param("years", t.minimumDrivingYears()).param("age", t.minimumAge()).param("clean", t.cleanRecordDays())
                .param("medical", t.medicalFitRequired()).param("test", t.testRequired()).param("validity", t.validityDays())
                .param("renewal", t.renewalWindowDays()).param("fee", t.feeMinor()).param("actor", actor).param("at", ts(at)).update();
    }
    @Override public Optional<PermitService.PermitType> type(String code) {
        return jdbc.sql("select * from permit_type where code=:code").param("code", code).query((rs, row) -> mapType(rs)).optional();
    }
    @Override public String nextNumber(Instant at) {
        int year = at.atZone(java.time.ZoneOffset.UTC).getYear();
        Integer sequence = jdbc.sql("insert into permit_number_sequence(year,next_value) values (:year,2) on conflict(year) do update set next_value=permit_number_sequence.next_value+1 returning next_value-1")
                .param("year", year).query(Integer.class).single();
        return "TP-" + year + "-" + String.format(java.util.Locale.ROOT, "%08d", sequence);
    }
    @Override public void insertPermit(PermitService.Permit p, String exceptionReason, String actor) {
        jdbc.sql("insert into training_permit(id,number,type_code,traffic_file_number,school_id,vehicle_plate,status,applied_at,exception_reason,created_by) values (:id,:number,:type,:file,:school,:vehicle,:status,:at,:exception,:actor)")
                .param("id", p.id()).param("number", p.number()).param("type", p.typeCode()).param("file", p.trafficFileNumber())
                .param("school", p.schoolId()).param("vehicle", p.vehiclePlate()).param("status", p.status())
                .param("at", ts(p.appliedAt())).param("exception", exceptionReason).param("actor", actor).update();
        jdbc.sql("insert into permit_status_history(permit_id,status,changed_at,changed_by,reason) values (:id,:status,:at,:actor,:reason)")
                .param("id", p.id()).param("status", p.status()).param("at", ts(p.appliedAt())).param("actor", actor).param("reason", exceptionReason).update();
    }
    @Override public void insertTest(PermitService.PermitTest t, String actor) {
        jdbc.sql("insert into permit_test(id,permit_id,committee,scheduled_at,result,created_by) values (:id,:permit,:committee,:at,'BOOKED',:actor)")
                .param("id", t.id()).param("permit", t.permitId()).param("committee", t.committee()).param("at", ts(t.scheduledAt())).param("actor", actor).update();
    }
    @Override public Optional<PermitService.PermitTest> openTest(UUID permitId) {
        return jdbc.sql("select id,permit_id,committee,examiner,scheduled_at,result,reason from permit_test where permit_id=:permit and result='BOOKED' order by scheduled_at limit 1")
                .param("permit", permitId).query((rs, row) -> mapTest(rs)).optional();
    }
    @Override public void recordTest(UUID id, String examiner, String result, String reason, Instant at) {
        jdbc.sql("update permit_test set examiner=:examiner,result=:result,reason=:reason,recorded_at=:at where id=:id and result='BOOKED'")
                .param("examiner", examiner).param("result", result).param("reason", reason).param("at", ts(at)).param("id", id).update();
    }
    @Override public void setStatus(UUID id, String status, String actor, String reason, Instant at) { transition(id, status, null, null, actor, reason, at); }
    @Override public void transition(UUID id, String status, Instant validFrom, Instant validTo, String actor, String reason, Instant at) {
        jdbc.sql("update training_permit set status=:status,valid_from=coalesce(cast(:valid_from as timestamptz),valid_from),valid_to=coalesce(cast(:valid_to as timestamptz),valid_to),updated_at=:at where id=:id")
                .param("status", status).param("valid_from", ts(validFrom)).param("valid_to", ts(validTo)).param("at", ts(at)).param("id", id).update();
        jdbc.sql("insert into permit_status_history(permit_id,status,changed_at,changed_by,reason) values (:id,:status,:at,:actor,:reason)")
                .param("id", id).param("status", status).param("at", ts(at)).param("actor", actor).param("reason", reason).update();
    }
    @Override public Optional<PermitService.Permit> permit(UUID id) {
        return jdbc.sql("select * from training_permit where id=:id").param("id", id).query((rs, row) -> mapPermit(rs)).optional();
    }
    @Override public List<PermitService.Permit> activeByTrafficFile(String file) {
        return jdbc.sql("select * from training_permit where traffic_file_number=:file and status='ISSUED' for update")
                .param("file", file).query((rs, row) -> mapPermit(rs)).list();
    }
    @Override public List<PermitService.Permit> expired(Instant at, int limit) {
        return jdbc.sql("select * from training_permit where status='ISSUED' and valid_to<=:at order by valid_to limit :limit for update skip locked")
                .param("at", ts(at)).param("limit", limit).query((rs, row) -> mapPermit(rs)).list();
    }
    private static PermitService.PermitType mapType(ResultSet r) throws SQLException {
        return new PermitService.PermitType(r.getString("code"), r.getString("category"), r.getString("licence_class"),
                r.getInt("minimum_driving_years"), r.getInt("minimum_age"), r.getInt("clean_record_days"),
                r.getBoolean("medical_fit_required"), r.getBoolean("test_required"), r.getInt("validity_days"),
                r.getInt("renewal_window_days"), r.getLong("fee_minor"), r.getString("status"));
    }
    private static PermitService.Permit mapPermit(ResultSet r) throws SQLException {
        return new PermitService.Permit(r.getObject("id", UUID.class), r.getString("number"), r.getString("type_code"),
                r.getString("traffic_file_number"), r.getString("school_id"), r.getString("vehicle_plate"), r.getString("status"),
                r.getTimestamp("applied_at").toInstant(), instant(r, "valid_from"), instant(r, "valid_to"));
    }
    private static PermitService.PermitTest mapTest(ResultSet r) throws SQLException {
        return new PermitService.PermitTest(r.getObject("id", UUID.class), r.getObject("permit_id", UUID.class),
                r.getString("committee"), r.getString("examiner"), r.getTimestamp("scheduled_at").toInstant(),
                r.getString("result"), r.getString("reason"));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static java.sql.Timestamp ts(java.time.Instant at) {
        return at == null ? null : java.sql.Timestamp.from(at);
    }
}
