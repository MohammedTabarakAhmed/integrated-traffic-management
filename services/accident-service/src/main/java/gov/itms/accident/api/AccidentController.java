package gov.itms.accident.api;

import gov.itms.accident.application.AccidentService;
import gov.itms.accident.application.AccidentWorkflowService;
import gov.itms.accident.domain.Accident;
import gov.itms.accident.domain.DomainException;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
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

/** Accident reports and party statements (AC-001..AC-006). */
@RestController
class AccidentController {
    private final AccidentService accidents;
    private final AccidentWorkflowService workflow;
    private final CurrentActorProvider actors;

    AccidentController(AccidentService accidents, AccidentWorkflowService workflow, CurrentActorProvider actors) {
        this.accidents = accidents;
        this.workflow = workflow;
        this.actors = actors;
    }

    record PartyRequest(String trafficFileNumber, String vehiclePlate, @NotNull Accident.Role role,
                        @Size(max = 40) String seatPosition, @Size(max = 40) String injuryDegree,
                        @Size(max = 80) String transportMethod, @Size(max = 40) String intoxicationStatus,
                        Integer faultPercent, boolean agrees, Boolean unknownParty) { }
    record ReportRequest(@NotBlank String region, @NotNull Instant occurredAt, @NotBlank @Size(max = 500) String location,
                         @Size(max = 80) String roadCondition, @Size(max = 80) String weather,
                         @NotNull Accident.Origin origin,
                         @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid PartyRequest> parties,
                         @Size(max = 100) List<@NotBlank String> evidenceDocumentIds) { }
    record PartyFault(@jakarta.validation.constraints.Min(1) int partyNo,
                      @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) int faultPercent) { }
    record FaultDecisionRequest(@NotBlank @Size(min = 3, max = 500) String reason,
                                @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid PartyFault> shares) { }
    record RepairPermitRequest(@NotBlank @Size(max = 40) String vehiclePlate,
                               @NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 120) String> damagedParts) { }
    record ObjectionRequest(@jakarta.validation.constraints.Min(1) int partyNo,
                            @NotBlank @Size(max = 1000) String reason) { }
    record ObjectionReviewRequest(boolean accept, @NotBlank @Size(max = 1000) String reason) { }
    record HitRunIdentityRequest(String trafficFileNumber, String vehiclePlate) { }
    record ReasonRequest(@Size(max = 1000) String reason) { }
    record IssuedRepairPermitResponse(UUID id, String number, String accidentReportNumber, String vehiclePlate,
                                      String region, List<String> damagedParts, Instant issuedAt, String status,
                                      String verificationCode) {
        static IssuedRepairPermitResponse of(AccidentService.IssuedRepairPermit issued) {
            var permit = issued.permit();
            return new IssuedRepairPermitResponse(permit.id(), permit.number(), permit.accidentReportNumber(),
                    permit.vehiclePlate(), permit.region(), permit.damagedParts(), permit.issuedAt(), permit.status(),
                    issued.verificationCode());
        }
    }
    record ReportResponse(UUID id, String reportNumber, String region, Instant occurredAt, String location,
                          String roadCondition, String weather, String status, String origin,
                          List<Accident.Party> parties, List<String> evidenceDocumentIds) {
        static ReportResponse of(Accident a) {
            return new ReportResponse(a.id(), a.reportNumber(), a.region(), a.occurredAt(), a.location(),
                    a.roadCondition(), a.weather(), a.status(), a.origin(), a.parties(), a.evidenceDocumentIds());
        }
    }

    @PostMapping("/v1/accidents")
    @PreAuthorize("hasAuthority('accident.report')")
    ResponseEntity<ReportResponse> report(@Valid @RequestBody ReportRequest body) {
        if (!actors.authorisation().allows("accident.report", new ScopeTarget(body.region(), null, null))) {
            throw new org.springframework.security.access.AccessDeniedException("accident report scope required");
        }
        var current = actors.current();
        AccidentService.Report report = new AccidentService.Report(body.region(), body.occurredAt(),
                body.location(), body.roadCondition(), body.weather(), body.origin(), body.parties().stream()
                .map(p -> new AccidentService.PartyInput(p.trafficFileNumber(), p.vehiclePlate(), p.role(),
                        p.seatPosition(), p.injuryDegree(), p.transportMethod(), p.intoxicationStatus(),
                        p.faultPercent(), p.agrees(), Boolean.TRUE.equals(p.unknownParty()))).toList(), body.evidenceDocumentIds() == null
                ? List.of() : body.evidenceDocumentIds());
        Accident result = accidents.report(report, current.userId());
        return ResponseEntity.created(URI.create("/v1/accidents/" + result.id())).body(ReportResponse.of(result));
    }

    @GetMapping("/v1/accidents/{id}")
    @PreAuthorize("hasAuthority('accident.read')")
    ReportResponse get(@PathVariable UUID id) {
        Accident accident = accidents.get(id);
        if (!actors.authorisation().allows("accident.read", new ScopeTarget(accident.region(), null, null))) {
            throw new org.springframework.security.access.AccessDeniedException("accident read scope required");
        }
        return ReportResponse.of(accident);
    }

    @PostMapping("/v1/accidents/{id}/fault-decisions")
    @PreAuthorize("hasAuthority('accident.investigate')")
    ReportResponse decideFault(@PathVariable UUID id, @Valid @RequestBody FaultDecisionRequest body) {
        Accident accident = accidents.get(id);
        if (!actors.authorisation().allows("accident.investigate", new ScopeTarget(accident.region(), null, null))) {
            throw new org.springframework.security.access.AccessDeniedException("accident investigation scope required");
        }
        java.util.Map<Integer, Integer> shares = new java.util.LinkedHashMap<>();
        for (PartyFault share : body.shares()) {
            if (shares.putIfAbsent(share.partyNo(), share.faultPercent()) != null) {
                throw new DomainException(DomainException.Kind.VALIDATION, "FAULT_SHARES_INVALID",
                        "partyNo must appear once", List.of("AC-005"));
            }
        }
        return ReportResponse.of(accidents.decideFault(id, shares, body.reason(), actors.current().userId()));
    }

    @PostMapping("/v1/accidents/{id}/repair-permits")
    @PreAuthorize("hasAuthority('accident.repair-permit.issue')")
    ResponseEntity<IssuedRepairPermitResponse> issueRepairPermit(@PathVariable UUID id,
                                                                  @Valid @RequestBody RepairPermitRequest body) {
        Accident accident = accidents.get(id);
        if (!actors.authorisation().allows("accident.repair-permit.issue",
                new ScopeTarget(accident.region(), null, null))) {
            throw new org.springframework.security.access.AccessDeniedException("accident region required");
        }
        var issued = accidents.issueRepairPermit(id, body.vehiclePlate(), body.damagedParts(),
                actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/repair-permits/" + issued.permit().number()))
                .body(IssuedRepairPermitResponse.of(issued));
    }

    @GetMapping("/v1/repair-permits/{number}")
    @PreAuthorize("hasAuthority('accident.repair-permit.verify')")
    gov.itms.accident.domain.RepairPermit verifyRepairPermit(@PathVariable String number,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80)
            @RequestParam String code) {
        var permit = accidents.verifyRepairPermit(number, code);
        if (!actors.authorisation().allows("accident.repair-permit.verify",
                new ScopeTarget(permit.region(), null, null))) {
            throw new org.springframework.security.access.AccessDeniedException("repair permit region required");
        }
        return permit;
    }

    @PostMapping("/v1/repair-permits/{number}/cancellations")
    @PreAuthorize("hasAuthority('accident.repair-permit.cancel')")
    gov.itms.accident.domain.RepairPermit cancelRepairPermit(@PathVariable String number,
                                                              @Valid @RequestBody ReasonRequest body) {
        var permit = accidents.repairPermit(number);
        requireScope("accident.repair-permit.cancel", permit.region());
        return accidents.cancelRepairPermit(number, body.reason(), actors.current().userId());
    }

    record PaperBookRequest(@jakarta.validation.constraints.NotBlank String serial,
                            @jakarta.validation.constraints.NotBlank String region,
                            @jakarta.validation.constraints.NotBlank String officerId) { }
    record PaperReportRequest(@jakarta.validation.constraints.NotBlank String reportSerial,
                              @jakarta.validation.constraints.NotNull UUID accidentId) { }
    record PaperReconciliationRequest(@jakarta.validation.constraints.NotNull List<@jakarta.validation.constraints.NotBlank String> reportSerials) { }
    @PostMapping("/v1/accident-paper-books") @PreAuthorize("hasAuthority('accident.paper-book.issue')")
    ResponseEntity<AccidentWorkflowService.PaperBook> issuePaperBook(@Valid @RequestBody PaperBookRequest b){
        requireScope("accident.paper-book.issue",b.region());
        var book=workflow.issuePaperBook(b.serial(),b.region(),b.officerId(),actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/accident-paper-books/"+book.serial())).body(book);
    }
    @PostMapping("/v1/accident-paper-books/{serial}/reports") @PreAuthorize("hasAuthority('accident.paper-book.record')")
    AccidentWorkflowService.BookEntry enterPaperReport(@PathVariable String serial,@Valid @RequestBody PaperReportRequest b){
        Accident accident=accidents.get(b.accidentId());
        requireScope("accident.paper-book.record",accident.region());
        return workflow.enterPaperReport(serial,b.reportSerial(),b.accidentId(),actors.current().userId());
    }
    @PostMapping("/v1/accident-paper-books/{serial}/reconciliation") @PreAuthorize("hasAuthority('accident.paper-book.reconcile')")
    AccidentWorkflowService.PaperBook reconcilePaperBook(@PathVariable String serial,@Valid @RequestBody PaperReconciliationRequest b){
        requireScope("accident.paper-book.reconcile",workflow.paperBook(serial).region());
        return workflow.reconcilePaperBook(serial,b.reportSerials(),actors.current().userId());
    }
    @PostMapping("/v1/accident-paper-books/{serial}/losses") @PreAuthorize("hasAuthority('accident.paper-book.issue')")
    AccidentWorkflowService.PaperBook losePaperBook(@PathVariable String serial,@Valid @RequestBody ReasonRequest b){
        requireScope("accident.paper-book.issue",workflow.paperBook(serial).region());
        return workflow.losePaperBook(serial,b.reason(),actors.current().userId());
    }

    @PostMapping("/v1/accidents/{id}/objections")
    @PreAuthorize("hasAuthority('accident.objection.submit')")
    ResponseEntity<AccidentWorkflowService.Objection> object(@PathVariable UUID id,
            @Valid @RequestBody ObjectionRequest body) {
        Accident accident = accidents.get(id);
        requireScope("accident.objection.submit", accident.region());
        var objection = workflow.object(id, body.partyNo(), body.reason(), actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/accident-objections/" + objection.id())).body(objection);
    }

    @PostMapping("/v1/accident-objections/{id}/reviews")
    @PreAuthorize("hasAuthority('accident.objection.review')")
    AccidentWorkflowService.Objection reviewObjection(@PathVariable UUID id,
            @Valid @RequestBody ObjectionReviewRequest body) {
        AccidentWorkflowService.Objection objection = workflow.objection(id);
        Accident accident = accidents.get(objection.accidentId());
        requireScope("accident.objection.review", accident.region());
        return workflow.review(id, body.accept(), body.reason(), actors.current().userId());
    }

    @PostMapping("/v1/accidents/{id}/hit-and-run-parties/{partyNo}/identifications")
    @PreAuthorize("hasAuthority('accident.investigate')")
    ReportResponse identifyHitRun(@PathVariable UUID id, @PathVariable int partyNo,
            @Valid @RequestBody HitRunIdentityRequest body) {
        Accident accident = accidents.get(id);
        requireScope("accident.investigate", accident.region());
        return ReportResponse.of(workflow.identifyHitRun(id, partyNo, body.trafficFileNumber(), body.vehiclePlate(),
                actors.current().userId()));
    }

    @PostMapping("/v1/accidents/{id}/closures")
    @PreAuthorize("hasAuthority('accident.investigate')")
    ReportResponse close(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        Accident accident = accidents.get(id);
        requireScope("accident.investigate", accident.region());
        return ReportResponse.of(workflow.close(id, body.reason(), actors.current().userId()));
    }

    @PostMapping("/v1/accidents/{id}/prosecution-referrals")
    @PreAuthorize("hasAuthority('accident.prosecution.refer')")
    ReportResponse refer(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        Accident accident = accidents.get(id);
        requireScope("accident.prosecution.refer", accident.region());
        if (body.reason() == null || body.reason().isBlank()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "PROSECUTION_REFERRAL_INVALID",
                    "referral reason is required", List.of("AC-011"));
        }
        return ReportResponse.of(workflow.referToProsecution(id, body.reason(), actors.current().userId()));
    }

    @PostMapping("/v1/accidents/{id}/ambulance-requests")
    @PreAuthorize("hasAuthority('accident.external.send')")
    ResponseEntity<Void> requestAmbulance(@PathVariable UUID id) {
        Accident accident = accidents.get(id);
        requireScope("accident.external.send", accident.region());
        workflow.requestAmbulance(id, actors.current().userId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/v1/accidents/{id}/insurer-notifications")
    @PreAuthorize("hasAuthority('accident.external.send')")
    ResponseEntity<Void> notifyInsurers(@PathVariable UUID id) {
        Accident accident = accidents.get(id);
        requireScope("accident.external.send", accident.region());
        workflow.sendToInsurers(id, actors.current().userId());
        return ResponseEntity.accepted().build();
    }

    private void requireScope(String permission, String region) {
        if (!actors.authorisation().allows(permission, new ScopeTarget(region, null, null))) {
            throw new org.springframework.security.access.AccessDeniedException("accident region scope required");
        }
    }
}
