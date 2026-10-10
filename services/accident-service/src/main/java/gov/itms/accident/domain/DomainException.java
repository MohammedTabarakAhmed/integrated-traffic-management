package gov.itms.accident.domain;

import java.util.List;

/** An accident-domain validation, state or lookup failure. */
public final class DomainException extends RuntimeException {
    public enum Kind { VALIDATION, NOT_FOUND, CONFLICT }
    private final Kind kind;
    private final String code;
    private final List<String> requirementRefs;
    public DomainException(Kind kind, String code, String message, List<String> refs) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.requirementRefs = List.copyOf(refs);
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
    public List<String> requirementRefs() { return requirementRefs; }
}
