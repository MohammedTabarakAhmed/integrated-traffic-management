package gov.itms.vehicle.infrastructure;

import gov.itms.vehicle.application.Ports;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Vehicles, plates and registration requests in PostgreSQL (V1). */
class JdbcVehicleRepository implements Ports.VehicleRepository {

    static final String VEHICLE_SELECT = """
            select id, vin, engine_no, make, model, model_year, body_type, colours, fuel_type, steering, seats,
                empty_weight_kg, gross_weight_kg, axles, cylinders, category, owner_file_number, region, status,
                registration_expiry, plate_id, plate_number, created_at, version, customs_certificate_no,
                engine_capacity_cc, origin
            from vehicle""";
    private static final String SERIES_SELECT = """
            select code, region, plate_category, colour_code, design, prefix, first_number, last_number,
                premium_pattern, active
            from plate_series""";
    static final String REQUEST_SELECT = """
            select id, vehicle_id, kind, status, quote_id, payable_id, amount_minor, currency, new_expiry, plate_id,
                requested_by, channel, created_at, completed_at, payload::text as payload, subject_ref, payer_file
            from registration_request""";
    static final String PLATE_SELECT = """
            select id, series_code, number, display, status, vehicle_id, holder_file_number, changed_at, hold_until,
                serial_no
            from plate""";
    private static final int MAX_SCAN = 10_000;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcVehicleRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- vehicles

    @Override
    public boolean activeVinExists(String vin) {
        return jdbc.sql("select exists (select 1 from vehicle where vin = :v and status <> 'DEREGISTERED')")
                .param("v", vin).query(Boolean.class).single();
    }

    @Override
    public void insertVehicle(Vehicle v) {
        Vehicle.Spec s = v.spec();
        jdbc.sql("""
                        insert into vehicle (id, vin, engine_no, make, model, model_year, body_type, colours,
                            fuel_type, steering, seats, empty_weight_kg, gross_weight_kg, axles, cylinders, category,
                            owner_file_number, region, status, created_at, customs_certificate_no, engine_capacity_cc,
                            origin)
                        values (:id, :vin, :engine, :make, :model, :year, :body, string_to_array(:colours, ','),
                            :fuel, :steering, :seats, :empty, :gross, :axles, :cylinders, :category, :owner,
                            :region, :status, :at, :customs, :capacity, :origin)""")
                .param("id", v.id()).param("vin", v.vin()).param("engine", s.engineNo()).param("make", s.make())
                .param("model", s.model()).param("year", s.modelYear()).param("body", s.bodyType())
                .param("colours", String.join(",", s.colours())).param("fuel", s.fuelType())
                .param("steering", s.steering()).param("seats", s.seats()).param("empty", s.emptyWeightKg())
                .param("gross", s.grossWeightKg()).param("axles", s.axles()).param("cylinders", s.cylinders())
                .param("category", s.category()).param("owner", v.ownerFileNumber()).param("region", v.region())
                .param("status", v.status().name()).param("at", Timestamp.from(v.createdAt()))
                .param("customs", v.customsCertificateNo()).param("capacity", s.engineCapacityCc())
                .param("origin", s.origin()).update();
    }

    @Override
    public Optional<Vehicle> findByVin(String vin) {
        return jdbc.sql(VEHICLE_SELECT + " where vin = :v order by (status = 'DEREGISTERED'), created_at desc limit 1")
                .param("v", vin).query(JdbcVehicleRepository::mapVehicle).optional();
    }

    @Override
    public Optional<String> vinByPlate(String display) {
        return jdbc.sql("""
                select v.vin from plate p join vehicle v on v.id = p.vehicle_id
                where upper(p.display) = upper(:d) and p.status = 'ASSIGNED'
                order by p.changed_at desc limit 1
                """).param("d", display).query(String.class).optional();
    }

    @Override
    public Optional<Vehicle> findForUpdate(UUID id) {
        return jdbc.sql(VEHICLE_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcVehicleRepository::mapVehicle).optional();
    }

