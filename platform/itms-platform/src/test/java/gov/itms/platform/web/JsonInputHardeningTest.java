package gov.itms.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class JsonInputHardeningTest {

    enum Colour { RED, GREEN }

    record Body(Colour colour, String text, Long amount, Integer count, Instant at, LocalDate on) {
    }

    private static final JsonMapper MAPPER = mapper();

    private static JsonMapper mapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        JsonInputHardening.customizer().customize(builder);
        return builder.build();
    }

    private static Body read(String json) {
        return MAPPER.readValue(json, Body.class);
    }

    @Test
    @Tag("SEC-022")
    @DisplayName("SEC-022: well-formed values bind unchanged")
    void validInputBinds() {
        Body b = read("""
                {"colour":"GREEN","text":"  Arabic نص  ","amount":9007199254740993,"count":7,
                 "at":"9999-12-31T23:59:59.999999Z","on":"0001-01-01"}""");
        assertThat(b).isEqualTo(new Body(Colour.GREEN, "  Arabic نص  ", 9007199254740993L, 7,
                Instant.parse("9999-12-31T23:59:59.999999Z"), LocalDate.of(1, 1, 1)));
        assertThat(read("{\"colour\":null,\"text\":null,\"amount\":null,\"at\":null,\"on\":null}"))
                .isEqualTo(new Body(null, null, null, null, null, null));
    }

    @Test
    @Tag("SEC-022")
    @DisplayName("SEC-022: an unknown field is refused, not silently ignored")
    void unknownFieldsAreRefused() {
        assertThatThrownBy(() -> read("{\"text\":\"a\",\"approvedBy\":\"me\"}"))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @Tag("SEC-022")
    @DisplayName("SEC-022: an enum must be exactly its name as a JSON string")
    void enumsAreExact() {
        for (String value : List.of("1", "0", "true", "\"GREEN \"", "\" GREEN\"", "\"green\"", "\"GREEN\\u0000\"",
                "\"\\tGREEN\"", "[\"GREEN\"]", "{}", "\"\"")) {
            assertThatThrownBy(() -> read("{\"colour\":" + value + "}")).as(value)
                    .isInstanceOf(JacksonException.class);
        }
    }

    @Test
    @Tag("SEC-022")
    @Tag("CC-003")
    @DisplayName("SEC-022/CC-003: an integer must be a JSON integer; fractions, exponents and strings are refused")
    void integersAreNotCoerced() {
        for (String field : List.of("amount", "count")) {
            for (String value : List.of("5.9", "7.0", "1e3", "\"7\"", "true", "\"\"")) {
                assertThatThrownBy(() -> read("{\"" + field + "\":" + value + "}")).as(field + "=" + value)
                        .isInstanceOf(JacksonException.class);
            }
        }
    }

    @Test
    @Tag("SEC-022")
    @DisplayName("SEC-022: text must be a JSON string without the NUL character")
    void textIsStrict() {
        for (String value : List.of("12345", "1.5", "true", "\"ab\\u0000cd\"", "[\"a\"]", "{}")) {
            assertThatThrownBy(() -> read("{\"text\":" + value + "}")).as(value)
                    .isInstanceOf(JacksonException.class);
        }
    }

    @Test
    @Tag("SEC-022")
    @DisplayName("SEC-022: date-times and dates must be RFC 3339 strings within years 0001 to 9999")
    void datesAreBounded() {
        for (String value : List.of("4102444800", "4102444800.5", "\"+10000-01-01T00:00:00Z\"",
                "\"+999999999-01-01T00:00:00Z\"", "\"9999-12-31T23:59:59.9999999Z\"", "\"0000-12-31T23:59:59Z\"",
                "true")) {
            assertThatThrownBy(() -> read("{\"at\":" + value + "}")).as("at " + value)
                    .isInstanceOf(JacksonException.class);
        }
        for (String value : List.of("20000", "\"+10000-01-01\"", "\"0000-12-31\"", "\"-0001-01-01\"", "[2026,1,1]",
                "true")) {
            assertThatThrownBy(() -> read("{\"on\":" + value + "}")).as("on " + value)
                    .isInstanceOf(JacksonException.class);
        }
        assertThat(read("{\"on\":\"9999-12-31\"}").on()).isEqualTo(LocalDate.of(9999, 12, 31));
    }
}
