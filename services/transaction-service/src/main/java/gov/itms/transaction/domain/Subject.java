package gov.itms.transaction.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What a transaction is about (CC-027 "person, vehicle, licence"; DATA_MODEL master entities). A person is identified
 * by their traffic file. Business numbers use an interim format until the owning services define theirs (Q-046).
 */
public record Subject(Type type, String id) {

    public enum Type { TRAFFIC_FILE, VEHICLE, DRIVING_LICENCE, PLATE }

    /** Interim business-number format (Q-046): upper case, so that one subject has one spelling (CC-027). */
    public static final String BUSINESS_NUMBER = "^[A-Z0-9][A-Z0-9-]{0,39}$";
    private static final Pattern NUMBER = Pattern.compile(BUSINESS_NUMBER);

    public Subject {
        if (type == null) {
            throw invalid("subject.type");
        }
        id = businessNumber("subject.id", id);
    }

    public static String businessNumber(String field, String value) {
        if (value == null || !NUMBER.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    private static DomainException invalid(String field) {
        return new DomainException(DomainException.Kind.VALIDATION, "BUSINESS_NUMBER_INVALID",
                field + " must be 1-40 characters of A-Z, 0-9 or -, starting with a letter or digit",
                List.of("CC-027"), field);
    }
}
