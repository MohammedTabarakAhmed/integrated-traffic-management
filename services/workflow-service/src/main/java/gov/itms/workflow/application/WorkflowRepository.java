package gov.itms.workflow.application;

import gov.itms.workflow.domain.WorkItem;
import gov.itms.workflow.domain.WorkRecords;
import gov.itms.workflow.domain.WorkflowDefinition;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Workflow definitions, work items, their timeline and notes, and queue members (CC-060..CC-065). */
public interface WorkflowRepository {

    /** Filters of a work list (CC-063); {@code null} means any. */
    record ListFilter(Collection<WorkItem.Status> statuses, String serviceCode, Boolean overdue, Boolean escalated,
                      Instant now, int limit) {
    }

    // definitions

    void lockDefinition(String code);

    int nextDefinitionVersion(String code);

    void insertDefinition(WorkflowDefinition definition);

    Optional<WorkflowDefinition> findDefinition(String code, int versionNo);

    Optional<WorkflowDefinition> latestDefinition(String code);

    /** The latest version of every workflow, optionally of one service. */
    List<WorkflowDefinition> latestDefinitions(String serviceCode);

    // work items

    void insertItem(WorkItem item, String workflowCode);

    Optional<WorkItem> findItem(UUID id);

    Optional<WorkItem> findItemForUpdate(UUID id);

    /** Saves the item's mutable state; fails with an optimistic-locking error if it changed since read. */
    void updateItem(WorkItem item);

    boolean activeItemExists(String reference, String workflowCode);

    String workflowCodeOf(UUID itemId);

    List<WorkItem> assignedTo(String userId, ListFilter filter);

    /** Items in the queues of these roles (any region); the caller filters by scope. */
    List<WorkItem> inQueues(Collection<String> roles, ListFilter filter);

    /** Escalated items whose escalation role is one of these. */
    List<WorkItem> escalatedTo(Collection<String> roles, ListFilter filter);

    /** OPEN, not yet escalated items due before {@code now}; locked, skipping rows other instances hold. */
    List<WorkItem> overdueForEscalation(Instant now, int limit);

    // timeline and notes

    void insertHistory(WorkRecords.HistoryEntry entry);

    List<WorkRecords.HistoryEntry> history(UUID itemId);

    void insertNote(WorkRecords.Note note);

    List<WorkRecords.Note> notes(UUID itemId);

    // queue members

    void upsertMember(WorkRecords.QueueMember member);

    /** Members of a role's queues with their count of OPEN items assigned. */
    List<WorkRecords.QueueMember> members(String role);

    List<WorkRecords.QueueMember> membershipsOf(String userId);

    void markAssigned(String userId, String role, Instant at);
}
