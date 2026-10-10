package gov.itms.marine.infrastructure;

import gov.itms.marine.application.MarineService;
import gov.itms.marine.application.Ports;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcMarineRepository implements Ports.MarineRepository {
    private final JdbcClient jdbc;
    public JdbcMarineRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override public void insertCraft(MarineService.Craft c,String actor,Instant at){
        jdbc.sql("insert into marine_craft(id,hull_id,craft_type,length_metres,engine_count,capacity,home_port,owner_file,status,created_by,created_at) values (:id,:hull,:type,:length,:engines,:capacity,:port,:owner,:status,:actor,:at)")
                .param("id",c.id()).param("hull",c.hullId()).param("type",c.type()).param("length",c.lengthMetres()).param("engines",c.engineCount()).param("capacity",c.capacity()).param("port",c.homePort()).param("owner",c.owner()).param("status",c.status()).param("actor",actor).param("at",at).update();
    }
    @Override public Optional<MarineService.Craft> craft(String hull){return jdbc.sql("select * from marine_craft where hull_id=:hull").param("hull",hull).query(JdbcMarineRepository::mapCraft).optional();}
    @Override public void insertInspection(MarineService.Inspection i,String actor){jdbc.sql("insert into marine_inspection(id,hull_id,purpose,result,workshop,document_id,inspected_at,recorded_by) values (:id,:hull,:purpose,:result,:workshop,:document,:at,:actor)").param("id",i.id()).param("hull",i.hullId()).param("purpose",i.purpose()).param("result",i.result()).param("workshop",i.workshop()).param("document",i.documentId()).param("at",i.inspectedAt()).param("actor",actor).update();}
    @Override public Optional<String> latestRenewalResult(String hull){return jdbc.sql("select result from marine_inspection where hull_id=:hull and purpose='RENEWAL' order by inspected_at desc limit 1").param("hull",hull).query(String.class).optional();}
    @Override public void insertLicence(MarineService.OperatorLicence l,String actor){jdbc.sql("insert into marine_operator_licence(id,number,traffic_file_number,craft_type,status,issued_at,expires_at,issued_by) values (:id,:number,:file,:type,:status,:now,:expiry,:actor)").param("id",l.id()).param("number",l.number()).param("file",l.trafficFile()).param("type",l.craftType()).param("status",l.status()).param("now",l.issuedAt()).param("expiry",l.expiresAt()).param("actor",actor).update();}
    @Override public void transfer(String hull,String owner,Instant at){jdbc.sql("update marine_craft set owner_file=:owner,updated_at=:at where hull_id=:hull").param("owner",owner).param("at",at).param("hull",hull).update();}
    @Override public void renew(String hull,Instant expiry,Instant at){jdbc.sql("update marine_craft set registration_expires_at=:expiry,status='ACTIVE',updated_at=:at where hull_id=:hull").param("expiry",expiry).param("at",at).param("hull",hull).update();}
    private static MarineService.Craft mapCraft(ResultSet r,int row)throws SQLException{return new MarineService.Craft(r.getObject("id",java.util.UUID.class),r.getString("hull_id"),r.getString("craft_type"),r.getDouble("length_metres"),r.getInt("engine_count"),r.getInt("capacity"),r.getString("home_port"),r.getString("owner_file"),r.getString("status"));}
}
