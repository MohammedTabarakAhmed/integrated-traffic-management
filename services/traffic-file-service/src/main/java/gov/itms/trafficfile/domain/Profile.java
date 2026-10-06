package gov.itms.trafficfile.domain;

import java.util.List;

/**
 * What staff record about a person beyond the identity (DL-005): contact details, address, blood group, education,
 * profession, languages, physical conditions, religion (only where the law requires it) and people-of-determination
 * status. Codes are reference-data codes; health-related fields are classified secret (DATA_MODEL §3).
 */
public record Profile(
        String mobile,
        String email,
        String address,
        String bloodGroup,
        String educationLevel,
        String profession,
        List<String> languages,
        List<String> physicalConditions,
        String religion,
        String determinationStatus) {

    public static final Profile EMPTY = new Profile(null, null, null, null, null, null, List.of(), List.of(), null,
            null);

    public Profile {
        mobile = Codes.optionalMobile(blankToNull(mobile));
        email = Codes.optionalEmail(blankToNull(email));
        address = text("address", address, 500);
        bloodGroup = Codes.optionalCode("bloodGroup", blankToNull(bloodGroup));
        educationLevel = Codes.optionalCode("educationLevel", blankToNull(educationLevel));
        profession = Codes.optionalCode("profession", blankToNull(profession));
        languages = Codes.codes("languages", languages);
        physicalConditions = Codes.codes("physicalConditions", physicalConditions);
        religion = Codes.optionalCode("religion", blankToNull(religion));
        determinationStatus = Codes.optionalCode("determinationStatus", blankToNull(determinationStatus));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(String field, String value, int max) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        v = v.strip();
        if (v.length() > max || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("FIELD_INVALID", field + " is malformed", List.of("DL-005"), field);
        }
        return v;
    }
}
