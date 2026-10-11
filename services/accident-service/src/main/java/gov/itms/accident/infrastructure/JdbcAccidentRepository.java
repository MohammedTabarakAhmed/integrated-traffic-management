package gov.itms.accident.infrastructure;

import gov.itms.accident.application.AccidentService;
import gov.itms.accident.application.Ports;
import gov.itms.accident.domain.Accident;
import gov.itms.accident.domain.DomainException;
import gov.itms.accident.domain.RepairPermit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for accident reports. */
public class JdbcAccidentRepository implements Ports.AccidentRepository {
    private final JdbcClient jdbc;
    public JdbcAccidentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public String nextReportNumber(Instant occurredAt) {
        long sequence = jdbc.sql("select nextval('accident_report_seq')").query(Long.class).single();
        return "A-" + occurredAt.atZone(java.time.ZoneOffset.UTC).getYear() + "-"
                + String.format(java.util.Locale.ROOT, "%08d", sequence);
    }

    @Override
    public void insert(UUID id, String number, String status, AccidentService.Report report, String actorId,
                       Instant at) {
        jdbc.sql("insert into accident_report (id,report_number,region,occurred_at,location,road_condition,weather,"
                        + "status,origin,recorded_by,recorded_at) values (:id,:number,:region,:occurred,:location,"
                        + ":road,:weather,:status,:origin,:actor,:at)")
                .param("id", id).param("number", number).param("region", report.region())
                .param("occurred", ts(report.occurredAt())).param("location", report.location())
                .param("road", report.roadCondition()).param("weather", report.weather()).param("status", status)
                .param("origin", report.origin().name()).param("actor", actorId).param("at", ts(at)).update();
        int index = 0;
        for (AccidentService.PartyInput p : report.parties()) {
            jdbc.sql("insert into accident_party (accident_id,party_no,traffic_file_number,vehicle_plate,role,"
                        + "seat_position,injury_degree,transport_method,intoxication_status,fault_percent,agrees,unknown_party) "
                        + "values (:id,:no,:file,:plate,:role,:seat,:injury,:transport,:intoxication,:fault,:agrees,:unknown)")
                    .param("id", id).param("no", ++index).param("file", p.trafficFileNumber())
                    .param("plate", p.vehiclePlate()).param("role", p.role().name()).param("seat", p.seatPosition())
                    .param("injury", p.injuryDegree()).param("transport", p.transportMethod())
                    .param("intoxication", p.intoxicationStatus()).param("fault", p.faultPercent())
                    .param("agrees", p.agrees()).param("unknown", p.unknownParty()).update();
        }
        for (String documentId : report.evidenceDocumentIds()) {
            jdbc.sql("insert into accident_evidence (accident_id,document_id) values (:id,:document)")
                    .param("id", id).param("document", documentId).update();
        }
        if ("FAULT_DECIDED".equals(status)) {
            jdbc.sql("insert into accident_decision(accident_id,decision_type,reason,decided_by,decided_at) "
                            + "values (:id,'FAULT','All listed parties agreed to the fault shares',:actor,:at)")
                    .param("id", id).param("actor", actorId).param("at", ts(at)).update();
        }
    }

    @Override
    public void decideFault(UUID id, Map<Integer, Integer> shares, String reason, String actorId, Instant decidedAt) {
        int changed = jdbc.sql("update accident_report set status='FAULT_DECIDED' where id=:id "
                        + "and status in ('REPORTED','UNDER_INVESTIGATION')")
                .param("id", id).update();
        if (changed != 1) {
            throw new DomainException(DomainException.Kind.CONFLICT, "ACCIDENT_NOT_OPEN",
                    "accident report is no longer open for a fault decision", List.of("AC-005"));
        }
        shares.forEach((partyNo, share) -> jdbc.sql("update accident_party set fault_percent=:share "
                        + "where accident_id=:id and party_no=:party")
                .param("share", share).param("id", id).param("party", partyNo).update());
        jdbc.sql("insert into accident_decision(accident_id,decision_type,reason,decided_by,decided_at) "
                        + "values (:id,'FAULT',:reason,:actor,:at)")
                .param("id", id).param("reason", reason).param("actor", actorId).param("at", ts(decidedAt)).update();
    }

