package gov.itms.reporting.domain;

import java.util.List;

/** A refused report request, with its problem code and the requirements behind the rule. */
public class DomainException extends RuntimeException {

    public enum Kind { VALIDATION, NOT_FOUND, FORBIDDEN }

    private final Kind kind;
    private final String code;
    private final List<String> requirements;

    public DomainException(Kind kind, String code, String message, List<String> requirements) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.requirements = List.copyOf(requirements);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public List<String> requirements() {
        return requirements;
    }
}
