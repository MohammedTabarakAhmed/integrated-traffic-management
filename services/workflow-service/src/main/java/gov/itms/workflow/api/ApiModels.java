package gov.itms.workflow.api;

import gov.itms.workflow.application.WorkflowService;
import gov.itms.workflow.domain.WorkItem;
import gov.itms.workflow.domain.WorkRecords;
import gov.itms.workflow.domain.WorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of {@code workflow-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record StepDto(@NotBlank String code, @NotNull WorkflowDefinition.StepKind kind, @NotBlank String nameNl,
                   @NotBlank String nameEn, @NotBlank String role, @NotNull WorkflowDefinition.Assignment assignment,
                   @NotNull Duration sla, @NotBlank String escalationRole,
                   @NotNull @Size(min = 1, max = 10) Map<String, String> outcomes) {

        WorkflowDefinition.Step toDomain() {
            return new WorkflowDefinition.Step(code, kind, nameNl, nameEn, role, assignment, sla, escalationRole,
                    outcomes);
        }

        static StepDto of(WorkflowDefinition.Step s) {
            return new StepDto(s.code(), s.kind(), s.nameNl(), s.nameEn(), s.role(), s.assignment(), s.sla(),
                    s.escalationRole(), s.outcomes());
        }
    }

    record PublishRequest(@NotBlank String code, @NotBlank String serviceCode, @NotBlank String nameNl,
                          @NotBlank String nameEn, @NotBlank String firstStep,
                          @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid StepDto> steps,
                          @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record DefinitionDto(String code, int versionNo, String serviceCode, String nameNl, String nameEn,
                         String firstStep, List<StepDto> steps, String publishedBy, Instant publishedAt,
                         String reason) {
        static DefinitionDto of(WorkflowDefinition d) {
            return new DefinitionDto(d.code(), d.versionNo(), d.serviceCode(), d.nameNl(), d.nameEn(), d.firstStep(),
                    d.steps().stream().map(StepDto::of).toList(), d.publishedBy().id(), d.publishedAt(), d.reason());
        }
    }

    record StartRequest(@NotBlank String workflowCode, @NotBlank String reference, @NotBlank String serviceCode,
                        @NotBlank String region, String centre, @Size(max = 100) String customerId) {
        WorkItem.Start toDomain() {
            return new WorkItem.Start(reference, serviceCode, region, centre, customerId, workflowCode);
        }
    }

    record WorkItemDto(UUID id, String reference, String serviceCode, String workflowCode, int workflowVersion,
                       String region, String centre, String customerId, String stepCode, String queueRole,
                       String escalationRole, WorkItem.Status status, String outcome, String assignee,
                       Instant createdAt, Instant stepStartedAt, Instant dueAt, Instant pausedAt, Instant escalatedAt,
                       Instant closedAt, boolean overdue) {
        static WorkItemDto of(WorkflowService.ItemView v) {
            WorkItem i = v.item();
            return new WorkItemDto(i.id(), i.start().reference(), i.start().serviceCode(), v.workflowCode(),
                    i.definitionVersion(), i.start().region(), i.start().centre(), i.start().customerId(),
                    i.stepCode(), i.queueRole(), i.escalationRole(), i.status(), i.outcome(), i.assignee(),
                    i.createdAt(), i.stepStartedAt(), i.dueAt(), i.pausedAt(), i.escalatedAt(), i.closedAt(),
                    v.overdue());
        }
    }

    record ReasonRequest(@Size(min = 3, max = 500) String reason) {
    }

    record RequiredReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record AssignRequest(@NotBlank @Size(max = 100) String assignee, @Size(min = 3, max = 500) String reason) {
    }

    record BulkAssignRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> itemIds,
                             @NotBlank @Size(max = 100) String assignee, @Size(min = 3, max = 500) String reason) {
    }

    record BulkResultDto(UUID itemId, boolean done, String problem) {
        static BulkResultDto of(WorkflowService.BulkResult r) {
            return new BulkResultDto(r.itemId(), r.done(), r.problem());
        }
    }

    record DecisionRequest(@NotBlank @Size(max = 40) String outcome, @Size(min = 3, max = 500) String reason) {
    }

    record ReturnRequest(@NotBlank @Size(min = 3, max = 500) String requestedInformation) {
    }

    record NoteRequest(@NotBlank @Size(max = 4000) String text, @Size(max = 20) List<@NotNull String> attachmentIds) {
    }

    record NoteDto(UUID id, String text, List<String> attachmentIds, String authorId, String authorUsername,
                   Instant at) {
        static NoteDto of(WorkRecords.Note n) {
            return new NoteDto(n.id(), n.text(), n.attachmentIds(), n.author().id(), n.author().username(), n.at());
        }
    }

    record HistoryDto(String action, String stepCode, String actorId, Instant at, Map<String, Object> detail) {
        static HistoryDto of(WorkRecords.HistoryEntry e) {
            return new HistoryDto(e.action(), e.stepCode(), e.actorId(), e.at(), e.detail());
        }
    }

    record AvailabilityRequest(@NotNull Boolean available) {
    }

    record MembershipDto(String role, List<String> regions, List<String> centres, boolean available,
                         Instant lastAssignedAt, int openItems) {
        static MembershipDto of(WorkRecords.QueueMember m) {
            return new MembershipDto(m.role(), m.regions().stream().sorted().toList(),
                    m.centres().stream().sorted().toList(), m.available(), m.lastAssignedAt(), m.openItems());
        }
    }

    record Collection<T>(List<T> items) {
    }
}
