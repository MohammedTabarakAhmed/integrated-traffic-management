package gov.itms.workflow.api;

import gov.itms.workflow.api.ApiModels.AssignRequest;
import gov.itms.workflow.api.ApiModels.AvailabilityRequest;
import gov.itms.workflow.api.ApiModels.BulkAssignRequest;
import gov.itms.workflow.api.ApiModels.BulkResultDto;
import gov.itms.workflow.api.ApiModels.Collection;
import gov.itms.workflow.api.ApiModels.DecisionRequest;
import gov.itms.workflow.api.ApiModels.DefinitionDto;
import gov.itms.workflow.api.ApiModels.HistoryDto;
import gov.itms.workflow.api.ApiModels.MembershipDto;
import gov.itms.workflow.api.ApiModels.NoteDto;
import gov.itms.workflow.api.ApiModels.NoteRequest;
import gov.itms.workflow.api.ApiModels.PublishRequest;
import gov.itms.workflow.api.ApiModels.ReasonRequest;
import gov.itms.workflow.api.ApiModels.RequiredReasonRequest;
import gov.itms.workflow.api.ApiModels.ReturnRequest;
import gov.itms.workflow.api.ApiModels.StartRequest;
import gov.itms.workflow.api.ApiModels.WorkItemDto;
import gov.itms.workflow.application.WorkflowService;
import gov.itms.workflow.domain.WorkItem;
import gov.itms.workflow.domain.WorkRecords;
import gov.itms.workflow.domain.WorkflowDefinition;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Workflow definitions, work items, work lists and queues of {@code workflow-service.v1.yaml} (CC-060..CC-065). */
@RestController
class WorkflowController {

    private final WorkflowService workflow;
    private final Callers callers;

    WorkflowController(WorkflowService workflow, Callers callers) {
        this.workflow = workflow;
        this.callers = callers;
    }

    // ---- definitions

    @PostMapping("/v1/workflow-definitions")
    @PreAuthorize("hasAuthority('workflow.definition.manage')")
    ResponseEntity<DefinitionDto> publish(@Valid @RequestBody PublishRequest body) {
        WorkflowDefinition d = workflow.publish(body.code(), body.serviceCode(), body.nameNl(), body.nameEn(),
                body.firstStep(), body.steps().stream().map(ApiModels.StepDto::toDomain).toList(), body.reason(),
                callers.current());
        return ResponseEntity.created(URI.create("/v1/workflow-definitions/" + d.code() + "?version=" + d.versionNo()))
                .body(DefinitionDto.of(d));
    }

    @GetMapping("/v1/workflow-definitions")
    @PreAuthorize("hasAuthority('workflow.definition.read') or hasAuthority('workflow.definition.manage')")
    Collection<DefinitionDto> definitions(@RequestParam(required = false) String serviceCode) {
        return new Collection<>(workflow.definitions(serviceCode, callers.current()).stream().map(DefinitionDto::of)
                .toList());
    }

    @GetMapping("/v1/workflow-definitions/{code}")
    @PreAuthorize("hasAuthority('workflow.definition.read') or hasAuthority('workflow.definition.manage')")
    DefinitionDto definition(@PathVariable String code, @RequestParam(required = false) Integer version) {
        return DefinitionDto.of(workflow.definition(code, version, callers.current()));
    }

    // ---- items

    @PostMapping("/v1/work-items")
    @PreAuthorize("hasAuthority('workflow.item.create')")
    ResponseEntity<WorkItemDto> start(@Valid @RequestBody StartRequest body) {
        WorkflowService.ItemView v = workflow.start(body.toDomain(), callers.current());
        return ResponseEntity.created(URI.create("/v1/work-items/" + v.item().id())).body(WorkItemDto.of(v));
    }

    @GetMapping("/v1/work-items")
    @PreAuthorize("hasAuthority('workflow.item.work') or hasAuthority('workflow.item.supervise')")
    Collection<WorkItemDto> workList(@RequestParam WorkflowService.ListKind list,
                                     @RequestParam(required = false) List<WorkItem.Status> status,
                                     @RequestParam(required = false) String serviceCode,
                                     @RequestParam(required = false) Boolean overdue,
                                     @RequestParam(required = false) Boolean escalated,
                                     @RequestParam(defaultValue = "50") int limit) {
        return new Collection<>(workflow.workList(list, status, serviceCode, overdue, escalated, limit,
                callers.current()).stream().map(WorkItemDto::of).toList());
    }

