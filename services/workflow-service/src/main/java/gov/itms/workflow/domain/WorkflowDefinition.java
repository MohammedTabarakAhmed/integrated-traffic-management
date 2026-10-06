package gov.itms.workflow.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A configurable workflow (CC-060; ADR-049): the steps a case goes through for one service, as data. Each step names
 * the role whose queue receives it (CC-061), how it is assigned, its SLA and the supervisor role that receives it when
 * overdue (CC-062), and where each outcome leads: another step, or the end with that outcome. A definition is
 * published as a new version; a work item keeps the version it started with, so changing a workflow never changes
 * cases already running.
 */
public record WorkflowDefinition(String code, int versionNo, String serviceCode, String nameNl, String nameEn,
                                 String firstStep, List<Step> steps, Actor publishedBy, Instant publishedAt,
                                 String reason) {

    public enum StepKind { APPROVAL, REVIEW, INSPECTION, TASK }

    /** How a step's item gets an assignee when it enters the step (CC-061). */
    public enum Assignment { MANUAL, ROUND_ROBIN, LEAST_LOADED }

    public static final String END = "END";
    public static final String CODE = "^[A-Z][A-Z0-9_]{1,39}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{1,59}$");
    private static final Pattern SERVICE = Pattern.compile("^[A-Z][A-Z0-9_]{1,19}$");

    /**
     * One step. {@code outcomes} maps an outcome code (for example APPROVED, REJECTED, PASSED) to the next step's code
     * or {@link #END}.
     */
    public record Step(String code, StepKind kind, String nameNl, String nameEn, String role, Assignment assignment,
                       Duration sla, String escalationRole, Map<String, String> outcomes) {
        public Step {
            if (code == null || !CODE_PATTERN.matcher(code).matches() || END.equals(code)) {
                throw invalid("step code");
            }
            Objects.requireNonNull(kind, "kind");
            nameNl = name("step nameNl", nameNl);
            nameEn = name("step nameEn", nameEn);
            if (role == null || !ROLE.matcher(role).matches()) {
                throw invalid("step role");
            }
            Objects.requireNonNull(assignment, "assignment");
            if (sla == null || sla.compareTo(Duration.ofMinutes(15)) < 0 || sla.compareTo(Duration.ofDays(365)) > 0) {
                throw DomainException.validation("SLA_INVALID", "a step SLA is 15 minutes to 365 days",
                        List.of("CC-062"), code);
            }
            if (escalationRole == null || !ROLE.matcher(escalationRole).matches()) {
                throw invalid("step escalationRole");
            }
            outcomes = outcomes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(outcomes));
            if (outcomes.isEmpty() || outcomes.size() > 10
                    || outcomes.keySet().stream().anyMatch(k -> k == null || !CODE_PATTERN.matcher(k).matches())) {
                throw invalid("step outcomes");
            }
        }
    }

    public WorkflowDefinition {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw invalid("code");
        }
        if (serviceCode == null || !SERVICE.matcher(serviceCode).matches()) {
            throw invalid("serviceCode");
        }
        nameNl = name("nameNl", nameNl);
        nameEn = name("nameEn", nameEn);
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > 30) {
            throw invalid("steps");
        }
        Set<String> codes = new HashSet<>();
        for (Step s : steps) {
            if (!codes.add(s.code())) {
                throw DomainException.validation("WORKFLOW_INVALID", "step " + s.code() + " appears twice",
                        List.of("CC-060"), s.code());
            }
        }
        if (!codes.contains(firstStep)) {
            throw DomainException.validation("WORKFLOW_INVALID", "the first step is not one of the steps",
                    List.of("CC-060"), String.valueOf(firstStep));
        }
        for (Step s : steps) {
            for (String next : s.outcomes().values()) {
                if (!END.equals(next) && !codes.contains(next)) {
                    throw DomainException.validation("WORKFLOW_INVALID",
                            "step " + s.code() + " leads to an unknown step " + next, List.of("CC-060"), next);
                }
            }
        }
        if (steps.stream().noneMatch(s -> s.outcomes().containsValue(END))) {
            throw DomainException.validation("WORKFLOW_INVALID", "no step ends the workflow", List.of("CC-060"),
                    code);
        }
        Objects.requireNonNull(publishedBy);
        Objects.requireNonNull(publishedAt);
    }

    public Step step(String stepCode) {
        return steps.stream().filter(s -> s.code().equals(stepCode)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown step " + stepCode));
    }

    static String name(String field, String value) {
        if (value == null || value.isBlank() || value.strip().length() > 200
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field);
        }
        return value.strip();
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("CC-060"),
                field);
    }
}
