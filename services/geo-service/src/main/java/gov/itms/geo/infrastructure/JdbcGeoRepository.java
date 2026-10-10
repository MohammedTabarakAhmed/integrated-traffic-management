package gov.itms.geo.infrastructure;

import gov.itms.geo.application.GeoService;
import gov.itms.geo.application.Ports;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcGeoRepository implements Ports.GeoRepository {
    private final JdbcClient jdbc;
    public JdbcGeoRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Override public void insert(GeoService.Node n,String actor,Instant at) {
        jdbc.sql("insert into geo_node(id,parent_id,node_type,code,name_en,name_ar,region,latitude,longitude,speed_limit_kmh,bridge_load_tonnes,restrictions,opening_hours,road_type,lane_count,direction,owning_authority,created_by,created_at) values (:id,:parent,:type,:code,:en,:ar,:region,:lat,:lon,:speed,:load,:restrictions,:hours,:roadType,:lanes,:direction,:authority,:actor,:at)")
                .param("id",n.id()).param("parent",n.parentId()).param("type",n.type()).param("code",n.code()).param("en",n.nameEn())
                .param("ar",n.nameAr()).param("region",n.region()).param("lat",n.latitude()).param("lon",n.longitude())
                .param("speed",n.speedLimitKmh()).param("load",n.bridgeLoadTonnes()).param("restrictions",n.restrictions())
                .param("hours",n.openingHours()).param("roadType",n.roadType()).param("lanes",n.laneCount())
                .param("direction",n.direction()).param("authority",n.owningAuthority()).param("actor",actor).param("at",at).update();
    }
    @Override public Optional<GeoService.Node> find(UUID id) {
        return jdbc.sql("select * from geo_node where id=:id").param("id",id).query(JdbcGeoRepository::map).optional();
    }
    @Override public List<GeoService.Node> children(UUID parent,String type) {
        return jdbc.sql("select * from geo_node where parent_id=:parent and (:type is null or node_type=:type) order by code")
                .param("parent",parent).param("type",type).query(JdbcGeoRepository::map).list();
    }
    private static GeoService.Node map(ResultSet r,int row)throws SQLException {
        return new GeoService.Node(r.getObject("id",UUID.class),r.getObject("parent_id",UUID.class),r.getString("node_type"),
                r.getString("code"),r.getString("name_en"),r.getString("name_ar"),r.getString("region"),
                nullableDouble(r,"latitude"),nullableDouble(r,"longitude"),nullableDouble(r,"speed_limit_kmh"),
                nullableDouble(r,"bridge_load_tonnes"),r.getString("restrictions"),r.getString("opening_hours"),
                r.getString("road_type"),nullableInt(r,"lane_count"),r.getString("direction"),r.getString("owning_authority"));
    }
    private static Double nullableDouble(ResultSet r,String col)throws SQLException { double v=r.getDouble(col);return r.wasNull()?null:v; }
    private static Integer nullableInt(ResultSet r,String col)throws SQLException { int v=r.getInt(col);return r.wasNull()?null:v; }
}
