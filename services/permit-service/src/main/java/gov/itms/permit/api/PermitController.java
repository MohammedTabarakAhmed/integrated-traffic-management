package gov.itms.permit.api;

import gov.itms.permit.application.PermitService;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PermitController {
    private final PermitService permits;
    private final CurrentActorProvider actors;
    PermitController(PermitService permits, CurrentActorProvider actors) { this.permits = permits; this.actors = actors; }

    record TypeRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 40) String category,
                       @Size(max = 40) String licenceClass, @Min(0) int minimumDrivingYears,
                       @Min(16) int minimumAge, @Min(0) int cleanRecordDays, boolean medicalFitRequired,
                       boolean testRequired, @Positive int validityDays, @Min(0) int renewalWindowDays,
                       @Min(0) long feeMinor) { }
    record ApplicationRequest(@NotBlank String typeCode, @NotBlank String trafficFileNumber, String schoolId,
                              String vehiclePlate, boolean licenceEligible, @Min(0) int drivingYears,
                              @Min(0) int age, @Min(0) int cleanRecordDays, boolean medicallyFit,
                              boolean dualControls, @Size(max = 500) String exceptionReason) { }
    record TestBookingRequest(@NotBlank @Size(max = 80) String committee, @NotNull Instant scheduledAt) { }
    record TestResultRequest(@NotBlank @Size(max = 80) String examiner, boolean passed, @Size(max = 500) String reason) { }
    record TransitionRequest(@NotBlank String action, @Size(max = 500) String reason) { }

    @PostMapping("/v1/permit-types")
    @PreAuthorize("hasAuthority('permit.type.manage')")
    PermitService.PermitType configure(@Valid @RequestBody TypeRequest body) {
        return permits.configureType(body.code(), body.category(), body.licenceClass(), body.minimumDrivingYears(),
                body.minimumAge(), body.cleanRecordDays(), body.medicalFitRequired(), body.testRequired(),
                body.validityDays(), body.renewalWindowDays(), body.feeMinor(), actors.current().userId());
    }

    @PostMapping("/v1/permits")
    @PreAuthorize("hasAuthority('permit.application.submit')")
    ResponseEntity<PermitService.Permit> apply(@Valid @RequestBody ApplicationRequest body) {
        PermitService.Permit permit = permits.apply(body.typeCode(), body.trafficFileNumber(), body.schoolId(),
                body.vehiclePlate(), body.licenceEligible(), body.drivingYears(), body.age(), body.cleanRecordDays(),
                body.medicallyFit(), body.dualControls(), body.exceptionReason(), actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/permits/" + permit.id())).body(permit);
    }

    @PostMapping("/v1/permits/{id}/tests")
    @PreAuthorize("hasAuthority('permit.test.book')")
    ResponseEntity<PermitService.PermitTest> book(@PathVariable UUID id, @Valid @RequestBody TestBookingRequest body) {
        PermitService.PermitTest test = permits.bookTest(id, body.committee(), body.scheduledAt(), actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/permits/" + id + "/tests/" + test.id())).body(test);
    }

    @PostMapping("/v1/permits/{id}/test-results")
    @PreAuthorize("hasAuthority('permit.test.record')")
    PermitService.Permit recordTest(@PathVariable UUID id, @Valid @RequestBody TestResultRequest body) {
        return permits.recordTest(id, body.examiner(), body.passed(), body.reason(), actors.current().userId());
    }

    @PostMapping("/v1/permits/{id}/transitions")
    @PreAuthorize("hasAuthority('permit.lifecycle.manage')")
    PermitService.Permit transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest body) {
        return permits.transition(id, body.action(), body.reason(), actors.current().userId());
    }
}
