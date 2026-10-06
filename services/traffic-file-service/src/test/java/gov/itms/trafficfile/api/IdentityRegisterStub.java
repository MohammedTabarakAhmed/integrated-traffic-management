package gov.itms.trafficfile.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A real HTTP stand-in for the identity register's lookup (ICD-01), with the same people as the local WireMock mappings
 * (infra/local/mocks/identity-register): a citizen, a resident, a deceased person, a number that fails with 503, and
 * 404 for anything else. It also stands in for one economic licensing authority (ICD-04) under {@code /dxb}: an active,
 * a second active, an expired and a cancelled licence, one that fails with 503, and 404 for anything else.
 */
final class IdentityRegisterStub {

    static final String CITIZEN = "784199012345671";
    static final String RESIDENT = "784198812345672";
    static final String DECEASED = "784197012345673";
    static final String FAILING = "999999999999999";
    static final String LICENCE_ACTIVE = "CN-1001234";
    static final String LICENCE_ACTIVE_2 = "CN-1005678";
    static final String LICENCE_EXPIRED = "CN-2000001";
    static final String LICENCE_CANCELLED = "CN-3000001";
    static final String LICENCE_FAILING = "CN-9999999";

    private final HttpServer server;
    /** Register records that replace the built-in ones, so a test can change a person (DL-010). */
    private final java.util.Map<String, String> overrides = new java.util.concurrent.ConcurrentHashMap<>();

    IdentityRegisterStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/persons/", this::lookup);
        server.createContext("/dxb/v1/licences/", this::licence);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** From now on the register answers this record for the person. */
    void override(String id, String givenEn, String familyEn, String residency, String lifeStatus) {
        overrides.put(id, person(id, "اسم", "عائلة", givenEn, familyEn, "PHL", residency, "2029-01-31", "1988-09-03",
                "F", lifeStatus));
    }

    void stop() {
        server.stop(0);
    }

    private void lookup(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestURI().getPath().substring("/v1/persons/".length());
        String override = overrides.get(id);
        if (override != null) {
            respond(exchange, 200, override);
            return;
        }
        switch (id) {
            case CITIZEN -> respond(exchange, 200, person(CITIZEN, "أحمد", "المنصوري", "Ahmed", "Al Mansoori", "ARE",
                    "CITIZEN", null, "1990-04-12", "M", "ALIVE"));
            case RESIDENT -> respond(exchange, 200, person(RESIDENT, "ماريا", "سانتوس", "Maria", "Santos", "PHL",
                    "RESIDENT", "2028-01-31", "1988-09-03", "F", "ALIVE"));
            case DECEASED -> respond(exchange, 200, person(DECEASED, "سالم", "الكعبي", "Salem", "Al Kaabi", "ARE",
                    "CITIZEN", null, "1970-01-20", "M", "DECEASED"));
            case FAILING -> respond(exchange, 503, "");
            default -> respond(exchange, 404, "");
        }
    }

    private void licence(HttpExchange exchange) throws IOException {
        String no = exchange.getRequestURI().getPath().substring("/dxb/v1/licences/".length());
        switch (no) {
            case LICENCE_ACTIVE -> respond(exchange, 200, licence(no, "Rapid Transport LLC", "2030-12-31", "ACTIVE"));
            case LICENCE_ACTIVE_2 -> respond(exchange, 200, licence(no, "Fleet Establishment", "2029-06-30", "ACTIVE"));
            case LICENCE_EXPIRED -> respond(exchange, 200, licence(no, "Old Trading", "2020-01-31", "ACTIVE"));
            case LICENCE_CANCELLED -> respond(exchange, 200, licence(no, "Closed Company", "2031-01-31", "CANCELLED"));
            case LICENCE_FAILING -> respond(exchange, 503, "");
            default -> respond(exchange, 404, "");
        }
    }

    private static String licence(String no, String nameEn, String expiry, String status) {
        return """
                {"licenceNo":"%s","tradeNameNl":"شركة","tradeNameEn":"%s","activityCodes":["LAND_TRANSPORT"],
                 "expiryDate":"%s","status":"%s"}""".formatted(no, nameEn, expiry, status);
    }

    private static String person(String id, String givenNl, String familyNl, String givenEn, String familyEn,
                                 String nationality, String residency, String residencyExpiry, String birthDate,
                                 String gender, String lifeStatus) {
        return """
                {"nationalIdNo":"%s","givenNameNl":"%s","familyNameNl":"%s","givenNameEn":"%s","familyNameEn":"%s",
                 "nationalityCode":"%s","residencyStatus":"%s","residencyExpiry":%s,"birthDate":"%s",
                 "genderCode":"%s","lifeStatus":"%s"}""".formatted(id, givenNl, familyNl, givenEn, familyEn,
                nationality, residency, residencyExpiry == null ? "null" : "\"" + residencyExpiry + "\"", birthDate,
                gender, lifeStatus);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
