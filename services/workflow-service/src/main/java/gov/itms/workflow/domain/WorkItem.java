package gov.itms.workflow.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A case moving through a workflow (CC-060..CC-065; ADR-049). It sits in one step at a time, in the queue of that
 * step's role for its region and service centre (CC-061), with an SLA due time (CC-062). It may be returned to the
 * customer for more information, which pauses the SLA clock until the customer responds (CC-065).
 *
 * <p>States: OPEN (in a queue, assigned or not) → RETURNED (waiting for the customer) → OPEN …; COMPLETED with the
 * outcome of the last step, or CANCELLED by the owning service. Completed and cancelled items never change again.
 */
public final class WorkItem {

    public enum Status { OPEN, RETURNED, COMPLETED, CANCELLED }

    public static final String REFERENCE = "^[A-Z0-9][A-Z0-9-]{0,59}$";
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(REFERENCE);
    private static final Pattern SCOPE_CODE = Pattern.compile("^[A-Z0-9_]{1,40}$");

    /** What the owning service gives when it starts a case. */
    public record Start(String reference, String serviceCode, String region, String centre, String customerId,
                        String workflowCode) {
        public Start {
            if (reference == null || !REFERENCE_PATTERN.matcher(reference).matches()) {
                throw WorkflowDefinition.invalid("reference");
            }
            if (region == null || !SCOPE_CODE.matcher(region).matches()) {
                throw WorkflowDefinition.invalid("region");
            }
            if (centre != null && !SCOPE_CODE.matcher(centre).matches()) {
                throw WorkflowDefinition.invalid("centre");
            }
            if (customerId != null && (customerId.isBlank() || customerId.length() > 100)) {
                throw WorkflowDefinition.invalid("customerId");
            }
        }
    }

    private final UUID id;
    private final Start start;
    private final int definitionVersion;
    private final Instant createdAt;
    private String stepCode;
    private String queueRole;
    private String escalationRole;
    private Status status;
    private String outcome;
    private String assignee;
    private Instant stepStartedAt;
    private Instant dueAt;
    private Instant pausedAt;
    private Instant escalatedAt;
    private Instant closedAt;
    private long version;

