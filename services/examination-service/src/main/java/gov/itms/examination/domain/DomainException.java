package gov.itms.examination.domain;

import java.util.List;

/** A business rule refused the request; mapped to problem+json with the requirement references. */
public class DomainException extends RuntimeException {
    public enum Kind { VALIDATION, NOT_FOUND, CONFLICT, OUT_OF_SCOPE }

    private final Kind kind;
    private final String code;
    private final List<String> requirements;

    public DomainException(Kind kind, String code, String message, List<String> requirements) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.requirements = List.copyOf(requirements);
    }

    public static DomainException invalid(String code, String message, String... ids) {
        return new DomainException(Kind.VALIDATION, code, message, List.of(ids));
    }

    public static DomainException missing(String code, String message, String... ids) {
        return new DomainException(Kind.NOT_FOUND, code, message, List.of(ids));
    }

    public static DomainException conflict(String code, String message, String... ids) {
        return new DomainException(Kind.CONFLICT, code, message, List.of(ids));
    }

    public static DomainException outOfScope(String message, String... ids) {
        return new DomainException(Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED", message, List.of(ids));
    }

    public Kind kind() { return kind; }
    public String code() { return code; }
    public List<String> requirements() { return requirements; }
}
