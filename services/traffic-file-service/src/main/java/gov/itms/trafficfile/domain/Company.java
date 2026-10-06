package gov.itms.trafficfile.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A company's file content (DL-003, DL-004): the commercial licence as read from the economic licensing authority of
 * the issuing region (locked, like a register identity), and the people who act for the company: authorised
 * signatories (at least one) and fleet managers, each an individual's traffic file.
 */
public record Company(Licence licence, String nameNl, String nameEn, List<String> activities, LocalDate licenceExpiry,
                      List<String> signatories, List<String> fleetManagers) {

    /** Fields that come from the licensing authority and are locked against staff edits. */
    public static final List<String> REGISTER_FIELDS = List.of("licence", "nameNl", "nameEn", "activities",
            "licenceExpiry");

    private static final Pattern FILE_NUMBER = Pattern.compile(Codes.FILE_NUMBER);
    static final int MAX_REPRESENTATIVES = 20;

    /** A commercial licence: the issuing authority (a reference-data code of commercial licence sources) and number. */
    public record Licence(String source, String number) {
        public static final String NUMBER = "^[A-Za-z0-9/-]{1,40}$";

        public Licence {
            source = Codes.code("licenceSource", source);
            if (number == null || !number.matches(NUMBER)) {
                throw DomainException.validation("FIELD_INVALID", "commercialLicenceNo is malformed",
                        List.of("DL-003"), "commercialLicenceNo");
            }
        }
    }

    public Company {
        Objects.requireNonNull(licence, "licence");
        nameNl = name("nameNl", nameNl);
        nameEn = name("nameEn", nameEn);
        if (nameNl == null && nameEn == null) {
            throw DomainException.validation("NAME_REQUIRED", "the company needs a name in at least one language",
                    List.of("DL-003", "CC-012"));
        }
        activities = Codes.codes("activities", activities);
        if (licenceExpiry == null) {
            throw DomainException.validation("FIELD_INVALID", "licenceExpiry is required", List.of("DL-004"),
                    "licenceExpiry");
        }
        signatories = fileNumbers("signatories", signatories);
        fleetManagers = fileNumbers("fleetManagers", fleetManagers);
        if (signatories.isEmpty()) {
            throw DomainException.validation("SIGNATORY_REQUIRED", "a company needs at least one authorised signatory",
                    List.of("DL-004"));
        }
    }

    /** The same company with other representatives (DL-004); licence fields are unchanged. */
    public Company withRepresentatives(List<String> newSignatories, List<String> newFleetManagers) {
        return new Company(licence, nameNl, nameEn, activities, licenceExpiry, newSignatories, newFleetManagers);
    }

    /** Every file number named as a signatory or fleet manager, without repeats. */
    public List<String> representatives() {
        return java.util.stream.Stream.concat(signatories.stream(), fleetManagers.stream()).distinct().toList();
    }

    private static String name(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > 200 || v.chars().anyMatch(Character::isISOControl)) {
            throw DomainException.validation("FIELD_INVALID", field + " is malformed", List.of("DL-003"), field);
        }
        return v;
    }

    private static List<String> fileNumbers(String field, List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_REPRESENTATIVES) {
            throw DomainException.validation("FIELD_INVALID", field + " lists too many files", List.of("DL-004"),
                    field);
        }
        if (values.stream().distinct().count() != values.size()) {
            throw DomainException.validation("LIST_DUPLICATES", field + " repeats a value", List.of("DL-004"), field);
        }
        for (String v : values) {
            if (v == null || !FILE_NUMBER.matcher(v).matches()) {
                throw DomainException.validation("FIELD_INVALID", field + " holds a malformed file number",
                        List.of("DL-004"), field);
            }
        }
        return List.copyOf(values);
    }
}
