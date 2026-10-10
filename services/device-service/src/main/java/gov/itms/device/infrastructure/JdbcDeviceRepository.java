package gov.itms.device.infrastructure;

import gov.itms.device.application.Ports;
import gov.itms.device.domain.Capture;
import gov.itms.device.domain.Device;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL storage for enforcement devices and immutable capture facts. */
class JdbcDeviceRepository implements Ports.DeviceRepository {

    private static final String DEVICE = "select id,code,type,owner,region,latitude,longitude,road,direction,"
            + "speed_limit_kmh,status,calibrated_at,calibration_expires_at,created_at,created_by from enforcement_device";
    private static final String CAPTURE = "select id,source_key,device_code,captured_at,catalogue_code,region,plate,"
            + "location,speed_limit_kmh,measured_speed_kmh,evidence_id,owner_file_number,status,quality_reasons,violation_number,received_at "
            + "from device_capture";

    private final JdbcClient jdbc;

    JdbcDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Device d) {
        jdbc.sql("""
                insert into enforcement_device (id,code,type,owner,region,latitude,longitude,road,direction,
                    speed_limit_kmh,status,calibrated_at,calibration_expires_at,created_at,created_by)
                values (:id,:code,:type,:owner,:region,:lat,:lon,:road,:direction,:limit,:status,:calibrated,:expires,
                    :created,:by)
                """).param("id", d.id()).param("code", d.code()).param("type", d.type().name())
                .param("owner", d.owner()).param("region", d.region()).param("lat", d.latitude())
                .param("lon", d.longitude()).param("road", d.road()).param("direction", d.direction())
                .param("limit", d.speedLimitKmh()).param("status", d.status().name())
                .param("calibrated", timestamp(d.calibratedAt())).param("expires", timestamp(d.calibrationExpiresAt()))
                .param("created", Timestamp.from(d.createdAt())).param("by", d.createdBy()).update();
    }

    @Override
    public Optional<Device> device(String code) {
        return jdbc.sql(DEVICE + " where code=:code").param("code", code).query(this::mapDevice).optional();
    }

    @Override
    public Optional<Device> deviceForUpdate(String code) {
        return jdbc.sql(DEVICE + " where code=:code for update").param("code", code).query(this::mapDevice).optional();
    }

    @Override
    public void updateCalibration(Device d, String by, Instant at) {
        jdbc.sql("update enforcement_device set calibrated_at=:calibrated, calibration_expires_at=:expires"
                        + " where id=:id")
                .param("calibrated", Timestamp.from(d.calibratedAt()))
                .param("expires", Timestamp.from(d.calibrationExpiresAt())).param("id", d.id()).update();
        jdbc.sql("insert into enforcement_device_calibration (id,device_id,calibrated_at,expires_at,recorded_by,"
                        + "recorded_at) values (:id,:device,:from,:until,:by,:at)")
                .param("id", UUID.randomUUID()).param("device", d.id())
                .param("from", Timestamp.from(d.calibratedAt())).param("until", Timestamp.from(d.calibrationExpiresAt()))
                .param("by", by).param("at", Timestamp.from(at)).update();
    }

    @Override
    public List<Device> devices(String region) {
        return jdbc.sql(DEVICE + " where region=:region order by code").param("region", region)
                .query(this::mapDevice).list();
    }

    @Override
    public boolean insertCapture(Capture c) {
        return jdbc.sql("""
                insert into device_capture (id,source_key,device_code,captured_at,received_at,catalogue_code,region,
                    plate,location,speed_limit_kmh,measured_speed_kmh,evidence_id,owner_file_number,status,quality_reasons,violation_number)
                values (:id,:source,:device,:captured,:received,:catalogue,:region,:plate,:location,:limit,:speed,:evidence,
                    :owner,:status,:reasons,:violation) on conflict (source_key) do nothing
                """).param("id", c.id()).param("source", c.sourceKey()).param("device", c.deviceCode())
                .param("captured", Timestamp.from(c.capturedAt())).param("received", Timestamp.from(c.receivedAt()))
                .param("catalogue", c.catalogueCode()).param("region", c.region()).param("plate", c.plate())
                .param("location", c.location()).param("limit", c.speedLimitKmh())
                .param("speed", c.measuredSpeedKmh()).param("evidence", c.evidenceId())
                .param("owner", c.ownerFileNumber()).param("status", c.status().name())
                .param("reasons", c.qualityReasons().toArray(String[]::new)).param("violation", c.violationNumber())
                .update() == 1;
    }

    @Override
    public Optional<Capture> capture(UUID id) {
        return jdbc.sql(CAPTURE + " where id=:id").param("id", id).query(this::mapCapture).optional();
    }

    @Override
    public Optional<Capture> captureBySource(String sourceKey) {
        return jdbc.sql(CAPTURE + " where source_key=:key").param("key", sourceKey).query(this::mapCapture).optional();
    }

    @Override
    public Optional<Capture> captureForUpdate(UUID id) {
        return jdbc.sql(CAPTURE + " where id=:id for update").param("id", id).query(this::mapCapture).optional();
    }

    @Override
    public List<Capture> captures(Capture.Status status, String region, int limit) {
        return jdbc.sql(CAPTURE + " where status=:status and region=:region order by received_at limit :limit")
                .param("status", status.name()).param("region", region).param("limit", limit)
                .query(this::mapCapture).list();
    }

    @Override
    public void updateCapture(Capture c) {
        jdbc.sql("update device_capture set status=:status,owner_file_number=:owner,violation_number=:violation "
                        + "where id=:id")
                .param("status", c.status().name()).param("owner", c.ownerFileNumber())
                .param("violation", c.violationNumber()).param("id", c.id()).update();
    }

    private Device mapDevice(ResultSet rs, int row) throws SQLException {
        return new Device(rs.getObject("id", UUID.class), rs.getString("code"),
                Device.Type.valueOf(rs.getString("type")), rs.getString("owner"), rs.getString("region"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("road"),
                rs.getString("direction"), rs.getInt("speed_limit_kmh"), Device.Status.valueOf(rs.getString("status")),
                instant(rs, "calibrated_at"), instant(rs, "calibration_expires_at"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"));
    }

    private Capture mapCapture(ResultSet rs, int row) throws SQLException {
        Array array = rs.getArray("quality_reasons");
        String[] values = array == null ? new String[0] : (String[]) array.getArray();
        return new Capture(rs.getObject("id", UUID.class), rs.getString("source_key"), rs.getString("device_code"),
                rs.getTimestamp("captured_at").toInstant(), rs.getString("catalogue_code"), rs.getString("region"),
                rs.getString("plate"), rs.getString("location"), rs.getInt("speed_limit_kmh"),
                (Integer) rs.getObject("measured_speed_kmh"),
                rs.getString("evidence_id"), rs.getString("owner_file_number"),
                Capture.Status.valueOf(rs.getString("status")), List.of(values), rs.getString("violation_number"),
                rs.getTimestamp("received_at").toInstant());
    }

    private static Timestamp timestamp(Instant i) { return i == null ? null : Timestamp.from(i); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
