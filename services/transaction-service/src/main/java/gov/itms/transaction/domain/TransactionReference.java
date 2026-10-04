package gov.itms.transaction.domain;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-readable transaction reference {@code <ServiceCode>-<Region>-<YYYY>-<Sequence>} (CC-021). Q-003 interim rule:
 * the sequence counts per service, region and year, has 7 digits and starts again at 1 each year. Service and region
 * codes contain no hyphen, so a reference always splits back into its four parts.
 */
public record TransactionReference(String serviceCode, String region, int year, long sequence) {

    public static final long MAX_SEQUENCE = 9_999_999L;
    public static final String PATTERN = "^([A-Z][A-Z0-9_]{1,19})-([A-Z0-9_]{1,40})-([0-9]{4})-([0-9]{7})$";
    private static final Pattern REFERENCE = Pattern.compile(PATTERN);

    public TransactionReference {
        serviceCode = Codes.serviceCode(serviceCode);
        region = Codes.code("region", region);
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("year must have four digits");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence starts at 1");
        }
        if (sequence > MAX_SEQUENCE) {
            throw new DomainException(DomainException.Kind.CONFLICT, "REFERENCE_SEQUENCE_EXHAUSTED",
                    "no reference numbers are left this year for service " + serviceCode + " in region " + region,
                    List.of("CC-021"), serviceCode, region);
        }
    }

    /** Parses a reference; anything else is refused. */
    public static TransactionReference parse(String value) {
        Matcher m = value == null ? null : REFERENCE.matcher(value);
        if (m == null || !m.matches() || Integer.parseInt(m.group(3)) == 0 || Long.parseLong(m.group(4)) == 0) {
            throw new DomainException(DomainException.Kind.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    "there is no transaction with this reference", List.of("CC-021"));
        }
        return new TransactionReference(m.group(1), m.group(2), Integer.parseInt(m.group(3)),
                Long.parseLong(m.group(4)));
    }

    @Override
    public String toString() {
        return "%s-%s-%04d-%07d".formatted(serviceCode, region, year, sequence);
    }
}