    @GetMapping("/v1/work-items/{id}")
    @PreAuthorize("isAuthenticated()")
    WorkItemDto get(@PathVariable UUID id) {
        return WorkItemDto.of(workflow.get(id, callers.current()));
    }

    @GetMapping("/v1/work-items/{id}/history")
    @PreAuthorize("isAuthenticated()")
    Collection<HistoryDto> history(@PathVariable UUID id) {
        return new Collection<>(workflow.history(id, callers.current()).stream().map(HistoryDto::of).toList());
    }

    @PostMapping("/v1/work-items/{id}/claims")
    @PreAuthorize("hasAuthority('workflow.item.work')")
    WorkItemDto claim(@PathVariable UUID id) {
        return WorkItemDto.of(workflow.claim(id, callers.current()));
    }

    @PostMapping("/v1/work-items/{id}/releases")
    @PreAuthorize("hasAuthority('workflow.item.work') or hasAuthority('workflow.item.supervise')")
    WorkItemDto release(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return WorkItemDto.of(workflow.release(id, body.reason(), callers.current()));
    }

    @PostMapping("/v1/work-items/{id}/assignments")
    @PreAuthorize("hasAuthority('workflow.item.supervise')")
    WorkItemDto assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest body) {
        return WorkItemDto.of(workflow.assign(id, body.assignee(), body.reason(), callers.current()));
    }

    @PostMapping("/v1/work-items/bulk-assignments")
    @PreAuthorize("hasAuthority('workflow.item.supervise')")
    Collection<BulkResultDto> bulkAssign(@Valid @RequestBody BulkAssignRequest body) {
        return new Collection<>(workflow.bulkAssign(body.itemIds(), body.assignee(), body.reason(), callers.current())
                .stream().map(BulkResultDto::of).toList());
    }

    @PostMapping("/v1/work-items/{id}/decisions")
    @PreAuthorize("hasAuthority('workflow.item.work')")
    WorkItemDto decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return WorkItemDto.of(workflow.decide(id, body.outcome(), body.reason(), callers.current()));
    }

    @PostMapping("/v1/work-items/{id}/returns")
    @PreAuthorize("hasAuthority('workflow.item.work')")
    WorkItemDto returnToCustomer(@PathVariable UUID id, @Valid @RequestBody ReturnRequest body) {
        return WorkItemDto.of(workflow.returnToCustomer(id, body.requestedInformation(), callers.current()));
    }

    @PostMapping("/v1/work-items/{id}/customer-responses")
    @PreAuthorize("isAuthenticated()")
    WorkItemDto customerResponse(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        return WorkItemDto.of(workflow.customerResponse(id, body.text(), body.attachmentIds(), callers.current()));
    }

    @PostMapping("/v1/work-items/{id}/cancellations")
    @PreAuthorize("hasAuthority('workflow.item.create')")
    WorkItemDto cancel(@PathVariable UUID id, @Valid @RequestBody RequiredReasonRequest body) {
        return WorkItemDto.of(workflow.cancel(id, body.reason(), callers.current()));
    }

    @PostMapping("/v1/work-items/{id}/notes")
    @PreAuthorize("hasAuthority('workflow.item.work') or hasAuthority('workflow.item.supervise')")
    ResponseEntity<NoteDto> addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        WorkRecords.Note n = workflow.addNote(id, body.text(), body.attachmentIds(), callers.current());
        return ResponseEntity.created(URI.create("/v1/work-items/" + id + "/notes")).body(NoteDto.of(n));
    }

    @GetMapping("/v1/work-items/{id}/notes")
    @PreAuthorize("hasAuthority('workflow.item.work') or hasAuthority('workflow.item.supervise')")
    Collection<NoteDto> notes(@PathVariable UUID id) {
        return new Collection<>(workflow.notes(id, callers.current()).stream().map(NoteDto::of).toList());
    }

    // ---- queues

    @PostMapping("/v1/queue-memberships/me")
    @PreAuthorize("hasAuthority('workflow.item.work')")
    Collection<MembershipDto> setAvailability(@Valid @RequestBody AvailabilityRequest body) {
        return new Collection<>(workflow.setAvailability(body.available(), callers.current()).stream()
                .map(MembershipDto::of).toList());
    }

    @GetMapping("/v1/queue-memberships/me")
    @PreAuthorize("hasAuthority('workflow.item.work')")
    Collection<MembershipDto> memberships() {
        return new Collection<>(workflow.memberships(callers.current()).stream().map(MembershipDto::of).toList());
    }
}
