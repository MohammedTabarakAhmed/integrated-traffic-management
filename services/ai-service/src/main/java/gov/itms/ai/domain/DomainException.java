package gov.itms.ai.domain;

import java.util.List;

public class DomainException extends RuntimeException {
    public enum Kind { VALIDATION, NOT_FOUND, CONFLICT, UNAVAILABLE, KILL_SWITCH, OUT_OF_SCOPE, SEGREGATION_OF_DUTIES, MODEL_UNAVAILABLE }
    private final Kind kind;
    private final String code;
    private final List<String> requirements;
    private final transient Object[] args;

    public DomainException(Kind kind, String code, String message, List<String> requirements) {
        this(kind, code, message, requirements, new Object[0]);
    }

    public DomainException(Kind kind, String code, String message, List<String> requirements, Object... args) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.requirements = List.copyOf(requirements);
        this.args = args == null ? new Object[0] : args.clone();
    }

    public Kind kind() { return kind; }
    public String code() { return code; }
    public List<String> requirements() { return requirements; }
    public List<String> requirementRefs() { return requirements; }
    public Object[] args() { return args.clone(); }

    public static DomainException validation(String message, String... requirements) {
        return new DomainException(Kind.VALIDATION, "BUSINESS_RULE_VIOLATION", message, List.of(requirements));
    }
    public static DomainException notFound(String message, String... requirements) {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", message, List.of(requirements));
    }
    public static DomainException conflict(String message, String... requirements) {
        return new DomainException(Kind.CONFLICT, "RESOURCE_CONFLICT", message, List.of(requirements));
    }
    public static DomainException unavailable(String message, String... requirements) {
        return new DomainException(Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", message, List.of(requirements));
    }
    public static DomainException killSwitch(String message, String... requirements) {
        return new DomainException(Kind.KILL_SWITCH, "AI_KILL_SWITCH_ACTIVATED", message, List.of(requirements));
    }

    // The form the application services use: an explicit problem code, the message, the requirement ids and the
    // values the message refers to.
    public static DomainException validation(String code, String message, List<String> requirements, Object... args) {
        return new DomainException(Kind.VALIDATION, code, message, requirements, args);
    }
    public static DomainException notFound(String code, String message, List<String> requirements, Object... args) {
        return new DomainException(Kind.NOT_FOUND, code, message, requirements, args);
    }
    public static DomainException conflict(String code, String message, List<String> requirements, Object... args) {
        return new DomainException(Kind.CONFLICT, code, message, requirements, args);
    }
    public static DomainException modelUnavailable(String code, String message, List<String> requirements, Object... args) {
        return new DomainException(Kind.MODEL_UNAVAILABLE, code, message, requirements, args);
    }
}
