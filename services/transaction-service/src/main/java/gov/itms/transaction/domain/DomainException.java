package gov.itms.transaction.domain;

import java.util.List;

/**
 * A business-rule violation. {@code code} is a stable machine code used for messages in both languages and for
 * API problem responses; {@code kind} decides the HTTP status in the API layer.
 */
public class DomainException extends RuntimeException {

    public enum Kind { VALIDATION, NOT_FOUND, CONFLICT, SEGREGATION_OF_DUTIES }

    private final Kind kind;
    private final String code;
    private final List<String> requirementRefs;
    private final transient Object[] args;

    public DomainException(Kind kind, String code, String message, List<String> requirementRefs, Object... args) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.requirementRefs = List.copyOf(requirementRefs);
        this.args = args;
    }

    public static DomainException validation(String code, String message, Object... args) {
        return new DomainException(Kind.VALIDATION, code, message, List.of("CC-020"), args);
    }

    public static DomainException notFound(String code, String message, Object... args) {
        return new DomainException(Kind.NOT_FOUND, code, message, List.of(), args);
    }

    public static DomainException conflict(String code, String message, Object... args) {
        return new DomainException(Kind.CONFLICT, code, message, List.of("CC-114"), args);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public List<String> requirementRefs() {
        return requirementRefs;
    }

    public Object[] args() {
        return args.clone();
    }
}
