package gov.itms.trafficfile.domain;

import java.util.List;

/**
 * A person's given and family names in the national language and in English (CC-012: both where available). At least
 * one complete pair is required.
 */
public record PersonName(String givenNl, String familyNl, String givenEn, String familyEn) {

    public PersonName {
        givenNl = part("givenNl", givenNl);
        familyNl = part("familyNl", familyNl);
        givenEn = part("givenEn", givenEn);
        familyEn = part("familyEn", familyEn);
        boolean national = givenNl != null && familyNl != null;
        boolean english = givenEn != null && familyEn != null;
        if (!national && !english) {
            throw DomainException.validation("NAME_REQUIRED",
                    "a given and a family name are required in at least one language", List.of("DL-005", "CC-012"));
        }
    }

    private static String part(String field, String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > 100 || stripped.chars().anyMatch(Character::isISOControl)) {
            throw DomainException.validation("FIELD_INVALID", field + " is malformed", List.of("DL-005"), field);
        }
        return stripped;
    }
}
