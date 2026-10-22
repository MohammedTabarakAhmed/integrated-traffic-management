package gov.itms.appointment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import gov.itms.appointment.application.AppointmentService;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end slice test of appointments: HTTP → security → service → PostgreSQL (V1 guards, slot locks) → outboxes,
 * with every request and response validated against appointment-service.v1.yaml. Each test has its own centre (UTC,
 * open every day 08:00-12:00) and service code. The reminder job is off; the test runs the reminder pass itself.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.appointments.reminders.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-090")
class AppointmentApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final AtomicInteger CODES = new AtomicInteger();
    private static final LocalDate DAY = LocalDate.now(ZoneOffset.UTC).plusDays(3);
    private static final Instant EIGHT = DAY.atTime(8, 0).toInstant(ZoneOffset.UTC);
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    AppointmentService appointments;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/appointment-service.v1.yaml").build();
    }

    private static RequestPostProcessor admin() {
        return user("u-admin", "APPOINTMENT_ADMIN", "appointment.calendar.manage", "appointment.queue.manage",
                "appointment.read");
    }

    private static RequestPostProcessor agent() {
        return user("u-agent", "APPOINTMENT_AGENT", "appointment.book", "appointment.attendance", "appointment.read",
                "appointment.queue.manage");
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("roles", List.of("CUSTOMER")));
    }

    private static RequestPostProcessor user(String sub, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", "BACKOFFICE")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath).withHeader("Authorization", "Bearer t");
        if (key != null) {
            req.withHeader("Idempotency-Key", key);
        }
        if (qs > 0) {
            for (String pair : path.substring(qs + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv[1]);
            }
        }
        if (payload != null) {
            req.withContentType("application/json").withBody(payload);
        }
        SimpleResponse response = SimpleResponse.Builder.status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).build();
        ValidationReport report = result.getResponse().getStatus() < 400
                ? contract.validate(req.build(), response)
                : contract.validateResponse(requestPath, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, requestPath,
                result.getResponse().getStatus()).isEmpty();
        return result;
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        boolean idempotent = path.startsWith("/v1/bookings") || path.startsWith("/v1/queue-tickets");
        return call(post(path), Request.Method.POST, path, body, who, idempotent ? "it-" + UUID.randomUUID() : null);
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        return call(get(path), Request.Method.GET, path, null, who, null);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return body(r).get("code").asString();
    }

    /** A centre, one resource of the given capacity and the service's settings; returns {centre, service, resource}. */
    private String[] calendar(int capacity) throws Exception {
        int n = CODES.incrementAndGet();
        String suffix = n + "_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
        String centre = "C" + suffix;
        String service = "S" + suffix;
        String resource = "R" + suffix;
        Map<DayOfWeek, List<Map<String, String>>> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            hours.put(d, List.of(Map.of("opens", "08:00", "closes", "12:00")));
        }
        assertThat(postAs("/v1/appointment-centres", Map.of("code", centre, "region", "NORTH", "zone", "UTC",
                "weeklyHours", hours, "active", true), admin()).getResponse().getStatus()).isEqualTo(200);
        MvcResult r = postAs("/v1/appointment-resources", Map.of("code", resource, "centreCode", centre,
                "type", "EXAMINER", "name", "Examiner", "services", List.of(service), "capacity", capacity,
                "active", true), admin());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        Map<String, Object> settings = new HashMap<>(Map.of("serviceCode", service, "slotMinutes", 30,
                "horizonDays", 30, "minLeadMinutes", 1440, "maxChanges", 2, "noShowLimit", 2,
                "noShowWindowDays", 90, "penaltyDays", 30));
        assertThat(postAs("/v1/appointment-service-settings", settings, admin()).getResponse().getStatus())
                .isEqualTo(200);
        return new String[] {centre, service, resource};
    }

    private MvcResult book(String[] cal, Instant start, String customerId, RequestPostProcessor who)
            throws Exception {
        return postAs("/v1/bookings", Map.of("serviceCode", cal[1], "centreCode", cal[0], "start", start.toString(),
                "customerId", customerId), who);
    }

    @Test
    @Tag("CC-091")
    @Tag("CC-092")
    @DisplayName("slots, booking, change within the limit, cancellation; audited; a closed booking never changes")
    void bookChangeCancel() throws Exception {
        String[] cal = calendar(1);
        JsonNode slots = body(getAs("/v1/slots?centreCode=" + cal[0] + "&serviceCode=" + cal[1] + "&from=" + DAY
                + "&to=" + DAY, customer("c-1"))).get("items");
        assertThat(slots).hasSize(8);
        MvcResult booked = book(cal, EIGHT, "c-1", customer("c-1"));
        assertThat(booked.getResponse().getStatus()).as(booked.getResponse().getContentAsString()).isEqualTo(201);
        String id = body(booked).get("id").asString();
        assertThat(body(booked).get("resourceCode").asString()).isEqualTo(cal[2]);
        assertThat(body(getAs("/v1/slots?centreCode=" + cal[0] + "&serviceCode=" + cal[1] + "&from=" + DAY + "&to="
                + DAY, customer("c-1"))).get("items")).hasSize(7);

        for (int i = 1; i <= 2; i++) {
            MvcResult changed = postAs("/v1/bookings/" + id + "/changes",
                    Map.of("start", EIGHT.plus(Duration.ofMinutes(30L * i)).toString()), customer("c-1"));
            assertThat(body(changed).get("changes").asInt()).isEqualTo(i);
        }
        assertThat(code(postAs("/v1/bookings/" + id + "/changes", Map.of("start", EIGHT.toString()),
                customer("c-1")))).isEqualTo("CHANGE_LIMIT_REACHED");
        assertThat(postAs("/v1/bookings/" + id + "/cancellations", Map.of("reason", "not mine"), customer("c-2"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(body(postAs("/v1/bookings/" + id + "/cancellations", Map.of("reason", "plans changed"),
                customer("c-1"))).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(code(postAs("/v1/bookings/" + id + "/cancellations", Map.of(), customer("c-1"))))
                .isEqualTo("BOOKING_CLOSED");

        assertThat(db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", id).query(String.class).list()).containsExactly("APPOINTMENT_BOOKED",
                "APPOINTMENT_CHANGED", "APPOINTMENT_CHANGED", "APPOINTMENT_CANCELLED");
        assertThatThrownBy(() -> db.sql("update booking set changes = 0 where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("closed booking");
        assertThatThrownBy(() -> db.sql("delete from booking where id = cast(:id as uuid)").param("id", id)
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
    }

    @Test
    @Tag("CC-091")
    @DisplayName("two customers race for the last place: exactly one booking succeeds")
    void lastPlaceRace() throws Exception {
        String[] cal = calendar(1);
        Instant start = EIGHT.plus(Duration.ofHours(1));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String customerId = "race-" + i + "-" + cal[0].toLowerCase();
                attempts.add(() -> book(cal, start, customerId, customer(customerId)).getResponse().getStatus());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(attempts)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.sql("select count(*) from booking where resource_code = :r and status = 'BOOKED'")
                .param("r", cal[2]).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Tag("CH-031")
    @DisplayName("CH-031: tickets are issued and numbered per day, called to a counter, served or departed;"
            + " the day's queue shows the waiting and service times; nobody else manages the counter")
    void queueTickets() throws Exception {
        String[] cal = calendar(1);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        JsonNode first = body(postAs("/v1/queue-tickets", Map.of("centreCode", cal[0], "serviceCode", cal[1]),
                agent()));
        JsonNode second = body(postAs("/v1/queue-tickets", Map.of("centreCode", cal[0]), agent()));
        assertThat(first.get("status").asString()).isEqualTo("ISSUED");
        assertThat(first.hasNonNull("waitingMinutes")).isFalse();
        assertThat(first.get("issuedBy").asString()).isEqualTo("u-agent");
        assertThat(second.get("ticketNo").asString()).isNotEqualTo(first.get("ticketNo").asString());
        assertThat(body(getAs("/v1/queue-tickets?centreCode=" + cal[0] + "&date=" + today, agent()))
                .get("metrics").get("issued").asInt()).isEqualTo(2);

        String no1 = first.get("ticketNo").asString();
        JsonNode called = body(postAs("/v1/queue-tickets/" + no1 + "/calls", Map.of("counter", "C-3"), agent()));
        assertThat(called.get("status").asString()).isEqualTo("CALLED");
        assertThat(called.get("counter").asString()).isEqualTo("C-3");
        assertThat(called.get("waitingMinutes").asLong()).as("waiting in whole minutes, from zero up")
                .isGreaterThanOrEqualTo(0L);
        JsonNode served = body(postAs("/v1/queue-tickets/" + no1 + "/completions", null, agent()));
        assertThat(served.get("status").asString()).isEqualTo("SERVED");
        assertThat(served.get("serviceMinutes").asLong()).as("service in whole minutes, from zero up")
                .isGreaterThanOrEqualTo(0L);

        String no2 = second.get("ticketNo").asString();
        assertThat(body(postAs("/v1/queue-tickets/" + no2 + "/departures",
                Map.of("reason", "queue too long"), agent())).get("status").asString()).isEqualTo("LEFT");

        assertThat(code(postAs("/v1/queue-tickets/" + no2 + "/calls", Map.of("counter", "C-4"), agent())))
                .isEqualTo("TICKET_NOT_OPEN");
        assertThat(code(postAs("/v1/queue-tickets/" + no1 + "/calls", Map.of("counter", "C-4"), agent())))
                .isEqualTo("TICKET_NOT_OPEN");
        assertThat(code(postAs("/v1/queue-tickets/" + no1 + "/completions", null, agent())))
                .isEqualTo("TICKET_NOT_CALLED");
        assertThat(code(postAs("/v1/queue-tickets/Q-NO_THERE-251001-9/calls", Map.of("counter", "C"), agent())))
                .isEqualTo("TICKET_NOT_FOUND");

        JsonNode day = body(getAs("/v1/queue-tickets?centreCode=" + cal[0] + "&date=" + today, agent()));
        assertThat(day.get("metrics").get("issued").asInt()).isEqualTo(2);
        assertThat(day.get("metrics").get("called").asInt()).isEqualTo(2);
        assertThat(day.get("metrics").get("served").asInt()).isEqualTo(1);
        assertThat(day.get("items").findValuesAsString("ticketNo")).containsExactlyInAnyOrder(no1, no2);

        // outside: a customer has neither the manage grant nor the read grant; the administrator has both
        assertThat(postAs("/v1/queue-tickets", Map.of("centreCode", cal[0]), customer("c-5"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs("/v1/queue-tickets?centreCode=" + cal[0] + "&date=" + today, customer("c-5"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(postAs("/v1/queue-tickets", Map.of("centreCode", cal[0]), admin())
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(db.sql("select action from audit_outbox where entity_id = :n order by occurred_at")
                .param("n", no1).query(String.class).list()).containsExactly("QUEUE_TICKET_ISSUED",
                "QUEUE_TICKET_CALLED", "QUEUE_TICKET_SERVED");
    }

    @Test
    @Tag("CC-001")
    @Tag("CC-093")
    @DisplayName("staff book for customers; attendance needs its permission; customers only see their own")
    void staffAndScope() throws Exception {
        String[] cal = calendar(2);
        MvcResult staffBooked = book(cal, EIGHT, "c-9", agent());
        assertThat(staffBooked.getResponse().getStatus()).isEqualTo(201);
        String id = body(staffBooked).get("id").asString();
        assertThat(book(cal, EIGHT, "c-10", customer("c-11")).getResponse().getStatus())
                .as("a customer cannot book for someone else").isEqualTo(403);
        assertThat(getAs("/v1/bookings/" + id, customer("c-9")).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAs("/v1/bookings/" + id, customer("c-10")).getResponse().getStatus()).isEqualTo(403);
        assertThat(body(getAs("/v1/bookings?customerId=c-9", customer("c-9"))).get("items").findValuesAsString("id"))
                .contains(id);
        assertThat(postAs("/v1/bookings/" + id + "/attendances", Map.of("attended", true), customer("c-9"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(code(postAs("/v1/bookings/" + id + "/attendances", Map.of("attended", false), agent())))
                .as("before the start").isEqualTo("BOOKING_NOT_STARTED");
        assertThat(body(getAs("/v1/bookings?centreCode=" + cal[0] + "&day=" + DAY, agent())).get("items")).hasSize(1);
    }

    @Test
    @Tag("CC-071")
    @DisplayName("the reminder pass emits one reminder event per booking")
    void remindersOnce() throws Exception {
        String[] cal = calendar(1);
        String id = body(book(cal, EIGHT.plus(Duration.ofHours(2)), "c-r", customer("c-r"))).get("id").asString();
        appointments.sendReminders(Duration.ofDays(7), 1000);
        appointments.sendReminders(Duration.ofDays(7), 1000);
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.appointment.reminder-due' "
                + "and subject = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
    }
}
