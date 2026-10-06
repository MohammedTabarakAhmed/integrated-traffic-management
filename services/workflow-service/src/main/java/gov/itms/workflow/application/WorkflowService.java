package gov.itms.workflow.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.workflow.domain.Channel;
import gov.itms.workflow.domain.DomainException;
import gov.itms.workflow.domain.WorkItem;
import gov.itms.workflow.domain.WorkRecords;
import gov.itms.workflow.domain.WorkflowDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflows and work items (CC-060..CC-065; ADR-049).
 * <ul>
 *   <li>workflow administrators publish definitions as new versions ({@value #DEFINITION_MANAGE}); running items keep
 *       the version they started with (CC-060);</li>
 *   <li>the owning service starts a case for a business reference ({@value #ITEM_CREATE}); the item enters the first
 *       step's queue (role + region + centre) and is assigned automatically by round robin or least loaded among the
 *       queue's available members, or waits to be claimed or assigned (CC-061);</li>
 *   <li>staff work in the queues of their roles within their grants' regions and centres ({@value #ITEM_WORK}):
 *       personal and team lists with filters, claim, release, decide (CC-063); supervisors ({@value #ITEM_SUPERVISE})
 *       assign, also in bulk, and see escalated items (CC-062, CC-063);</li>
 *   <li>internal notes with attachments, never shown to customers (CC-064); returning a case to the customer pauses
 *       the SLA clock until the customer responds ({@value #ITEM_RESPOND}, CC-065);</li>
 *   <li>a job escalates overdue items to the step's supervisor role and emits {@code itms.workflow.item-overdue}, from
 *       which notification-service tells the supervisor and the customer of the delay (CC-062).</li>
 * </ul>
 * Every change is audited (CC-120) and written to the item's timeline.
 */
public class WorkflowService {

    public static final String DEFINITION_MANAGE = "workflow.definition.manage";
    public static final String DEFINITION_READ = "workflow.definition.read";
    public static final String ITEM_CREATE = "workflow.item.create";
    public static final String ITEM_WORK = "workflow.item.work";
    public static final String ITEM_SUPERVISE = "workflow.item.supervise";
    public static final String ITEM_RESPOND = "workflow.item.respond";
    static final String ITEM = "WorkItem";
    private static final int MAX_LIST = 200;
    private static final int MAX_BULK = 100;

    public enum ListKind { PERSONAL, TEAM, ESCALATED }

    /** The result of one item of a bulk action. */
    public record BulkResult(UUID itemId, boolean done, String problem) {
    }

    /** An item with the code of its workflow. */
    public record ItemView(WorkItem item, String workflowCode, boolean overdue) {
    }

    private final WorkflowRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public WorkflowService(WorkflowRepository repo, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---- definitions (CC-060)

    @Transactional
    public WorkflowDefinition publish(String code, String serviceCode, String nameNl, String nameEn, String firstStep,
                                      List<WorkflowDefinition.Step> steps, String reason, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(DEFINITION_MANAGE));
        String why = reason(reason);
        repo.lockDefinition(code);
        Optional<WorkflowDefinition> previous = repo.latestDefinition(code);
        if (previous.isPresent() && !previous.get().serviceCode().equals(serviceCode)) {
            throw DomainException.validation("WORKFLOW_SERVICE_MISMATCH",
                    "workflow " + code + " belongs to another service", List.of("CC-060"), code);
        }
        WorkflowDefinition d = new WorkflowDefinition(code, repo.nextDefinitionVersion(code), serviceCode, nameNl,
                nameEn, firstStep, steps, caller.actor(), clock.instant(), why);
        repo.insertDefinition(d);
        audit.record("WORKFLOW_PUBLISHED", "WorkflowDefinition", code + ":" + d.versionNo(),
                previous.map(p -> Map.<String, Object>of("versionNo", p.versionNo())).orElse(null),
                Map.of("versionNo", d.versionNo(), "steps", d.steps().size()), why);
        return d;
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition definition(String code, Integer versionNo, Caller caller) {
        require(caller.grants().holds(DEFINITION_READ) || caller.grants().holds(DEFINITION_MANAGE));
        return (versionNo == null ? repo.latestDefinition(code) : repo.findDefinition(code, versionNo))
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "WORKFLOW_NOT_FOUND",
                        "workflow " + code + " not found", List.of("CC-060"), code));
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinition> definitions(String serviceCode, Caller caller) {
        require(caller.grants().holds(DEFINITION_READ) || caller.grants().holds(DEFINITION_MANAGE));
        return repo.latestDefinitions(serviceCode);
    }

    // ---- starting and reading items

    @Transactional
    public ItemView start(WorkItem.Start start, Caller caller) {
        channelOf(caller);
        require(caller.grants().allowsAt(ITEM_CREATE, start.region(), start.centre()));
        WorkflowDefinition d = repo.latestDefinition(start.workflowCode())
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "WORKFLOW_NOT_FOUND",
                        "workflow " + start.workflowCode() + " not found", List.of("CC-060"), start.workflowCode()));
        if (repo.activeItemExists(start.reference(), d.code())) {
            throw DomainException.conflict("WORK_ITEM_EXISTS",
                    "a case for " + start.reference() + " is already running in this workflow", List.of("CC-060"),
                    start.reference());
        }
        Instant now = clock.instant();
        WorkItem item = WorkItem.open(UUID.randomUUID(), start, d, now);
        repo.insertItem(item, d.code());
        history(item, "CREATED", caller.actor().id(), now, Map.of("workflow", d.code(), "version", d.versionNo()));
        autoAssign(item, d.step(item.stepCode()), now);
        repo.updateItem(item);
        audit.record("WORK_ITEM_CREATED", ITEM, item.id().toString(), null, snapshot(item, d.code()), null);
        events.record("itms.workflow.item-created", item.start().reference(), snapshot(item, d.code()));
        return view(item, d.code(), now);
    }

    @Transactional
    public ItemView get(UUID id, Caller caller) {
        WorkItem item = find(id);
        requireSees(item, caller);
        audit.record("WORK_ITEM_VIEWED", ITEM, id.toString(), null, null, null);
        return view(item, repo.workflowCodeOf(id), clock.instant());
    }

    @Transactional
    public List<WorkRecords.HistoryEntry> history(UUID id, Caller caller) {
        requireSees(find(id), caller);
        audit.record("WORK_ITEM_HISTORY_VIEWED", ITEM, id.toString(), null, null, null);
        return repo.history(id);
    }

    /** Personal, team or escalated work list with filters (CC-063), soonest due first. */
    @Transactional(readOnly = true)
    public List<ItemView> workList(ListKind kind, List<WorkItem.Status> statuses, String serviceCode, Boolean overdue,
                                   Boolean escalated, int limit, Caller caller) {
        if (limit < 1 || limit > MAX_LIST) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1.." + MAX_LIST, List.of("CC-063"),
                    "limit");
        }
        Instant now = clock.instant();
        List<WorkItem.Status> wanted = statuses == null || statuses.isEmpty()
                ? List.of(WorkItem.Status.OPEN, WorkItem.Status.RETURNED) : statuses;
        WorkflowRepository.ListFilter filter = new WorkflowRepository.ListFilter(wanted, serviceCode, overdue,
                escalated, now, MAX_LIST * 5);
        List<WorkItem> found = switch (kind) {
            case PERSONAL -> {
                require(caller.grants().holds(ITEM_WORK));
                yield repo.assignedTo(caller.actor().id(), filter);
            }
            case TEAM -> {
                List<Caller.RoleScope> scopes = new ArrayList<>(caller.grants().roleScopes(ITEM_WORK));
                require(!scopes.isEmpty() || caller.grants().holds(ITEM_SUPERVISE));
                Set<String> roles = new HashSet<>();
                scopes.forEach(s -> roles.add(s.role()));
                yield roles.isEmpty() && !caller.grants().holds(ITEM_SUPERVISE) ? List.of()
                        : repo.inQueues(roles, filter).stream()
                        .filter(i -> caller.worksIn(ITEM_WORK, i.queueRole(), i.start().region(), i.start().centre())
                                || supervises(caller, i))
                        .toList();
            }
            case ESCALATED -> {
                List<Caller.RoleScope> scopes = caller.grants().roleScopes(ITEM_SUPERVISE);
                require(!scopes.isEmpty());
                Set<String> roles = new HashSet<>();
                scopes.forEach(s -> roles.add(s.role()));
                yield repo.escalatedTo(roles, filter).stream()
                        .filter(i -> caller.worksIn(ITEM_SUPERVISE, i.escalationRole(), i.start().region(),
                                i.start().centre()))
                        .toList();
            }
        };
        List<ItemView> views = new ArrayList<>();
        for (WorkItem i : found) {
            if (views.size() == limit) {
                break;
            }
            views.add(view(i, repo.workflowCodeOf(i.id()), now));
        }
        return views;
    }

    // ---- assignment (CC-061, CC-063)

    @Transactional
    public ItemView claim(UUID id, Caller caller) {
        channelOf(caller);
        WorkItem item = findForUpdate(id);
        if (!caller.worksIn(ITEM_WORK, item.queueRole(), item.start().region(), item.start().centre())) {
            throw outOfScope();
        }
        if (item.assignee() != null && !item.assignee().equals(caller.actor().id())) {
            throw DomainException.conflict("WORK_ITEM_ASSIGNED", "the work item is already assigned to someone else",
                    List.of("CC-061"));
        }
        Instant now = clock.instant();
        item.assign(caller.actor().id());
        repo.updateItem(item);
        repo.markAssigned(caller.actor().id(), item.queueRole(), now);
        history(item, "CLAIMED", caller.actor().id(), now, Map.of());
        audit.record("WORK_ITEM_CLAIMED", ITEM, id.toString(), null, Map.of("assignee", caller.actor().id()), null);
        return view(item, repo.workflowCodeOf(id), now);
    }

    @Transactional
    public ItemView release(UUID id, String reason, Caller caller) {
        channelOf(caller);
        WorkItem item = findForUpdate(id);
        boolean own = caller.actor().id().equals(item.assignee());
        if (!own && !supervises(caller, item)) {
            throw outOfScope();
        }
        String before = item.assignee();
        item.release();
        repo.updateItem(item);
        Instant now = clock.instant();
        history(item, "RELEASED", caller.actor().id(), now, Map.of("previousAssignee", before));
        audit.record("WORK_ITEM_RELEASED", ITEM, id.toString(), Map.of("assignee", before), null,
                reason == null ? null : reason(reason));
        return view(item, repo.workflowCodeOf(id), now);
    }

    /** A supervisor assigns an item to a member of its queue (CC-061). */
    @Transactional
    public ItemView assign(UUID id, String assigneeId, String reason, Caller caller) {
        channelOf(caller);
        return assignOne(id, assigneeId, reason, caller);
    }

    /** Assigns several items at once (CC-063); each item succeeds or fails on its own. */
    @Transactional
    public List<BulkResult> bulkAssign(List<UUID> ids, String assigneeId, String reason, Caller caller) {
        channelOf(caller);
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK || new HashSet<>(ids).size() != ids.size()) {
            throw DomainException.validation("BULK_INVALID", "give 1 to " + MAX_BULK + " different work items",
                    List.of("CC-063"));
        }
        List<BulkResult> results = new ArrayList<>();
        for (UUID id : ids) {
            try {
                assignOne(id, assigneeId, reason, caller);
                results.add(new BulkResult(id, true, null));
            } catch (DomainException e) {
                results.add(new BulkResult(id, false, e.code()));
            }
        }
        return results;
    }

    private ItemView assignOne(UUID id, String assigneeId, String reason, Caller caller) {
        WorkItem item = findForUpdate(id);
        if (!supervises(caller, item)) {
            throw outOfScope();
        }
        boolean member = repo.members(item.queueRole()).stream().anyMatch(m -> m.userId().equals(assigneeId)
                && m.reaches(item.start().region(), item.start().centre()));
        if (!member) {
            throw DomainException.validation("ASSIGNEE_NOT_IN_QUEUE",
                    "the assignee does not work in this queue", List.of("CC-061"), assigneeId);
        }
        Instant now = clock.instant();
        String before = item.assignee();
        item.assign(assigneeId);
        repo.updateItem(item);
        repo.markAssigned(assigneeId, item.queueRole(), now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("assignee", assigneeId);
        detail.put("previousAssignee", before);
        history(item, "ASSIGNED", caller.actor().id(), now, detail);
        audit.record("WORK_ITEM_ASSIGNED", ITEM, id.toString(), before == null ? null : Map.of("assignee", before),
                Map.of("assignee", assigneeId), reason == null ? null : reason(reason));
        return view(item, repo.workflowCodeOf(id), now);
    }

    /** The caller joins or leaves automatic assignment in the queues of their roles (CC-061). */
    @Transactional
    public List<WorkRecords.QueueMember> setAvailability(boolean available, Caller caller) {
        channelOf(caller);
        List<Caller.RoleScope> scopes = caller.grants().roleScopes(ITEM_WORK);
        require(!scopes.isEmpty());
        for (Caller.RoleScope s : scopes) {
            repo.upsertMember(new WorkRecords.QueueMember(caller.actor().id(), caller.actor().username(), s.role(),
                    s.regions(), s.centres(), available, null, 0));
        }
        audit.record(available ? "QUEUE_JOINED" : "QUEUE_LEFT", "QueueMember", caller.actor().id(), null,
                Map.of("roles", scopes.stream().map(Caller.RoleScope::role).distinct().sorted().toList()), null);
        return repo.membershipsOf(caller.actor().id());
    }

    @Transactional(readOnly = true)
    public List<WorkRecords.QueueMember> memberships(Caller caller) {
        require(caller.grants().holds(ITEM_WORK));
        return repo.membershipsOf(caller.actor().id());
    }

    // ---- decisions, returns, notes

    @Transactional
    public ItemView decide(UUID id, String outcome, String reason, Caller caller) {
        channelOf(caller);
        WorkItem item = findForUpdate(id);
        String code = repo.workflowCodeOf(id);
        WorkflowDefinition d = repo.findDefinition(code, item.definitionVersion()).orElseThrow();
        String fromStep = item.stepCode();
        Instant now = clock.instant();
        WorkflowDefinition.Step entered = item.decide(caller.actor().id(), outcome, d, now);
        String why = reason == null ? null : reason(reason);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("step", fromStep);
        detail.put("outcome", outcome);
        detail.put("nextStep", entered == null ? WorkflowDefinition.END : entered.code());
        history(item, "DECIDED", caller.actor().id(), now, detail);
        if (entered != null) {
            autoAssign(item, entered, now);
        }
        repo.updateItem(item);
        audit.record("WORK_ITEM_DECIDED", ITEM, id.toString(), Map.of("step", fromStep), snapshot(item, code), why);
        Map<String, Object> event = snapshot(item, code);
        event.put("decidedStep", fromStep);
        event.put("decidedOutcome", outcome);
        events.record(entered == null ? "itms.workflow.item-completed" : "itms.workflow.step-entered",
                item.start().reference(), event);
        return view(item, code, now);
    }

    /** Returns the case to the customer for more information (CC-065); the customer is notified by event. */
    @Transactional
    public ItemView returnToCustomer(UUID id, String requestedInformation, Caller caller) {
        channelOf(caller);
        String what = reason(requestedInformation);
        WorkItem item = findForUpdate(id);
        Instant now = clock.instant();
        item.returnToCustomer(caller.actor().id(), now);
        repo.updateItem(item);
        history(item, "RETURNED", caller.actor().id(), now, Map.of("requestedInformation", what));
        audit.record("WORK_ITEM_RETURNED", ITEM, id.toString(), null, snapshot(item, repo.workflowCodeOf(id)), what);
        Map<String, Object> event = snapshot(item, repo.workflowCodeOf(id));
        event.put("requestedInformation", what);
        events.record("itms.workflow.item-returned", item.start().reference(), event);
        return view(item, repo.workflowCodeOf(id), now);
    }

    /** The customer's answer (CC-065), given by the customer or by staff for them; the SLA clock resumes. */
    @Transactional
    public ItemView customerResponse(UUID id, String message, List<String> attachmentIds, Caller caller) {
        channelOf(caller);
        WorkItem item = findForUpdate(id);
        boolean customer = item.start().customerId() != null && item.start().customerId().equals(caller.actor().id());
        if (!customer && !caller.grants().allowsAt(ITEM_RESPOND, item.start().region(), item.start().centre())) {
            throw outOfScope();
        }
        Instant now = clock.instant();
        WorkRecords.Note note = new WorkRecords.Note(UUID.randomUUID(), id, message, attachmentIds, caller.actor(),
                now);
        Duration paused = item.resume(now);
        repo.updateItem(item);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pausedSeconds", paused.toSeconds());
        detail.put("attachments", note.attachmentIds());
        detail.put("message", note.text());
        history(item, "CUSTOMER_RESPONDED", caller.actor().id(), now, detail);
        audit.record("WORK_ITEM_RESUMED", ITEM, id.toString(), null, snapshot(item, repo.workflowCodeOf(id)), null);
        events.record("itms.workflow.item-resumed", item.start().reference(), snapshot(item, repo.workflowCodeOf(id)));
        return view(item, repo.workflowCodeOf(id), now);
    }

    @Transactional
    public ItemView cancel(UUID id, String reason, Caller caller) {
        channelOf(caller);
        String why = reason(reason);
        WorkItem item = findForUpdate(id);
        require(caller.grants().allowsAt(ITEM_CREATE, item.start().region(), item.start().centre()));
        Instant now = clock.instant();
        item.cancel(now);
        repo.updateItem(item);
        history(item, "CANCELLED", caller.actor().id(), now, Map.of());
        audit.record("WORK_ITEM_CANCELLED", ITEM, id.toString(), null, snapshot(item, repo.workflowCodeOf(id)), why);
        events.record("itms.workflow.item-cancelled", item.start().reference(),
                snapshot(item, repo.workflowCodeOf(id)));
        return view(item, repo.workflowCodeOf(id), now);
    }

    /** Adds an internal note (CC-064). Staff who see the item may add notes, also after it is closed. */
    @Transactional
    public WorkRecords.Note addNote(UUID id, String text, List<String> attachmentIds, Caller caller) {
        channelOf(caller);
        WorkItem item = find(id);
        requireStaffSees(item, caller);
        WorkRecords.Note note = new WorkRecords.Note(UUID.randomUUID(), id, text, attachmentIds, caller.actor(),
                clock.instant());
        repo.insertNote(note);
        history(item, "NOTE_ADDED", caller.actor().id(), note.at(), Map.of("noteId", note.id().toString()));
        audit.record("WORK_ITEM_NOTE_ADDED", ITEM, id.toString(), null,
                Map.of("noteId", note.id().toString(), "attachments", note.attachmentIds().size()), null);
        return note;
    }

    @Transactional
    public List<WorkRecords.Note> notes(UUID id, Caller caller) {
        requireStaffSees(find(id), caller);
        audit.record("WORK_ITEM_NOTES_VIEWED", ITEM, id.toString(), null, null, null);
        return repo.notes(id);
    }

    // ---- SLA escalation (CC-062)

    /** Escalates up to {@code limit} overdue items; run by the scheduled job as the system. */
    @Transactional
    public int escalateOverdue(int limit) {
        Instant now = clock.instant();
        CurrentActor system = CurrentActor.system("workflow-sla-job");
        int escalated = 0;
        for (WorkItem item : repo.overdueForEscalation(now, limit)) {
            if (!item.escalateIfOverdue(now)) {
                continue;
            }
            repo.updateItem(item);
            String code = repo.workflowCodeOf(item.id());
            history(item, "ESCALATED", system.userId(), now, Map.of("escalationRole", item.escalationRole(),
                    "dueAt", item.dueAt().toString()));
            audit.recordAs(system, "WORK_ITEM_ESCALATED", ITEM, item.id().toString(), null, snapshot(item, code),
                    "SLA exceeded");
            Map<String, Object> event = snapshot(item, code);
            event.put("customerId", item.start().customerId());
            event.put("overdueSince", item.dueAt().toString());
            events.recordAs(system, "itms.workflow.item-overdue", item.start().reference(), event);
            escalated++;
        }
        return escalated;
    }

    // ---- helpers

    private void autoAssign(WorkItem item, WorkflowDefinition.Step step, Instant now) {
        if (step.assignment() == WorkflowDefinition.Assignment.MANUAL) {
            return;
        }
        Comparator<WorkRecords.QueueMember> order = step.assignment() == WorkflowDefinition.Assignment.LEAST_LOADED
                ? Comparator.comparingInt(WorkRecords.QueueMember::openItems).thenComparing(
                        WorkRecords.QueueMember::lastAssignedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                : Comparator.comparing(WorkRecords.QueueMember::lastAssignedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
        repo.members(step.role()).stream()
                .filter(m -> m.available() && m.reaches(item.start().region(), item.start().centre()))
                .min(order.thenComparing(WorkRecords.QueueMember::userId))
                .ifPresent(m -> {
                    item.assign(m.userId());
                    repo.markAssigned(m.userId(), step.role(), now);
                    history(item, "ASSIGNED", "workflow-assignment", now,
                            Map.of("assignee", m.userId(), "strategy", step.assignment().name()));
                });
    }

    private boolean supervises(Caller caller, WorkItem item) {
        return caller.grants().allowsAt(ITEM_SUPERVISE, item.start().region(), item.start().centre());
    }

    private void requireStaffSees(WorkItem item, Caller caller) {
        boolean sees = caller.worksIn(ITEM_WORK, item.queueRole(), item.start().region(), item.start().centre())
                || caller.actor().id().equals(item.assignee()) || supervises(caller, item);
        if (!sees) {
            throw outOfScope();
        }
    }

    private void requireSees(WorkItem item, Caller caller) {
        boolean sees = caller.worksIn(ITEM_WORK, item.queueRole(), item.start().region(), item.start().centre())
                || caller.actor().id().equals(item.assignee()) || supervises(caller, item)
                || caller.grants().allowsAt(ITEM_CREATE, item.start().region(), item.start().centre());
        if (!sees) {
            throw outOfScope();
        }
    }

    private void history(WorkItem item, String action, String actorId, Instant at, Map<String, Object> detail) {
        repo.insertHistory(new WorkRecords.HistoryEntry(item.id(), action, item.stepCode(), actorId, at, detail));
    }

    private WorkItem find(UUID id) {
        return repo.findItem(id).orElseThrow(() -> notFound(id));
    }

    private WorkItem findForUpdate(UUID id) {
        return repo.findItemForUpdate(id).orElseThrow(() -> notFound(id));
    }

    private ItemView view(WorkItem item, String code, Instant now) {
        return new ItemView(item, code, item.overdue(now));
    }

    static Map<String, Object> snapshot(WorkItem i, String workflowCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", i.id().toString());
        m.put("reference", i.start().reference());
        m.put("serviceCode", i.start().serviceCode());
        m.put("workflowCode", workflowCode);
        m.put("step", i.stepCode());
        m.put("queueRole", i.queueRole());
        m.put("region", i.start().region());
        m.put("centre", i.start().centre());
        m.put("status", i.status().name());
        m.put("outcome", i.outcome());
        m.put("assignee", i.assignee());
        m.put("dueAt", i.dueAt().toString());
        return m;
    }

    static String reason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("CC-060"));
        }
        return v;
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw outOfScope();
        }
    }

    private static DomainException outOfScope() {
        return new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                "you may not do this with this work item", List.of("CC-001", "CC-061"));
    }

    static Channel channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
        return caller.channel();
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "WORK_ITEM_NOT_FOUND",
                "work item " + id + " does not exist", List.of("CC-060"));
    }
}