    private WorkItem(UUID id, Start start, int definitionVersion, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.start = Objects.requireNonNull(start);
        this.definitionVersion = definitionVersion;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static WorkItem open(UUID id, Start start, WorkflowDefinition definition, Instant now) {
        if (!definition.serviceCode().equals(start.serviceCode())) {
            throw DomainException.validation("WORKFLOW_SERVICE_MISMATCH",
                    "workflow " + definition.code() + " belongs to another service", List.of("CC-060"),
                    definition.code());
        }
        WorkItem item = new WorkItem(id, start, definition.versionNo(), now);
        item.status = Status.OPEN;
        item.enter(definition.step(definition.firstStep()), now);
        return item;
    }

    /** Rebuilds a stored item. */
    public static WorkItem restore(UUID id, Start start, int definitionVersion, Instant createdAt, String stepCode,
                                   String queueRole, String escalationRole, Status status, String outcome,
                                   String assignee, Instant stepStartedAt, Instant dueAt, Instant pausedAt,
                                   Instant escalatedAt, Instant closedAt, long version) {
        WorkItem w = new WorkItem(id, start, definitionVersion, createdAt);
        w.stepCode = stepCode;
        w.queueRole = queueRole;
        w.escalationRole = escalationRole;
        w.status = status;
        w.outcome = outcome;
        w.assignee = assignee;
        w.stepStartedAt = stepStartedAt;
        w.dueAt = dueAt;
        w.pausedAt = pausedAt;
        w.escalatedAt = escalatedAt;
        w.closedAt = closedAt;
        w.version = version;
        return w;
    }

    private void enter(WorkflowDefinition.Step step, Instant now) {
        stepCode = step.code();
        queueRole = step.role();
        escalationRole = step.escalationRole();
        assignee = null;
        stepStartedAt = now;
        dueAt = now.plus(step.sla());
        escalatedAt = null;
    }

    public void assign(String userId) {
        requireOpen();
        assignee = Objects.requireNonNull(userId);
    }

    public void release() {
        requireOpen();
        if (assignee == null) {
            throw DomainException.conflict("WORK_ITEM_NOT_ASSIGNED", "the work item has no assignee",
                    List.of("CC-061"));
        }
        assignee = null;
    }

    /**
     * Records the assignee's outcome for the current step and moves on (CC-060): to the next step's queue, or to the
     * end. Returns the step entered, or {@code null} when the workflow ended.
     */
    public WorkflowDefinition.Step decide(String actorId, String outcomeCode, WorkflowDefinition definition,
                                          Instant now) {
        requireOpen();
        requireAssignee(actorId);
        WorkflowDefinition.Step step = definition.step(stepCode);
        String next = step.outcomes().get(outcomeCode);
        if (next == null) {
            throw DomainException.validation("OUTCOME_INVALID",
                    "step " + stepCode + " has no outcome " + outcomeCode, List.of("CC-060"), outcomeCode,
                    String.join(", ", step.outcomes().keySet()));
        }
        if (WorkflowDefinition.END.equals(next)) {
            status = Status.COMPLETED;
            outcome = outcomeCode;
            closedAt = now;
            assignee = null;
            return null;
        }
        WorkflowDefinition.Step entered = definition.step(next);
        enter(entered, now);
        return entered;
    }

    /** Returns the case to the customer for more information (CC-065); the SLA clock stops. */
    public void returnToCustomer(String actorId, Instant now) {
        requireOpen();
        requireAssignee(actorId);
        if (start.customerId() == null) {
            throw DomainException.conflict("NO_CUSTOMER", "this case has no customer to return it to",
                    List.of("CC-065"));
        }
        status = Status.RETURNED;
        pausedAt = now;
    }

    /** The customer answered (CC-065): the clock resumes and the due time moves by the paused time. */
    public Duration resume(Instant now) {
        if (status != Status.RETURNED) {
            throw DomainException.conflict("WORK_ITEM_NOT_RETURNED", "the case is not waiting for the customer",
                    List.of("CC-065"), status.name());
        }
        Duration paused = Duration.between(pausedAt, now);
        if (paused.isNegative()) {
            paused = Duration.ZERO;
        }
        dueAt = dueAt.plus(paused);
        pausedAt = null;
        status = Status.OPEN;
        return paused;
    }

    /** Marks the item escalated to the step's supervisor role once it is overdue (CC-062). */
    public boolean escalateIfOverdue(Instant now) {
        if (status != Status.OPEN || escalatedAt != null || !now.isAfter(dueAt)) {
            return false;
        }
        escalatedAt = now;
        return true;
    }

    public void cancel(Instant now) {
        if (status == Status.COMPLETED || status == Status.CANCELLED) {
            throw closed();
        }
        status = Status.CANCELLED;
        closedAt = now;
        assignee = null;
        pausedAt = null;
    }

    public boolean overdue(Instant now) {
        return status == Status.OPEN && now.isAfter(dueAt);
    }

    private void requireOpen() {
        if (status == Status.RETURNED) {
            throw DomainException.conflict("WORK_ITEM_RETURNED", "the case is waiting for the customer",
                    List.of("CC-065"));
        }
        if (status != Status.OPEN) {
            throw closed();
        }
    }

    private void requireAssignee(String actorId) {
        if (!actorId.equals(assignee)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "NOT_ASSIGNEE",
                    "only the assignee can act on this work item", List.of("CC-061"));
        }
    }

    private DomainException closed() {
        return DomainException.conflict("WORK_ITEM_CLOSED", "the work item is already " + status, List.of("CC-060"),
                status.name());
    }

    public UUID id() {
        return id;
    }

    public Start start() {
        return start;
    }

    public int definitionVersion() {
        return definitionVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String stepCode() {
        return stepCode;
    }

    public String queueRole() {
        return queueRole;
    }

    public String escalationRole() {
        return escalationRole;
    }

    public Status status() {
        return status;
    }

    public String outcome() {
        return outcome;
    }

    public String assignee() {
        return assignee;
    }

    public Instant stepStartedAt() {
        return stepStartedAt;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public Instant pausedAt() {
        return pausedAt;
    }

    public Instant escalatedAt() {
        return escalatedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    /** Called by the repository after a successful save: the stored version moved on by one. */
    public void saved() {
        version++;
    }

    public long version() {
        return version;
    }
}
