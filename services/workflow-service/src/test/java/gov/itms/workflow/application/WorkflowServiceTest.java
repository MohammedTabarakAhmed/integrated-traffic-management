package gov.itms.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.workflow.domain.Actor;
import gov.itms.workflow.domain.Channel;
import gov.itms.workflow.domain.DomainException;
import gov.itms.workflow.domain.WorkItem;
import gov.itms.workflow.domain.WorkRecords;
import gov.itms.workflow.domain.WorkflowDefinition;
import gov.itms.workflow.domain.WorkflowDefinitionTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-061")
class WorkflowServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final Repo repo = new Repo();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final WorkflowService service = new WorkflowService(repo, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action + " " + entityId);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, new DomainEventRecorder() {
        @Override
        public void record(String eventType, String subject, Object data) {
            events.add(eventType + " " + subject);
        }

        @Override
        public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
            record(eventType, subject, data);
        }
    }, clock);

    /** A caller whose grants hold {@code permissions} through {@code role} in {@code regions} (all centres). */
    private static Caller caller(String id, String role, Set<String> regions, String... permissions) {
        Set<String> held = Set.of(permissions);
        Caller.RoleScope scope = new Caller.RoleScope(role, regions, Set.of("*"));
        return new Caller(new Actor(id, id), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsAt(String permission, String region, String centre) {
                return held.contains(permission) && (region == null || scope.reaches(region, centre));
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }

            @Override
            public List<Caller.RoleScope> roleScopes(String permission) {
                return held.contains(permission) ? List.of(scope) : List.of();
            }
        }, null);
    }

    private static final Caller ADMIN = caller("u-admin", "WORKFLOW_ADMIN", Set.of("*"),
            WorkflowService.DEFINITION_MANAGE);
    private static final Caller STARTER = caller("svc-vehicle", "WORKFLOW_STARTER", Set.of("*"),
            WorkflowService.ITEM_CREATE);
    private static final Caller SUPERVISOR = caller("u-super", "CASE_SUPERVISOR", Set.of("NORTH"),
            WorkflowService.ITEM_SUPERVISE);

    private static Caller officer(String id, String region) {
        return caller(id, "CASE_OFFICER", Set.of(region), WorkflowService.ITEM_WORK);
    }

    private WorkflowDefinition publish(String code, WorkflowDefinition.Assignment assignment) {
        WorkflowDefinition template = WorkflowDefinitionTest.twoSteps(1, assignment);
        return service.publish(code, template.serviceCode(), template.nameNl(), template.nameEn(),
                template.firstStep(), template.steps(), "first version", ADMIN);
    }

    private WorkItem start(String reference, String workflow, String region, String customerId) {
        return service.start(new WorkItem.Start(reference, "VL_RENEW", region, "C1", customerId, workflow), STARTER)
                .item();
    }

    private void member(String userId, String region, boolean available, Instant lastAssignedAt) {
        repo.upsertMember(new WorkRecords.QueueMember(userId, userId, "CASE_OFFICER", Set.of(region), Set.of("*"),
                available, lastAssignedAt, 0));
    }

    @Test
    @DisplayName("CC-061: round robin gives each new item to the available member assigned longest ago, in scope")
    void roundRobin() {
        publish("WF_RR", WorkflowDefinition.Assignment.ROUND_ROBIN);
        member("u-a", "NORTH", true, null);
        member("u-b", "NORTH", true, null);
        member("u-off", "NORTH", false, null);
        member("u-south", "SOUTH", true, null);

        assertThat(List.of(start("APP-1", "WF_RR", "NORTH", null), start("APP-2", "WF_RR", "NORTH", null),
                start("APP-3", "WF_RR", "NORTH", null))).extracting(WorkItem::assignee)
                .containsExactly("u-a", "u-b", "u-a");
        assertThat(start("APP-4", "WF_RR", "EAST", null).assignee()).as("nobody reaches EAST").isNull();
        assertThat(repo.history.stream().filter(h -> h.action().equals("ASSIGNED")).count()).isEqualTo(3);
        assertThat(events).contains("itms.workflow.item-created APP-1");
    }

    @Test
    @DisplayName("CC-061: least loaded gives the item to the member with the fewest open items")
    void leastLoaded() {
        WorkflowDefinition manual = publish("WF_MANUAL", WorkflowDefinition.Assignment.MANUAL);
        publish("WF_LL", WorkflowDefinition.Assignment.LEAST_LOADED);
        member("u-a", "NORTH", true, NOW.minus(Duration.ofHours(1)));
        member("u-b", "NORTH", true, NOW.minus(Duration.ofHours(2)));
        for (int i = 0; i < 2; i++) {
            WorkItem busy = WorkItem.open(UUID.randomUUID(), new WorkItem.Start("OLD-" + i, "VL_RENEW", "NORTH", null,
                    null, "WF_MANUAL"), manual, NOW);
            busy.assign("u-b");
            repo.insertItem(busy, "WF_MANUAL");
        }
        assertThat(start("APP-1", "WF_LL", "NORTH", null).assignee())
                .as("u-b was assigned longest ago but has two open items").isEqualTo("u-a");
    }

    @Test
    @Tag("CC-001")
    @Tag("CC-063")
    @DisplayName("CC-001, CC-061: claims, team lists and supervision stay within the caller's regions")
    void scopeChecks() {
        publish("WF_MANUAL", WorkflowDefinition.Assignment.MANUAL);
        WorkItem north = start("APP-N", "WF_MANUAL", "NORTH", null);
        start("APP-S", "WF_MANUAL", "SOUTH", null);

        assertThatThrownBy(() -> service.claim(north.id(), officer("u-south", "SOUTH")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        service.claim(north.id(), officer("u-a", "NORTH"));
        assertThatThrownBy(() -> service.claim(north.id(), officer("u-b", "NORTH")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("WORK_ITEM_ASSIGNED");

        assertThat(service.workList(WorkflowService.ListKind.TEAM, null, null, null, null, 50,
                officer("u-b", "NORTH"))).extracting(v -> v.item().start().reference()).containsExactly("APP-N");
        assertThat(service.workList(WorkflowService.ListKind.PERSONAL, null, null, null, null, 50,
                officer("u-a", "NORTH"))).extracting(v -> v.item().id()).containsExactly(north.id());
        assertThatThrownBy(() -> service.release(north.id(), null, officer("u-b", "NORTH")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThat(service.release(north.id(), "rebalancing work", SUPERVISOR).item().assignee()).isNull();
        assertThatThrownBy(() -> service.get(north.id(), officer("u-south", "SOUTH")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.workList(WorkflowService.ListKind.TEAM, null, null, null, null, 0,
                officer("u-b", "NORTH"))).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
    }

    @Test
    @Tag("CC-063")
    @DisplayName("CC-063: bulk assignment reports a result per item; the assignee must work in the queue")
    void bulkResults() {
        publish("WF_MANUAL", WorkflowDefinition.Assignment.MANUAL);
        member("u-a", "NORTH", true, null);
        WorkItem one = start("APP-1", "WF_MANUAL", "NORTH", null);
        WorkItem two = start("APP-2", "WF_MANUAL", "NORTH", null);
        WorkItem south = start("APP-3", "WF_MANUAL", "SOUTH", null);
        UUID missing = UUID.randomUUID();

        List<WorkflowService.BulkResult> results = service.bulkAssign(List.of(one.id(), two.id(), south.id(),
                missing), "u-a", null, SUPERVISOR);
        assertThat(results).extracting(WorkflowService.BulkResult::done).containsExactly(true, true, false, false);
        assertThat(results).extracting(WorkflowService.BulkResult::problem)
                .containsExactly(null, null, "SCOPE_EXCEEDED", "WORK_ITEM_NOT_FOUND");
        assertThat(repo.items.get(one.id()).assignee()).isEqualTo("u-a");

        assertThatThrownBy(() -> service.assign(one.id(), "u-nobody", null, SUPERVISOR))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ASSIGNEE_NOT_IN_QUEUE");
        assertThatThrownBy(() -> service.bulkAssign(List.of(one.id(), one.id()), "u-a", null, SUPERVISOR))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("BULK_INVALID");
        assertThatThrownBy(() -> service.bulkAssign(List.of(), "u-a", null, SUPERVISOR))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("BULK_INVALID");
    }

    @Test
    @Tag("CC-060")
    @DisplayName("CC-060: a decision routes to the next step, auto-assigns there and finally completes the item")
    void decideAndComplete() {
        publish("WF_MANUAL", WorkflowDefinition.Assignment.MANUAL);
        WorkItem item = start("APP-1", "WF_MANUAL", "NORTH", null);
        Caller a = officer("u-a", "NORTH");
        service.claim(item.id(), a);
        WorkItem moved = service.decide(item.id(), "PASSED", "documents fine", a).item();
        assertThat(moved.stepCode()).isEqualTo("APPROVE");
        assertThat(events).contains("itms.workflow.step-entered APP-1");

        Caller approver = caller("u-approver", "CASE_APPROVER", Set.of("NORTH"), WorkflowService.ITEM_WORK);
        service.claim(item.id(), approver);
        assertThat(service.decide(item.id(), "APPROVED", null, approver).item().status())
                .isEqualTo(WorkItem.Status.COMPLETED);
        assertThat(events).contains("itms.workflow.item-completed APP-1");
        start("APP-1", "WF_MANUAL", "NORTH", null); // a closed case does not block a new one
        assertThatThrownBy(() -> start("APP-1", "WF_MANUAL", "NORTH", null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("WORK_ITEM_EXISTS");
    }

    @Test
    @Tag("CC-064")
    @Tag("CC-065")
    @DisplayName("CC-064, CC-065: notes are staff-only; a return pauses the clock until the customer responds")
    void notesReturnAndResponse() {
        publish("WF_MANUAL", WorkflowDefinition.Assignment.MANUAL);
        WorkItem item = start("APP-1", "WF_MANUAL", "NORTH", "c-1");
        Caller a = officer("u-a", "NORTH");
        service.claim(item.id(), a);
        service.addNote(item.id(), "called the customer", List.of("doc-1"), a);
        assertThat(service.notes(item.id(), a)).extracting(WorkRecords.Note::text).containsExactly("called the customer");
        Caller customer = caller("c-1", "CUSTOMER", Set.of(), "customer.self");
        assertThatThrownBy(() -> service.notes(item.id(), customer)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");

        Instant due = repo.items.get(item.id()).dueAt();
        service.returnToCustomer(item.id(), "please upload the insurance policy", a);
        assertThat(events).contains("itms.workflow.item-returned APP-1");
        clock.advance(Duration.ofHours(5));
        assertThatThrownBy(() -> service.customerResponse(item.id(), "here it is", List.of(),
                caller("c-2", "CUSTOMER", Set.of(), "customer.self"))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");
        WorkItem resumed = service.customerResponse(item.id(), "here it is", List.of("doc-2"), customer).item();
        assertThat(resumed.status()).isEqualTo(WorkItem.Status.OPEN);
        assertThat(resumed.dueAt()).isEqualTo(due.plus(Duration.ofHours(5)));
        assertThat(repo.history).extracting(WorkRecords.HistoryEntry::action)
                .contains("CREATED", "CLAIMED", "NOTE_ADDED", "RETURNED", "CUSTOMER_RESPONDED");
    }

    @Test
    @Tag("CC-062")
    @DisplayName("CC-062: the job escalates each overdue item once and emits item-overdue")
    void escalation() {
        publish("WF_MANUAL", WorkflowDefinition.Assignment.MANUAL);
        start("APP-1", "WF_MANUAL", "NORTH", "c-1");
        assertThat(service.escalateOverdue(100)).isZero();
        clock.advance(Duration.ofDays(1).plusMinutes(1));
        assertThat(service.escalateOverdue(100)).isEqualTo(1);
        assertThat(service.escalateOverdue(100)).isZero();
        assertThat(events.stream().filter(e -> e.equals("itms.workflow.item-overdue APP-1")).count()).isEqualTo(1);
        assertThat(service.workList(WorkflowService.ListKind.ESCALATED, null, null, null, null, 50, SUPERVISOR))
                .extracting(v -> v.item().start().reference()).containsExactly("APP-1");
    }

    @Test
    @Tag("CC-060")
    @DisplayName("CC-060: a new definition version leaves running items on the version they started with")
    void versionsKeptByRunningItems() {
        publish("WF_MANUAL", WorkflowDefinition.Assignment.MANUAL);
        WorkItem item = start("APP-1", "WF_MANUAL", "NORTH", null);
        WorkflowDefinition v2 = publish("WF_MANUAL", WorkflowDefinition.Assignment.ROUND_ROBIN);
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(repo.items.get(item.id()).definitionVersion()).isEqualTo(1);
        assertThatThrownBy(() -> service.publish("WF_MANUAL", "OTHER_SVC", "x", "x", v2.firstStep(), v2.steps(),
                "move it", ADMIN)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("WORKFLOW_SERVICE_MISMATCH");
        assertThatThrownBy(() -> service.publish("WF_Y", "VL_RENEW", "x", "x", v2.firstStep(), v2.steps(), "r",
                ADMIN)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("REASON_INVALID");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** In-memory repository; items are kept as the same mutable objects the service changes. */
    private static final class Repo implements WorkflowRepository {
        final Map<String, List<WorkflowDefinition>> definitions = new HashMap<>();
        final Map<UUID, WorkItem> items = new LinkedHashMap<>();
        final Map<UUID, String> workflowOf = new HashMap<>();
        final List<WorkRecords.HistoryEntry> history = new ArrayList<>();
        final List<WorkRecords.Note> notes = new ArrayList<>();
        final Map<String, WorkRecords.QueueMember> members = new LinkedHashMap<>();

        @Override
        public void lockDefinition(String code) {
        }

        @Override
        public int nextDefinitionVersion(String code) {
            return definitions.getOrDefault(code, List.of()).size() + 1;
        }

        @Override
        public void insertDefinition(WorkflowDefinition definition) {
            definitions.computeIfAbsent(definition.code(), c -> new ArrayList<>()).add(definition);
        }

        @Override
        public Optional<WorkflowDefinition> findDefinition(String code, int versionNo) {
            return definitions.getOrDefault(code, List.of()).stream().filter(d -> d.versionNo() == versionNo)
                    .findFirst();
        }

        @Override
        public Optional<WorkflowDefinition> latestDefinition(String code) {
            List<WorkflowDefinition> all = definitions.getOrDefault(code, List.of());
            return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
        }

        @Override
        public List<WorkflowDefinition> latestDefinitions(String serviceCode) {
            return definitions.values().stream().map(List::getLast)
                    .filter(d -> serviceCode == null || d.serviceCode().equals(serviceCode)).toList();
        }

        @Override
        public void insertItem(WorkItem item, String workflowCode) {
            items.put(item.id(), item);
            workflowOf.put(item.id(), workflowCode);
        }

        @Override
        public Optional<WorkItem> findItem(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<WorkItem> findItemForUpdate(UUID id) {
            return findItem(id);
        }

        @Override
        public void updateItem(WorkItem item) {
            item.saved();
        }

        @Override
        public boolean activeItemExists(String reference, String workflowCode) {
            return items.values().stream().anyMatch(i -> i.start().reference().equals(reference)
                    && workflowCode.equals(workflowOf.get(i.id()))
                    && (i.status() == WorkItem.Status.OPEN || i.status() == WorkItem.Status.RETURNED));
        }

        @Override
        public String workflowCodeOf(UUID itemId) {
            return workflowOf.get(itemId);
        }

        private List<WorkItem> filtered(java.util.function.Predicate<WorkItem> where, ListFilter f) {
            return items.values().stream().filter(where)
                    .filter(i -> f.statuses() == null || f.statuses().contains(i.status()))
                    .filter(i -> f.serviceCode() == null || f.serviceCode().equals(i.start().serviceCode()))
                    .filter(i -> f.overdue() == null || f.overdue() == i.overdue(f.now()))
                    .filter(i -> f.escalated() == null || f.escalated() == (i.escalatedAt() != null))
                    .sorted(Comparator.comparing(WorkItem::dueAt)).limit(f.limit()).toList();
        }

        @Override
        public List<WorkItem> assignedTo(String userId, ListFilter filter) {
            return filtered(i -> userId.equals(i.assignee()), filter);
        }

        @Override
        public List<WorkItem> inQueues(Collection<String> roles, ListFilter filter) {
            return filtered(i -> roles.contains(i.queueRole()), filter);
        }

        @Override
        public List<WorkItem> escalatedTo(Collection<String> roles, ListFilter filter) {
            return filtered(i -> i.escalatedAt() != null && roles.contains(i.escalationRole()), filter);
        }

        @Override
        public List<WorkItem> overdueForEscalation(Instant now, int limit) {
            return items.values().stream().filter(i -> i.status() == WorkItem.Status.OPEN && i.escalatedAt() == null
                    && i.dueAt().isBefore(now)).limit(limit).toList();
        }

        @Override
        public void insertHistory(WorkRecords.HistoryEntry entry) {
            history.add(entry);
        }

        @Override
        public List<WorkRecords.HistoryEntry> history(UUID itemId) {
            return history.stream().filter(h -> h.itemId().equals(itemId)).toList();
        }

        @Override
        public void insertNote(WorkRecords.Note note) {
            notes.add(note);
        }

        @Override
        public List<WorkRecords.Note> notes(UUID itemId) {
            return notes.stream().filter(n -> n.itemId().equals(itemId)).toList();
        }

        @Override
        public void upsertMember(WorkRecords.QueueMember member) {
            members.put(member.userId() + "/" + member.role(), member);
        }

        @Override
        public List<WorkRecords.QueueMember> members(String role) {
            return members.values().stream().filter(m -> m.role().equals(role))
                    .map(m -> new WorkRecords.QueueMember(m.userId(), m.username(), m.role(), m.regions(),
                            m.centres(), m.available(), m.lastAssignedAt(), (int) items.values().stream()
                            .filter(i -> i.status() == WorkItem.Status.OPEN && m.userId().equals(i.assignee())
                                    && role.equals(i.queueRole())).count()))
                    .toList();
        }

        @Override
        public List<WorkRecords.QueueMember> membershipsOf(String userId) {
            return members.values().stream().filter(m -> m.userId().equals(userId)).toList();
        }

        @Override
        public void markAssigned(String userId, String role, Instant at) {
            WorkRecords.QueueMember m = members.get(userId + "/" + role);
            if (m != null) {
                upsertMember(new WorkRecords.QueueMember(m.userId(), m.username(), m.role(), m.regions(), m.centres(),
                        m.available(), at, m.openItems()));
            }
        }
    }
}
