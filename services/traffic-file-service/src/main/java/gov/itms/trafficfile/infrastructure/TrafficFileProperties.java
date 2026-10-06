package gov.itms.trafficfile.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * traffic-file-service settings (NFR-042, ADR-041). Formats the BRD leaves open are configuration (Q-022, Q-057).
 *
 * @param nationalIdPattern regular expression a national identity number must match
 * @param fileNumberPrefix  letters put before the number, 0-6 upper-case letters (default none: numeric, Q-022)
 * @param fileNumberWidth   digits of the sequence number, zero-padded, 6-14; a Luhn check digit follows (Q-022)
 * @param register          the identity register (ICD-01)
 * @param licensing         the economic licensing authorities (ICD-04)
 */
@ConfigurationProperties("itms.traffic-file")
public record TrafficFileProperties(
        @DefaultValue("^[0-9]{6,20}$") String nationalIdPattern,
        @DefaultValue("") String fileNumberPrefix,
        @DefaultValue("10") int fileNumberWidth,
        Register register,
        Licensing licensing) {

    /**
     * @param authorities licence source code (reference list of commercial licence sources) → authority address
     * @param timeout     per lookup, 500 ms to 10 s
     */
    public record Licensing(Map<String, URI> authorities, @DefaultValue("3s") Duration timeout) {

        public Licensing {
            authorities = authorities == null ? Map.of() : Map.copyOf(authorities);
            if (timeout.compareTo(Duration.ofMillis(500)) < 0 || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
                throw new IllegalArgumentException("itms.traffic-file.licensing.timeout must be 500ms..10s");
            }
            if (authorities.keySet().stream().anyMatch(k -> !k.matches("^[A-Z0-9_]{1,40}$"))) {
                throw new IllegalArgumentException("itms.traffic-file.licensing.authorities keys must be source codes");
            }
        }
    }

    /**
     * @param baseUrl        register (or mock) address
     * @param timeout        per lookup, 500 ms to 10 s (ICD-01: p95 ≤ 1 s, give up after 3 s)
     * @param genderCodes    register gender code → ITMS gender code
     * @param residencyCodes register residency status → ITMS residency code
     */
    public record Register(URI baseUrl, @DefaultValue("3s") Duration timeout, Map<String, String> genderCodes,
                           Map<String, String> residencyCodes) {

        public Register {
            if (baseUrl == null) {
                throw new IllegalArgumentException("itms.traffic-file.register.base-url is required");
            }
            if (timeout.compareTo(Duration.ofMillis(500)) < 0 || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
                throw new IllegalArgumentException("itms.traffic-file.register.timeout must be 500ms..10s");
            }
            genderCodes = genderCodes == null ? Map.of() : Map.copyOf(genderCodes);
            residencyCodes = residencyCodes == null ? Map.of() : Map.copyOf(residencyCodes);
            if (genderCodes.isEmpty()) {
                throw new IllegalArgumentException("itms.traffic-file.register.gender-codes must map the register's codes");
            }
        }
    }

    public TrafficFileProperties {
        try {
            Pattern.compile(nationalIdPattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("itms.traffic-file.national-id-pattern is not a regular expression", e);
        }
        if (!fileNumberPrefix.matches("^[A-Z]{0,6}$")) {
            throw new IllegalArgumentException("itms.traffic-file.file-number-prefix must be 0-6 upper-case letters");
        }
        if (fileNumberWidth < 6 || fileNumberWidth > 14 || fileNumberPrefix.length() + fileNumberWidth + 1 > 20) {
            throw new IllegalArgumentException("itms.traffic-file.file-number-width must be 6..14 (number ≤ 20 chars)");
        }
        if (register == null) {
            throw new IllegalArgumentException("itms.traffic-file.register is required");
        }
        if (licensing == null) {
            licensing = new Licensing(Map.of(), Duration.ofSeconds(3));
        }
    }

    public Pattern nationalIdRegex() {
        return Pattern.compile(nationalIdPattern);
    }
}
