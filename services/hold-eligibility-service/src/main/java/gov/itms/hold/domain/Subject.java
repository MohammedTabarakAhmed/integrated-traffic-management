package gov.itms.hold.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What a hold is on (CC-030: "a traffic file, licence, vehicle or plate"). Business numbers use the interim format of
 * transaction-service until the owning services define theirs (Q-046), so one subject has one spelling everywhere.
 */
public record Subject(Type type, String id) {

    public enum Type { TRAFFIC_FILE, DRIVING_LICENCE, VEHICLE, PLATE }

    public static final String BUSINESS_NUMBER = "^[A-Z0-9][A-Z0-9-]{0,39}$";
    private static final Pattern NUMBER = Pattern.compile(BUSINESS_NUMBER);

    public Subject {
        if (type == null) {
            throw new DomainException(DomainException.Kind.VALIDATION, "SUBJECT_TYPE_REQUIRED",
                    "choose what the hold is on: a traffic file, licence, vehicle or plate", List.of("CC-030"));
        }
        if (id == null || !NUMBER.matcher(id).matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "BUSINESS_NUMBER_INVALID",
                    "subject.id must be 1-40 characters of A-Z, 0-9 or -, starting with a letter or digit",
                    List.of("CC-030"), "subject.id");
        }
    }
}
