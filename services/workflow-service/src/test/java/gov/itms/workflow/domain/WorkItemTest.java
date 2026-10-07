package gov.itms.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-060")
class WorkItemTest {

    private static final Instant NOW = WorkflowDefinitionTest.NOW;
    private static final WorkflowDefinition DEFINITION = WorkflowDefinitionTest.twoSteps(1,
            WorkflowDefinition.Assignment.MANUAL);

    private static WorkItem open(String customerId) {
        return WorkItem.open(UUID.randomUUID(), new WorkItem.Start("APP-1", "VL_RENEW", "NORTH", "C1", customerId,
                "VL_REVIEW"), DEFINITION, NOW);
    }

    @Test
    @Tag("CC-061")
    @Tag("CC-062")
    @DisplayName("CC-060, CC-061, CC-062: a new item sits unassigned in the first step's queue with its SLA due time")
    void opensInFirstStep() {
        WorkItem item = open("c-1");
        assertThat(item.status()).isEqualTo(WorkItem.Status.OPEN);
        assertThat(item.stepCode()).isEqualTo("REVIEW");
        assertThat(item.queueRole()).isEqualTo("CASE_OFFICER");
        assertThat(item.escalationRole()).isEqualTo("CASE_SUPERVISOR");
        assertThat(item.assignee()).isNull();
        assertThat(item.dueAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        assertThat(item.definitionVersion()).isEqualTo(1);
        assertThatThrownBy(() -> WorkItem.open(UUID.randomUUID(), new WorkItem.Start("APP-1", "OTHER", "NORTH", null,
                null, "VL_REVIEW"), DEFINITION, NOW)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("WORKFLOW_SERVICE_MISMATCH");
        assertThatThrownBy(() -> new WorkItem.Start("bad ref", "VL_RENEW", "NORTH", null, null, "VL_REVIEW"))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }

    @Test
    @DisplayName("CC-060: only the assignee decides; an outcome routes to the next step's queue or ends the item")
    void decideRouteEnd() {
        WorkItem item = open("c-1");
        assertThatThrownBy(() -> item.decide("u-1", "PASSED", DEFINITION, NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("NOT_ASSIGNEE");
        item.assign("u-1");
        assertThatThrownBy(() -> item.decide("u-1", "MAYBE", DEFINITION, NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("OUTCOME_INVALID");

        Instant later = NOW.plus(Duration.ofHours(2));
        WorkflowDefinition.Step entered = item.decide("u-1", "PASSED", DEFINITION, later);
        assertThat(entered.code()).isEqualTo("APPROVE");
        assertThat(item.queueRole()).isEqualTo("CASE_APPROVER");
        assertThat(item.assignee()).as("a new step starts unassigned").isNull();
        assertThat(item.dueAt()).isEqualTo(later.plus(Duration.ofHours(4)));

        item.assign("u-2");
        assertThat(item.decide("u-2", "APPROVED", DEFINITION, later)).isNull();
        assertThat(item.status()).isEqualTo(WorkItem.Status.COMPLETED);
        assertThat(item.outcome()).isEqualTo("APPROVED");
        assertThat(item.closedAt()).isEqualTo(later);
    }

    @Test
    @Tag("CC-065")
    @DisplayName("CC-065: a return pauses the clock; the response resumes it and moves the due time by the pause")
    void returnAndResume() {
        WorkItem item = open("c-1");
        item.assign("u-1");
        Instant due = item.dueAt();
        item.returnToCustomer("u-1", NOW.plus(Duration.ofHours(1)));
        assertThat(item.status()).isEqualTo(WorkItem.Status.RETURNED);
        assertThat(item.overdue(NOW.plus(Duration.ofDays(5)))).as("a paused item is never overdue").isFalse();
        assertThat(item.escalateIfOverdue(NOW.plus(Duration.ofDays(5)))).isFalse();
        assertThatThrownBy(() -> item.decide("u-1", "PASSED", DEFINITION, NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("WORK_ITEM_RETURNED");

        Duration paused = item.resume(NOW.plus(Duration.ofHours(4)));
        assertThat(paused).isEqualTo(Duration.ofHours(3));
        assertThat(item.dueAt()).isEqualTo(due.plus(Duration.ofHours(3)));
        assertThat(item.status()).isEqualTo(WorkItem.Status.OPEN);
        assertThat(item.pausedAt()).isNull();
        assertThatThrownBy(() -> item.resume(NOW)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("WORK_ITEM_NOT_RETURNED");

        WorkItem anonymous = open(null);
        anonymous.assign("u-1");
        assertThatThrownBy(() -> anonymous.returnToCustomer("u-1", NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("NO_CUSTOMER");
    }

    @Test
    @Tag("CC-062")
    @DisplayName("CC-062: an overdue item is escalated once, never before its due time")
    void escalationOnce() {
        WorkItem item = open("c-1");
        assertThat(item.escalateIfOverdue(item.dueAt())).as("due exactly now is not overdue").isFalse();
        Instant late = item.dueAt().plusSeconds(1);
        assertThat(item.overdue(late)).isTrue();
        assertThat(item.escalateIfOverdue(late)).isTrue();
        assertThat(item.escalatedAt()).isEqualTo(late);
        assertThat(item.escalateIfOverdue(late.plusSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("CC-060: completed and cancelled items never change again")
    void closedItems() {
        WorkItem item = open("c-1");
        item.cancel(NOW);
        assertThat(item.status()).isEqualTo(WorkItem.Status.CANCELLED);
        assertThatThrownBy(() -> item.assign("u-1")).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("WORK_ITEM_CLOSED");
        assertThatThrownBy(() -> item.cancel(NOW)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("WORK_ITEM_CLOSED");
        assertThat(item.escalateIfOverdue(NOW.plus(Duration.ofDays(9)))).isFalse();

        WorkItem done = open("c-1");
        done.assign("u-1");
        done.decide("u-1", "REJECTED", DEFINITION, NOW);
        assertThat(done.status()).isEqualTo(WorkItem.Status.COMPLETED);
        assertThatThrownBy(() -> done.release()).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("WORK_ITEM_CLOSED");

        WorkItem unassigned = open("c-1");
        assertThatThrownBy(unassigned::release).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("WORK_ITEM_NOT_ASSIGNED");
    }
}
