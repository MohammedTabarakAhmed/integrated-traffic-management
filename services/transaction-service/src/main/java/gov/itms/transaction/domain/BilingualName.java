package gov.itms.transaction.domain;

/** A name in the national language (right-to-left) and English (CC-010, CC-012, CC-020). */
public record BilingualName(String nl, String en) {

    public static final int MAX_LENGTH = 200;

    public BilingualName {
        nl = clean("name.nl", nl);
        en = clean("name.en", en);
    }

    private static String clean(String field, String value) {
        if (value == null || value.isBlank()) {
            throw DomainException.validation("NAME_REQUIRED", field + " is required in both languages", field);
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw DomainException.validation("NAME_TOO_LONG", field + " must be at most 200 characters", field);
        }
        return trimmed;
    }
}