    @Override
    public void updateVehicle(Vehicle v) {
        int n = jdbc.sql("""
                        update vehicle set owner_file_number = :owner, region = :region, status = :status,
                            registration_expiry = :expiry, plate_id = :plate, plate_number = :plateNumber,
                            engine_no = :engine, body_type = :body, colours = string_to_array(:colours, ','),
                            category = :category, customs_certificate_no = :customs,
                            engine_capacity_cc = :capacity, origin = :origin, version = version + 1
                        where id = :id and version = :version""")
                .param("owner", v.ownerFileNumber()).param("region", v.region()).param("status", v.status().name())
                .param("expiry", v.registrationExpiry() == null ? null : Date.valueOf(v.registrationExpiry()))
                .param("plate", v.plateId()).param("plateNumber", v.plateNumber()).param("id", v.id())
                .param("engine", v.spec().engineNo()).param("body", v.spec().bodyType())
                .param("colours", String.join(",", v.spec().colours())).param("category", v.spec().category())
                .param("customs", v.customsCertificateNo()).param("capacity", v.spec().engineCapacityCc())
                .param("origin", v.spec().origin()).param("version", v.version()).update();
        if (n != 1) {
            throw new OptimisticLockingFailureException("vehicle " + v.vin() + " changed concurrently");
        }
        v.saved();
    }

    @Override
    public void history(UUID vehicleId, String event, String actorId, Map<String, Object> detail, Instant at) {
        jdbc.sql("""
                        insert into vehicle_history (vehicle_id, event, actor_id, detail, at)
                        values (:v, :e, :a, cast(:d as jsonb), :at)""")
                .param("v", vehicleId).param("e", event).param("a", actorId)
                .param("d", json.writeValueAsString(detail)).param("at", Timestamp.from(at)).update();
    }

    @Override
    public List<HistoryEntry> history(UUID vehicleId) {
        return jdbc.sql("select event, actor_id, detail::text as detail, at from vehicle_history where vehicle_id = :v"
                        + " order by id")
                .param("v", vehicleId)
                .query((rs, n) -> new HistoryEntry(rs.getString("event"), rs.getString("actor_id"),
                        map(json.readTree(rs.getString("detail"))), rs.getTimestamp("at").toInstant()))
                .list();
    }

    // ---- inspections

    @Override
    public void insertInspection(Registration.Inspection i) {
        jdbc.sql("""
                        insert into inspection_result (id, vehicle_id, centre_code, inspected_on, odometer_km, results,
                            passed, photo_ids, recorded_by, recorded_at)
                        values (:id, :v, :centre, :on, :km, cast(:results as jsonb), :passed,
                            string_to_array(:photos, ','), :by, :at)""")
                .param("id", i.id()).param("v", i.vehicleId()).param("centre", i.centreCode())
                .param("on", Date.valueOf(i.inspectedOn())).param("km", i.odometerKm())
                .param("results", json.writeValueAsString(i.results())).param("passed", i.passed())
                .param("photos", String.join(",", i.photoDocumentIds())).param("by", i.recordedBy())
                .param("at", Timestamp.from(i.recordedAt())).update();
    }

    @Override
    public Optional<Registration.Inspection> latestInspection(UUID vehicleId) {
        return jdbc.sql("""
                        select id, vehicle_id, centre_code, inspected_on, odometer_km, results::text as results, passed,
                            photo_ids, recorded_by, recorded_at
                        from inspection_result where vehicle_id = :v order by inspected_on desc, recorded_at desc
                        limit 1""")
                .param("v", vehicleId)
                .query((rs, n) -> {
                    Map<String, String> results = new LinkedHashMap<>();
                    json.readTree(rs.getString("results")).properties()
                            .forEach(e -> results.put(e.getKey(), e.getValue().asString()));
                    return new Registration.Inspection(rs.getObject("id", UUID.class),
                            rs.getObject("vehicle_id", UUID.class), rs.getString("centre_code"),
                            rs.getDate("inspected_on").toLocalDate(), rs.getLong("odometer_km"), results,
                            rs.getBoolean("passed"), strings(rs.getArray("photo_ids")), rs.getString("recorded_by"),
                            rs.getTimestamp("recorded_at").toInstant());
                })
                .optional();
    }

    // ---- plates

    @Override
    public void saveSeries(Plates.Series s) {
        jdbc.sql("""
                        insert into plate_series (code, region, plate_category, colour_code, design, prefix,
                            first_number, last_number, next_number, premium_pattern, active, updated_at)
                        values (:code, :region, :category, :colour, :design, :prefix, :first, :last, :first, :premium,
                            :active, now())
                        on conflict (code) do update set plate_category = excluded.plate_category,
                            colour_code = excluded.colour_code, design = excluded.design,
                            last_number = greatest(excluded.last_number, plate_series.next_number - 1),
                            premium_pattern = excluded.premium_pattern, active = excluded.active, updated_at = now()""")
                .param("code", s.code()).param("region", s.region()).param("category", s.plateCategory())
                .param("colour", s.colourCode()).param("design", s.design()).param("prefix", s.prefix())
                .param("first", s.firstNumber()).param("last", s.lastNumber()).param("premium", s.premiumPattern())
                .param("active", s.active()).update();
    }