    @Override
    public Optional<Accident> find(UUID id) {
        return jdbc.sql("select * from accident_report where id=:id").param("id", id)
                .query((rs, row) -> map(rs, id)).optional();
    }

    @Override
    public void insertRepairPermit(RepairPermit permit, String codeHash) {
        jdbc.sql("insert into repair_permit(id,number,accident_id,vehicle_plate,region,issued_at,status,code_hash) "
                        + "select :id,:number,a.id,:plate,:region,:at,:status,:hash from accident_report a "
                        + "where a.report_number=:report")
                .param("id", permit.id()).param("number", permit.number()).param("plate", permit.vehiclePlate())
                .param("region", permit.region()).param("at", ts(permit.issuedAt())).param("status", permit.status())
                .param("hash", codeHash).param("report", permit.accidentReportNumber()).update();
        for (int i = 0; i < permit.damagedParts().size(); i++) {
            jdbc.sql("insert into repair_permit_part(permit_id,part_no,part_name) values (:id,:no,:part)")
                    .param("id", permit.id()).param("no", i + 1).param("part", permit.damagedParts().get(i)).update();
        }
    }

    @Override
    public Optional<RepairPermit> repairPermit(String number) {
        Optional<RepairPermit> found = jdbc.sql("select p.*, a.report_number as accident_report_number from repair_permit p "
                        + "join accident_report a on a.id = p.accident_id where p.number=:number")
                .param("number", number).query((rs, row) -> mapRepairPermit(rs)).optional();
        return found.map(p -> new RepairPermit(p.id(), p.number(), p.accidentReportNumber(), p.vehiclePlate(),
                p.region(), jdbc.sql("select part_name from repair_permit_part where permit_id=:id order by part_no")
                .param("id", p.id()).query(String.class).list(), p.issuedAt(), p.status(), p.valid()));
    }

    @Override
    public boolean verifyRepairPermit(String number, String codeHash) {
        return jdbc.sql("select exists(select 1 from repair_permit where number=:number and code_hash=:hash "
                        + "and status='ISSUED')")
                .param("number", number).param("hash", codeHash).query(Boolean.class).single();
    }

    @Override
    public void cancelRepairPermit(String number, String actorId, Instant at, String reason) {
        int changed = jdbc.sql("update repair_permit set status='CANCELLED' where number=:number and status='ISSUED'")
                .param("number", number).update();
        if (changed != 1) {
            throw new DomainException(DomainException.Kind.CONFLICT, "REPAIR_PERMIT_NOT_ISSUED",
                    "only an issued repair permit can be cancelled", List.of("AC-020"));
        }
    }

    private static RepairPermit mapRepairPermit(ResultSet rs) throws SQLException {
        return new RepairPermit(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getString("accident_report_number"), rs.getString("vehicle_plate"), rs.getString("region"),
                List.of(), rs.getTimestamp("issued_at").toInstant(), rs.getString("status"), true);
    }

    private Accident map(ResultSet rs, UUID id) throws SQLException {
        List<Accident.Party> parties = jdbc.sql("select * from accident_party where accident_id=:id order by party_no")
                .param("id", id).query(JdbcAccidentRepository::mapParty).list();
        List<String> evidence = jdbc.sql("select document_id from accident_evidence where accident_id=:id order by document_id")
                .param("id", id).query(String.class).list();
        return new Accident(rs.getObject("id", UUID.class), rs.getString("report_number"), rs.getString("region"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("location"), rs.getString("road_condition"),
                rs.getString("weather"), rs.getString("status"), rs.getString("origin"), rs.getString("recorded_by"),
                rs.getTimestamp("recorded_at").toInstant(), parties, evidence);
    }

    private static Accident.Party mapParty(ResultSet rs, int row) throws SQLException {
        Number fault = (Number) rs.getObject("fault_percent");
        return new Accident.Party(rs.getInt("party_no"), rs.getString("traffic_file_number"), rs.getString("vehicle_plate"),
                Accident.Role.valueOf(rs.getString("role")), rs.getString("seat_position"),
                rs.getString("injury_degree"), rs.getString("transport_method"),
                rs.getString("intoxication_status"), fault == null ? null : fault.intValue(), rs.getBoolean("agrees"),
                rs.getBoolean("unknown_party"));
    }

    private static java.sql.Timestamp ts(java.time.Instant at) {
        return at == null ? null : java.sql.Timestamp.from(at);
    }
}
