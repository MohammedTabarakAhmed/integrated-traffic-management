package gov.itms.workflow.domain;

import java.util.List;

/**
 * A business-rule violation. {@code code} is a stable machine code used for messages in both languages and for
 * API problem responses; {@code kind} decides the HTTP status in the API layer.
 */
public class DomainException extends RuntimeException {

    public enum Kind { VALIDATION, NOT_FOUND, CONFLICT, OUT_OF_SCOPE, SEGREGATION_OF_DUTIES }

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

    public static DomainException validation(String code, String message, List<String> refs, Object... args) {
        return new DomainException(Kind.VALIDATION, code, message, refs, args);
    }

    public static DomainException conflict(String code, String message, List<String> refs, Object... args) {
        return new DomainException(Kind.CONFLICT, code, message, refs, args);
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