    @Override
    public Optional<Plates.Series> findSeries(String code) {
        return jdbc.sql(SERIES_SELECT + " where code = :c").param("c", code)
                .query(JdbcVehicleRepository::mapSeries).optional();
    }

    @Override
    public List<Plates.Series> activeSeries(String region, String plateCategory) {
        return jdbc.sql(SERIES_SELECT + " where region = :r and plate_category = :c and active order by code")
                .param("r", region).param("c", plateCategory).query(JdbcVehicleRepository::mapSeries).list();
    }

    @Override
    public Optional<Long> nextFreeNumber(Plates.Series series) {
        long next = jdbc.sql("select next_number from plate_series where code = :c for update")
                .param("c", series.code()).query(Long.class).single();
        for (long n = next, scanned = 0; n <= series.lastNumber() && scanned < MAX_SCAN; n++, scanned++) {
            if (series.premium(n)) {
                continue;
            }
            boolean used = jdbc.sql("select exists (select 1 from plate where series_code = :c and number = :n"
                            + " and status in ('RESERVED', 'ASSIGNED', 'RETAINED', 'WITHDRAWN', 'LOST'))")
                    .param("c", series.code()).param("n", n).query(Boolean.class).single();
            if (!used) {
                jdbc.sql("update plate_series set next_number = :n where code = :c").param("n", n + 1)
                        .param("c", series.code()).update();
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    @Override
    public void insertPlate(Plates.Plate p) {
        jdbc.sql("""
                        insert into plate (id, series_code, number, display, status, vehicle_id, holder_file_number,
                            changed_at, hold_until, serial_no)
                        values (:id, :series, :number, :display, :status, :vehicle, :holder, :at, :until, :serial)""")
                .param("id", p.id()).param("series", p.seriesCode()).param("number", p.number())
                .param("display", p.display()).param("status", p.status().name()).param("vehicle", p.vehicleId())
                .param("holder", p.holderFileNumber()).param("at", Timestamp.from(p.changedAt()))
                .param("until", p.holdUntil() == null ? null : Date.valueOf(p.holdUntil()))
                .param("serial", p.serialNo()).update();
    }

    @Override
    public Optional<Plates.Plate> findPlate(UUID id) {
        return jdbc.sql(PLATE_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcVehicleRepository::mapPlate).optional();
    }

    @Override
    public void updatePlate(Plates.Plate p) {
        jdbc.sql("""
                        update plate set status = :status, vehicle_id = :vehicle, holder_file_number = :holder,
                            changed_at = :at, hold_until = :until, serial_no = :serial
                        where id = :id""")
                .param("status", p.status().name()).param("vehicle", p.vehicleId())
                .param("holder", p.holderFileNumber()).param("at", Timestamp.from(p.changedAt()))
                .param("until", p.holdUntil() == null ? null : Date.valueOf(p.holdUntil()))
                .param("serial", p.serialNo()).param("id", p.id()).update();
    }

    // ---- registration requests

    @Override
    public void insertRequest(Registration.Request r) {
        jdbc.sql("""
                        insert into registration_request (id, vehicle_id, kind, status, quote_id, payable_id,
                            amount_minor, currency, new_expiry, plate_id, requested_by, channel, created_at, payload,
                            subject_ref, payer_file)
                        values (:id, :v, :kind, :status, :quote, :payable, :amount, :currency, :expiry, :plate, :by,
                            :channel, :at, cast(:payload as jsonb), :subject, :payer)""")
                .param("id", r.id()).param("v", r.vehicleId()).param("kind", r.kind().name())
                .param("status", r.status().name()).param("quote", r.quoteId()).param("payable", r.payableId())
                .param("amount", r.amountMinor()).param("currency", r.currency())
                .param("expiry", r.newExpiry() == null ? null : Date.valueOf(r.newExpiry())).param("plate", r.plateId())
                .param("by", r.requestedBy()).param("channel", r.channel()).param("at", Timestamp.from(r.createdAt()))
                .param("payload", json.writeValueAsString(r.payload())).param("subject", r.subjectRef())
                .param("payer", r.payerFile()).update();
    }

    @Override
    public Optional<Registration.Request> findRequestForUpdate(UUID id) {
        return jdbc.sql(REQUEST_SELECT + " where id = :id for update").param("id", id)
                .query(this::mapRequest).optional();
    }

    @Override
    public Optional<Registration.Request> openRequest(UUID vehicleId) {
        return jdbc.sql(REQUEST_SELECT + " where vehicle_id = :v and status in ('AWAITING_PAYMENT', 'AWAITING_CONSENT')")
                .param("v", vehicleId).query(this::mapRequest).optional();
    }

    @Override
    public void updateRequest(Registration.Request r) {
        jdbc.sql("""
                        update registration_request set status = :s, completed_at = :at, quote_id = :quote,
                            payable_id = :payable, amount_minor = :amount, currency = :currency, plate_id = :plate,
                            payload = cast(:payload as jsonb)
                        where id = :id""")
                .param("s", r.status().name())
                .param("at", r.completedAt() == null ? null : Timestamp.from(r.completedAt()))
                .param("quote", r.quoteId()).param("payable", r.payableId()).param("amount", r.amountMinor())
                .param("currency", r.currency()).param("plate", r.plateId())
                .param("payload", json.writeValueAsString(r.payload())).param("id", r.id()).update();
    }

    // ---- mapping

    static Vehicle mapVehicle(ResultSet rs, int row) throws SQLException {
        Vehicle.Spec spec = new Vehicle.Spec(rs.getString("engine_no"), rs.getString("make"), rs.getString("model"),
                rs.getInt("model_year"), rs.getString("body_type"), strings(rs.getArray("colours")),
                rs.getString("fuel_type"), rs.getString("steering"), rs.getInt("seats"),
                rs.getInt("empty_weight_kg"), rs.getInt("gross_weight_kg"), rs.getInt("axles"),
                rs.getInt("cylinders"), rs.getString("category"),
                rs.getObject("engine_capacity_cc", Integer.class), rs.getString("origin"));
        Date expiry = rs.getDate("registration_expiry");
        Vehicle v = Vehicle.restore(rs.getObject("id", UUID.class), rs.getString("vin"), spec,
                rs.getTimestamp("created_at").toInstant(), rs.getString("owner_file_number"), rs.getString("region"),
                Vehicle.Status.valueOf(rs.getString("status")), expiry == null ? null : expiry.toLocalDate(),
                rs.getObject("plate_id", UUID.class), rs.getString("plate_number"), rs.getLong("version"));
        String customs = rs.getString("customs_certificate_no");
        if (customs != null) {
            v.linkCertificate(customs);
        }
        return v;
    }

    static Plates.Plate mapPlate(ResultSet rs, int row) throws SQLException {
        Date until = rs.getDate("hold_until");
        return new Plates.Plate(rs.getObject("id", UUID.class), rs.getString("series_code"), rs.getLong("number"),
                rs.getString("display"), Plates.Status.valueOf(rs.getString("status")),
                rs.getObject("vehicle_id", UUID.class), rs.getString("holder_file_number"),
                rs.getTimestamp("changed_at").toInstant(), until == null ? null : until.toLocalDate(),
                rs.getString("serial_no"));
    }

    static Plates.Series mapSeries(ResultSet rs, int row) throws SQLException {
        return new Plates.Series(rs.getString("code"), rs.getString("region"), rs.getString("plate_category"),
                rs.getString("colour_code"), rs.getString("design"), rs.getString("prefix"),
                rs.getLong("first_number"), rs.getLong("last_number"), rs.getString("premium_pattern"),
                rs.getBoolean("active"));
    }

    Registration.Request mapRequest(ResultSet rs, int row) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        Date expiry = rs.getDate("new_expiry");
        Map<String, String> payload = new LinkedHashMap<>();
        json.readTree(rs.getString("payload")).properties()
                .forEach(e -> payload.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
        return new Registration.Request(rs.getObject("id", UUID.class), rs.getObject("vehicle_id", UUID.class),
                Registration.Kind.valueOf(rs.getString("kind")), Registration.Status.valueOf(rs.getString("status")),
                rs.getString("quote_id"), rs.getObject("payable_id", UUID.class), rs.getLong("amount_minor"),
                rs.getString("currency"), expiry == null ? null : expiry.toLocalDate(),
                rs.getObject("plate_id", UUID.class), rs.getString("requested_by"), rs.getString("channel"),
                rs.getTimestamp("created_at").toInstant(), completed == null ? null : completed.toInstant(), payload,
                rs.getString("subject_ref"), rs.getString("payer_file"));
    }

    static Map<String, Object> map(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        n.properties().forEach(e -> m.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue()
                : e.getValue().isNull() ? null : e.getValue().asString()));
        return m;
    }

    static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) a.getArray()).map(String::valueOf).filter(s -> !s.isEmpty()).toList();
    }
}
