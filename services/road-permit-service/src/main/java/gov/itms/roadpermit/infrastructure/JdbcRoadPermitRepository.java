package gov.itms.roadpermit.infrastructure;

import gov.itms.roadpermit.application.RoadPermitService;
import gov.itms.roadpermit.domain.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRoadPermitRepository implements RoadPermitService.Repository {
    private final JdbcClient jdbc;
    public JdbcRoadPermitRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override public void insert(RoadPermitService.Application a,String[] authorities,String actor,Instant at){
        jdbc.sql("insert into road_permit(id,number,permit_type,applicant,eligibility_reference,region,status,starts_at,ends_at,load_tonnes,width_metres,height_metres,length_metres,vehicle_limit,created_by,created_at) values (:id,:number,:type,:applicant,:eligibility,:region,'APPLIED',:starts,:ends,:load,:width,:height,:length,:limit,:actor,:at)")
                .param("id",a.id()).param("number",a.number()).param("type",a.type()).param("applicant",a.applicant()).param("eligibility",a.eligibilityReference()).param("region",a.region()).param("starts",a.startsAt()).param("ends",a.endsAt()).param("load",a.loadTonnes()).param("width",a.widthMetres()).param("height",a.heightMetres()).param("length",a.lengthMetres()).param("limit",a.vehicleLimit()).param("actor",actor).param("at",at).update();
        int i=0;for(String vehicle:a.vehicles())jdbc.sql("insert into road_permit_vehicle(permit_id,vehicle_no,vehicle_reference) values (:id,:no,:vehicle)").param("id",a.id()).param("no",++i).param("vehicle",vehicle).update();
        i=0;for(String segment:a.routeSegments())jdbc.sql("insert into road_permit_route_segment(permit_id,segment_no,segment_id) values (:id,:no,:segment)").param("id",a.id()).param("no",++i).param("segment",segment).update();
        for(String authority:authorities)jdbc.sql("insert into road_permit_authority(permit_id,authority_code,status) values (:id,:authority,'PENDING') on conflict do nothing").param("id",a.id()).param("authority",authority).update();
        jdbc.sql("insert into road_permit_status_history(permit_id,status,changed_at,changed_by) values (:id,'APPLIED',:at,:actor)").param("id",a.id()).param("at",at).param("actor",actor).update();
    }
    @Override public RoadPermitService.Application get(UUID id){return jdbc.sql("select * from road_permit where id=:id").param("id",id).query((rs,row)->map(rs)).optional().orElseThrow(()->DomainException.notFound("road permit does not exist", "RE-020", "RE-021"));}
    @Override public void approve(UUID id,String authority,String actor,String reason,Instant at){jdbc.sql("update road_permit_authority set status='APPROVED',approved_by=:actor,approved_at=:at,reason=:reason where permit_id=:id and authority_code=:authority and status='PENDING'").param("actor",actor).param("at",at).param("reason",reason).param("id",id).param("authority",authority).update();}
    @Override public List<String> pendingAuthorities(UUID id){return jdbc.sql("select authority_code from road_permit_authority where permit_id=:id and status='PENDING'").param("id",id).query(String.class).list();}
    @Override public void transition(UUID id,String status,String actor,String reason,Instant at){jdbc.sql("update road_permit set status=:status,updated_at=:at where id=:id").param("status",status).param("at",at).param("id",id).update();jdbc.sql("insert into road_permit_status_history(permit_id,status,changed_at,changed_by,reason) values (:id,:status,:at,:actor,:reason)").param("id",id).param("status",status).param("at",at).param("actor",actor).param("reason",reason).update();}
    private RoadPermitService.Application map(ResultSet r)throws SQLException{
        UUID id=r.getObject("id",UUID.class);
        List<String> vehicles=jdbc.sql("select vehicle_reference from road_permit_vehicle where permit_id=:id order by vehicle_no").param("id",id).query(String.class).list();
        List<String> routes=jdbc.sql("select segment_id from road_permit_route_segment where permit_id=:id order by segment_no").param("id",id).query(String.class).list();
        return new RoadPermitService.Application(id,r.getString("number"),r.getString("permit_type"),r.getString("applicant"),r.getString("eligibility_reference"),vehicles,routes,r.getString("region"),r.getString("status"),r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant(),nullable(r,"load_tonnes"),nullable(r,"width_metres"),nullable(r,"height_metres"),nullable(r,"length_metres"),r.getInt("vehicle_limit"));
    }
    private static Double nullable(ResultSet r,String col)throws SQLException{double n=r.getDouble(col);return r.wasNull()?null:n;}
}
