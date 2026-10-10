package gov.itms.crossborder.domain;

import java.util.List;

public class DomainException extends RuntimeException {
    public enum Kind { VALIDATION, NOT_FOUND, CONFLICT }
    private final Kind kind;
    private final String code;
    private final List<String> requirements;
    public DomainException(Kind kind, String code, String message, List<String> requirements) {
        super(message); this.kind=kind; this.code=code; this.requirements=List.copyOf(requirements);
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
    public List<String> requirements() { return requirements; }
}
