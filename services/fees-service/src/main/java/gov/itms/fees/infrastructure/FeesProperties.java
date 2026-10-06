package gov.itms.fees.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * fees-service settings (NFR-042, ADR-046).
 *
 * @param currency      ISO 4217 code of all fee amounts (Q-006; test environments use XTS)
 * @param quoteValidity how long a quote stays usable, 5 minutes to 7 days
 */
@ConfigurationProperties("itms.fees")
public record FeesProperties(String currency, @DefaultValue("24h") Duration quoteValidity) {

    public FeesProperties {
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("itms.fees.currency must be an ISO 4217 code");
        }
        if (quoteValidity.compareTo(Duration.ofMinutes(5)) < 0 || quoteValidity.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("itms.fees.quote-validity must be 5m..7d");
        }
    }
}
