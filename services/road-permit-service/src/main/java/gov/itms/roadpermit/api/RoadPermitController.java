package gov.itms.roadpermit.api;

import gov.itms.roadpermit.application.RoadPermitService;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RoadPermitController {
    private final RoadPermitService permits;private final CurrentActorProvider actors;
    RoadPermitController(RoadPermitService permits,CurrentActorProvider actors){this.permits=permits;this.actors=actors;}
    record ApplicationRequest(@NotBlank String type,@NotBlank String applicant,String eligibilityReference,
                              @NotNull @Size(min=1,max=20) List<@NotBlank String> vehicles,
                              @Size(max=100) List<@NotBlank String> routeSegments,@NotBlank String region,
                              @NotNull Instant startsAt,@NotNull Instant endsAt,Double loadTonnes,Double widthMetres,
                              Double heightMetres,Double lengthMetres,@Min(1) int vehicleLimit,
                              @NotNull @Size(min=1,max=20) List<@NotBlank String> requiredAuthorities) { }
    record ApprovalRequest(@NotBlank String authority,@Size(max=500) String reason) { }
    record TransitionRequest(@NotBlank String action,@Size(max=500) String reason) { }
    @PostMapping("/v1/road-permits") @PreAuthorize("hasAuthority('road-permit.apply')")
    ResponseEntity<RoadPermitService.Application> apply(@Valid @RequestBody ApplicationRequest b){
        var a=permits.apply(b.type(),b.applicant(),b.eligibilityReference(),b.vehicles(),b.routeSegments(),b.region(),b.startsAt(),b.endsAt(),b.loadTonnes(),b.widthMetres(),b.heightMetres(),b.lengthMetres(),b.vehicleLimit(),b.requiredAuthorities(),actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/road-permits/"+a.id())).body(a);
    }
    @PostMapping("/v1/road-permits/{id}/approvals") @PreAuthorize("hasAuthority('road-permit.approve')")
    RoadPermitService.Application approve(@PathVariable UUID id,@Valid @RequestBody ApprovalRequest b){return permits.approve(id,b.authority(),b.reason(),actors.current().userId());}
    @PostMapping("/v1/road-permits/{id}/transitions") @PreAuthorize("hasAuthority('road-permit.manage')")
    RoadPermitService.Application transition(@PathVariable UUID id,@Valid @RequestBody TransitionRequest b){return permits.transition(id,b.action(),b.reason(),actors.current().userId());}
}
