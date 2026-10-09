package gov.itms.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-060")
public class WorkflowDefinitionTest {

    static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    static final Actor ADMIN = new Actor("u-admin", "admin");

    static WorkflowDefinition.Step step(String code, String role, WorkflowDefinition.Assignment assignment,
                                        Duration sla, Map<String, String> outcomes) {
        return new WorkflowDefinition.Step(code, WorkflowDefinition.StepKind.APPROVAL, "خطوة", "Step " + code, role,
                assignment, sla, "CASE_SUPERVISOR", outcomes);
    }

    /** REVIEW (officer, 1 day) → APPROVE (supervisor, 4 hours) → END; REVIEW can also reject straight to END. */
    public static WorkflowDefinition twoSteps(int versionNo, WorkflowDefinition.Assignment assignment) {
        return new WorkflowDefinition("VL_REVIEW", versionNo, "VL_RENEW", "مراجعة", "Review", "REVIEW",
                List.of(step("REVIEW", "CASE_OFFICER", assignment, Duration.ofDays(1),
                                Map.of("PASSED", "APPROVE", "REJECTED", WorkflowDefinition.END)),
                        step("APPROVE", "CASE_APPROVER", WorkflowDefinition.Assignment.MANUAL, Duration.ofHours(4),
                                Map.of("APPROVED", WorkflowDefinition.END, "SENT_BACK", "REVIEW"))),
                ADMIN, NOW, "first version");
    }

    @Test
    @DisplayName("CC-060: a consistent definition exposes its steps")
    void consistent() {
        WorkflowDefinition d = twoSteps(1, WorkflowDefinition.Assignment.MANUAL);
        assertThat(d.step("APPROVE").role()).isEqualTo("CASE_APPROVER");
        assertThat(d.step("REVIEW").outcomes()).containsEntry("PASSED", "APPROVE");
    }

    @Test
    @DisplayName("CC-060: duplicate steps, an unknown first step, unknown targets and no end are refused")
    void inconsistent() {
        Map<String, String> toEnd = Map.of("DONE", WorkflowDefinition.END);
        WorkflowDefinition.Step a = step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL,
                Duration.ofHours(1), toEnd);
        assertThatThrownBy(() -> new WorkflowDefinition("WF", 1, "SVC", "x", "x", "STEP_A", List.of(a, a), ADMIN, NOW,
                "r")).isInstanceOf(DomainException.class).extracting("code").isEqualTo("WORKFLOW_INVALID");
        assertThatThrownBy(() -> new WorkflowDefinition("WF", 1, "SVC", "x", "x", "STEP_B", List.of(a), ADMIN, NOW, "r"))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("WORKFLOW_INVALID");
        WorkflowDefinition.Step dangling = step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL,
                Duration.ofHours(1), Map.of("DONE", "NOWHERE"));
        assertThatThrownBy(() -> new WorkflowDefinition("WF", 1, "SVC", "x", "x", "STEP_A", List.of(dangling), ADMIN,
                NOW, "r")).isInstanceOf(DomainException.class).extracting("code").isEqualTo("WORKFLOW_INVALID");
        WorkflowDefinition.Step loop = step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL,
                Duration.ofHours(1), Map.of("AGAIN", "STEP_A"));
        assertThatThrownBy(() -> new WorkflowDefinition("WF", 1, "SVC", "x", "x", "STEP_A", List.of(loop), ADMIN, NOW,
                "r")).isInstanceOf(DomainException.class).extracting("code").isEqualTo("WORKFLOW_INVALID");
        assertThatThrownBy(() -> step("END", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL,
                Duration.ofHours(1), toEnd)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> step("STEP_A", "officer", WorkflowDefinition.Assignment.MANUAL, Duration.ofHours(1),
                toEnd)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL, Duration.ofHours(1),
                Map.of())).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }

    @Test
    @Tag("CC-062")
    @DisplayName("CC-062: a step SLA is 15 minutes to 365 days")
    void slaRange() {
        Map<String, String> toEnd = Map.of("DONE", WorkflowDefinition.END);
        assertThat(step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL, Duration.ofMinutes(15), toEnd)
                .sla()).isEqualTo(Duration.ofMinutes(15));
        assertThat(step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL, Duration.ofDays(365), toEnd)
                .sla()).isEqualTo(Duration.ofDays(365));
        assertThatThrownBy(() -> step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL,
                Duration.ofMinutes(14), toEnd)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SLA_INVALID");
        assertThatThrownBy(() -> step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL,
                Duration.ofDays(366), toEnd)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SLA_INVALID");
        assertThatThrownBy(() -> step("STEP_A", "CASE_OFFICER", WorkflowDefinition.Assignment.MANUAL, null, toEnd))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SLA_INVALID");
    }
}
