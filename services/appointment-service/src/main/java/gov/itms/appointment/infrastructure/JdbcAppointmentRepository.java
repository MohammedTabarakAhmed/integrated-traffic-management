package gov.itms.appointment.infrastructure;

import gov.itms.appointment.application.AppointmentRepository;
import gov.itms.appointment.domain.Booking;
import gov.itms.appointment.domain.Calendar;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Calendars and bookings in PostgreSQL; the database guards closed bookings (V1). */
class JdbcAppointmentRepository implements AppointmentRepository {

    private static final String BOOKING_SELECT = """
            select id, service_code, centre_code, resource_code, customer_id, transaction_reference, created_at,
                start_at, end_at, status, changes, closed_at, reminder_sent_at
            from booking""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcAppointmentRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void saveCentre(Calendar.Centre c) {
        ObjectNode hours = json.createObjectNode();
        c.weeklyHours().forEach((day, periods) -> {
            ArrayNode a = hours.putArray(day.name());
            periods.forEach(p -> a.addObject().put("opens", p.opens().toString()).put("closes", p.closes().toString()));
        });
        jdbc.sql("""
                        insert into appointment_centre (code, region, zone, weekly_hours, active, updated_at)
                        values (:code, :region, :zone, cast(:hours as jsonb), :active, now())
                        on conflict (code) do update set zone = excluded.zone, weekly_hours = excluded.weekly_hours,
                            active = excluded.active, updated_at = now()""")
                .param("code", c.code()).param("region", c.region()).param("zone", c.zone().getId())
                .param("hours", json.writeValueAsString(hours)).param("active", c.active()).update();
    }

    @Override
    public Optional<Calendar.Centre> findCentre(String code) {
        return jdbc.sql("""
                        select code, region, zone, weekly_hours::text as weekly_hours, active
                        from appointment_centre where code = :c""")
                .param("c", code).query(this::mapCentre).optional();
    }

    @Override
    public long insertClosure(Calendar.Closure c) {
        return jdbc.sql("""
                        insert into appointment_closure (centre_code, resource_code, date_from, date_to, kind, reason)
                        values (:centre, :resource, :from, :to, :kind, :reason) returning id""")
                .param("centre", c.centreCode()).param("resource", c.resourceCode())
                .param("from", Date.valueOf(c.from())).param("to", Date.valueOf(c.to()))
                .param("kind", c.kind().name()).param("reason", c.reason())
                .query(Long.class).single();
    }

    @Override
    public List<Calendar.Closure> closures(String centreCode, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select id, centre_code, resource_code, date_from, date_to, kind, reason
                        from appointment_closure
                        where centre_code = :c and date_from <= :to and date_to >= :from order by date_from, id""")
                .param("c", centreCode).param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query((rs, n) -> new Calendar.Closure(rs.getLong("id"), rs.getString("centre_code"),
                        rs.getString("resource_code"), rs.getDate("date_from").toLocalDate(),
                        rs.getDate("date_to").toLocalDate(), Calendar.ClosureKind.valueOf(rs.getString("kind")),
                        rs.getString("reason")))
                .list();
    }

    @Override
    public void saveResource(Calendar.Resource r) {
        jdbc.sql("""
                        insert into appointment_resource (code, centre_code, type, name, services, capacity, active,
                            updated_at)
                        values (:code, :centre, :type, :name, string_to_array(:services, ','), :capacity, :active,
                            now())
                        on conflict (code) do update set type = excluded.type, name = excluded.name,
                            services = excluded.services, capacity = excluded.capacity, active = excluded.active,
                            updated_at = now()""")
                .param("code", r.code()).param("centre", r.centreCode()).param("type", r.type().name())
                .param("name", r.name()).param("services", String.join(",", r.services().stream().sorted().toList()))
                .param("capacity", r.capacity()).param("active", r.active()).update();
    }

    @Override
    public Optional<Calendar.Resource> findResource(String code) {
        return jdbc.sql(RESOURCE_SELECT + " where code = :c").param("c", code)
                .query(JdbcAppointmentRepository::mapResource).optional();
    }

    @Override
    public List<Calendar.Resource> resources(String centreCode) {
        return jdbc.sql(RESOURCE_SELECT + " where centre_code = :c order by code").param("c", centreCode)
                .query(JdbcAppointmentRepository::mapResource).list();
    }

    private static final String RESOURCE_SELECT = """
            select code, centre_code, type, name, services, capacity, active from appointment_resource""";

    @Override
    public void saveSettings(Calendar.ServiceSettings s) {
        jdbc.sql("""
                        insert into appointment_service_settings (service_code, slot_minutes, horizon_days,
                            min_lead_minutes, max_changes, no_show_limit, no_show_window_days, penalty_days,
                            penalty_fee_code, updated_at)
                        values (:code, :slot, :horizon, :lead, :changes, :limit, :window, :penalty, :fee, now())
                        on conflict (service_code) do update set slot_minutes = excluded.slot_minutes,
                            horizon_days = excluded.horizon_days, min_lead_minutes = excluded.min_lead_minutes,
                            max_changes = excluded.max_changes, no_show_limit = excluded.no_show_limit,
                            no_show_window_days = excluded.no_show_window_days, penalty_days = excluded.penalty_days,
                            penalty_fee_code = excluded.penalty_fee_code, updated_at = now()""")
                .param("code", s.serviceCode()).param("slot", (int) s.slotLength().toMinutes())
                .param("horizon", s.horizonDays()).param("lead", (int) s.minLeadTime().toMinutes())
                .param("changes", s.maxChanges()).param("limit", s.noShowLimit())
                .param("window", s.noShowWindowDays()).param("penalty", s.penaltyDays())
                .param("fee", s.penaltyFeeCode()).update();
    }

    @Override
    public Optional<Calendar.ServiceSettings> findSettings(String serviceCode) {
        return jdbc.sql("""
                        select service_code, slot_minutes, horizon_days, min_lead_minutes, max_changes, no_show_limit,
                            no_show_window_days, penalty_days, penalty_fee_code
                        from appointment_service_settings where service_code = :c""")
                .param("c", serviceCode)
                .query((rs, n) -> new Calendar.ServiceSettings(rs.getString("service_code"),
                        Duration.ofMinutes(rs.getInt("slot_minutes")), rs.getInt("horizon_days"),
                        Duration.ofMinutes(rs.getInt("min_lead_minutes")), rs.getInt("max_changes"),
                        rs.getInt("no_show_limit"), rs.getInt("no_show_window_days"), rs.getInt("penalty_days"),
                        rs.getString("penalty_fee_code")))
                .optional();
    }

    @Override
    public void lockSlot(String resourceCode, Instant start) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('slot:' || :r || ':' || :s))").param("r", resourceCode)
                .param("s", String.valueOf(start.getEpochSecond())).query().listOfRows();
    }

    @Override
    public Map<String, Integer> bookedCounts(String centreCode, Instant from, Instant to) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql("""
                        select resource_code, start_at, count(*) as n from booking
                        where centre_code = :c and status = 'BOOKED' and start_at >= :from and start_at < :to
                        group by resource_code, start_at""")
                .param("c", centreCode).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((RowCallbackHandler) rs -> counts.put(AppointmentRepository.slotKey(
                        rs.getString("resource_code"), rs.getTimestamp("start_at").toInstant()), rs.getInt("n")));
        return counts;
    }

    @Override
    public int bookedCount(String resourceCode, Instant start) {
        return jdbc.sql("""
                        select count(*) from booking where resource_code = :r and start_at = :s
                            and status = 'BOOKED'""")
                .param("r", resourceCode).param("s", Timestamp.from(start)).query(Integer.class).single();
    }

    @Override
    public void insertBooking(Booking b) {
        jdbc.sql("""
                        insert into booking (id, service_code, centre_code, resource_code, customer_id,
                            transaction_reference, created_at, start_at, end_at, status, changes)
                        values (:id, :service, :centre, :resource, :customer, :reference, :createdAt, :start, :end,
                            :status, :changes)""")
                .param("id", b.id()).param("service", b.serviceCode()).param("centre", b.centreCode())
                .param("resource", b.resourceCode()).param("customer", b.customerId())
                .param("reference", b.transactionReference()).param("createdAt", Timestamp.from(b.createdAt()))
                .param("start", Timestamp.from(b.start())).param("end", Timestamp.from(b.end()))
                .param("status", b.status().name()).param("changes", b.changes()).update();
    }

    @Override
    public Optional<Booking> findBooking(UUID id) {
        return jdbc.sql(BOOKING_SELECT + " where id = :id").param("id", id)
                .query(JdbcAppointmentRepository::mapBooking).optional();
    }

    @Override
    public Optional<Booking> findBookingForUpdate(UUID id) {
        return jdbc.sql(BOOKING_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcAppointmentRepository::mapBooking).optional();
    }

    @Override
    public void updateBooking(Booking b) {
        jdbc.sql("""
                        update booking set resource_code = :resource, start_at = :start, end_at = :end,
                            status = :status, changes = :changes, closed_at = :closed, reminder_sent_at = :reminder
                        where id = :id""")
                .param("resource", b.resourceCode()).param("start", Timestamp.from(b.start()))
                .param("end", Timestamp.from(b.end())).param("status", b.status().name())
                .param("changes", b.changes()).param("closed", ts(b.closedAt()))
                .param("reminder", ts(b.reminderSentAt())).param("id", b.id()).update();
    }

    @Override
    public boolean activeBookingExists(String customerId, String serviceCode, String transactionReference,
                                       Instant now) {
        return jdbc.sql("""
                        select exists (select 1 from booking where customer_id = :c and service_code = :s
                            and status = 'BOOKED' and start_at > :now
                            and transaction_reference is not distinct from cast(:r as varchar))""")
                .param("c", customerId).param("s", serviceCode).param("now", Timestamp.from(now))
                .param("r", transactionReference).query(Boolean.class).single();
    }

    @Override
    public List<Booking> byCustomer(String customerId, int limit) {
        return jdbc.sql(BOOKING_SELECT + " where customer_id = :c order by start_at desc limit :l")
                .param("c", customerId).param("l", limit).query(JdbcAppointmentRepository::mapBooking).list();
    }

    @Override
    public List<Booking> byCentre(String centreCode, Instant from, Instant to, int limit) {
        return jdbc.sql(BOOKING_SELECT + """
                          where centre_code = :c and start_at >= :from and start_at < :to
                         order by start_at, resource_code limit :l""")
                .param("c", centreCode).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("l", limit).query(JdbcAppointmentRepository::mapBooking).list();
    }

    @Override
    public List<Booking> noShows(String customerId, String serviceCode, Instant since) {
        return jdbc.sql(BOOKING_SELECT + """
                          where customer_id = :c and service_code = :s and status = 'NO_SHOW' and closed_at >= :since
                         order by closed_at desc""")
                .param("c", customerId).param("s", serviceCode).param("since", Timestamp.from(since))
                .query(JdbcAppointmentRepository::mapBooking).list();
    }

    @Override
    public List<Booking> dueForReminder(Instant now, Instant until, int limit) {
        return jdbc.sql(BOOKING_SELECT + """
                          where status = 'BOOKED' and reminder_sent_at is null and start_at > :now
                             and start_at <= :until
                         order by start_at limit :l for update skip locked""")
                .param("now", Timestamp.from(now)).param("until", Timestamp.from(until)).param("l", limit)
                .query(JdbcAppointmentRepository::mapBooking).list();
    }

    private Calendar.Centre mapCentre(ResultSet rs, int row) throws SQLException {
        Map<DayOfWeek, List<Calendar.Period>> hours = new EnumMap<>(DayOfWeek.class);
        JsonNode n = json.readTree(rs.getString("weekly_hours"));
        n.properties().forEach(e -> {
            List<Calendar.Period> periods = new ArrayList<>();
            e.getValue().forEach(p -> periods.add(new Calendar.Period(LocalTime.parse(p.get("opens").asString()),
                    LocalTime.parse(p.get("closes").asString()))));
            hours.put(DayOfWeek.valueOf(e.getKey()), periods);
        });
        return new Calendar.Centre(rs.getString("code"), rs.getString("region"), ZoneId.of(rs.getString("zone")),
                hours, rs.getBoolean("active"));
    }

    private static Calendar.Resource mapResource(ResultSet rs, int row) throws SQLException {
        Array services = rs.getArray("services");
        return new Calendar.Resource(rs.getString("code"), rs.getString("centre_code"),
                Calendar.ResourceType.valueOf(rs.getString("type")), rs.getString("name"),
                Set.copyOf(Arrays.stream((Object[]) services.getArray()).map(String::valueOf).toList()),
                rs.getInt("capacity"), rs.getBoolean("active"));
    }

    private static Booking mapBooking(ResultSet rs, int row) throws SQLException {
        return Booking.restore(rs.getObject("id", UUID.class), rs.getString("service_code"),
                rs.getString("centre_code"), rs.getString("resource_code"), rs.getString("customer_id"),
                rs.getString("transaction_reference"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                Booking.Status.valueOf(rs.getString("status")), rs.getInt("changes"),
                instant(rs.getTimestamp("closed_at")), instant(rs.getTimestamp("reminder_sent_at")));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
