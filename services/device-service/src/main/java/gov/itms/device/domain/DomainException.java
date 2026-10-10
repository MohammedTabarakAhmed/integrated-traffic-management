package gov.itms.device.domain;

import java.util.List;

/** A device-domain validation, state or lookup failure. */
public final class DomainException extends RuntimeException {

    public enum Kind { VALIDATION, NOT_FOUND, CONFLICT, FORBIDDEN }

    private final Kind kind;
    private final String code;
    private final List<String> requirementRefs;

    public DomainException(Kind kind, String code, String message, List<String> requirementRefs) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.requirementRefs = List.copyOf(requirementRefs);
    }

    public static DomainException validation(String code, String message, String... refs) {
        return new DomainException(Kind.VALIDATION, code, message, List.of(refs));
    }

    public static DomainException conflict(String code, String message, String... refs) {
        return new DomainException(Kind.CONFLICT, code, message, List.of(refs));
    }

    public Kind kind() { return kind; }

    public String code() { return code; }

    public List<String> requirementRefs() { return requirementRefs; }
}
