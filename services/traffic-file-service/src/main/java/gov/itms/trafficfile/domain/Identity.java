package gov.itms.trafficfile.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Who the person is: the identity document, names, nationality, birth date and gender, and for residents the residency
 * (DL-001, DL-002). For a file opened with a national identity number these come from the identity register and staff
 * cannot change them ({@link #REGISTER_FIELDS}); for a visitor they are entered from the passport.
 */
public record Identity(
        IdentityDocument document,
        PersonName name,
        String nationality,
        LocalDate birthDate,
        String gender,
        String residencyStatus,
        LocalDate residencyExpiry) {

    /** Fields that come from the identity register and are locked against staff edits (DL-002). */
    public static final Set<String> REGISTER_FIELDS = Set.of("nationalIdNo", "name", "nationality", "birthDate",
            "gender", "residencyStatus", "residencyExpiry");

    public Identity {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(name, "name");
        nationality = Codes.country("nationality", nationality);
        if (birthDate == null) {
            throw DomainException.validation("FIELD_INVALID", "birthDate is required", List.of("DL-005"), "birthDate");
        }
        gender = Codes.code("gender", gender);
        residencyStatus = Codes.optionalCode("residencyStatus", residencyStatus);
        if (residencyExpiry != null && residencyStatus == null) {
            throw DomainException.validation("FIELD_INVALID", "residencyExpiry needs residencyStatus",
                    List.of("DL-002"), "residencyExpiry");
        }
    }

    /** True if the birth date lies after {@code today}. */
    public boolean bornAfter(LocalDate today) {
        return birthDate.isAfter(today);
    }

    /** The identity document a file is opened with (DL-001). */
    public sealed interface IdentityDocument {
    }

    /** Citizens and residents: the national identity number. */
    public record NationalId(String number) implements IdentityDocument {
        public NationalId {
            Objects.requireNonNull(number, "number");
        }
    }

    /** Visitors: passport number and issuing country. */
    public record Passport(String number, String country) implements IdentityDocument {
        public Passport {
            number = Codes.passport(number);
            country = Codes.country("passportCountry", country);
        }
    }
}
