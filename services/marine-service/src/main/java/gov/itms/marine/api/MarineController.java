package gov.itms.marine.api;

import gov.itms.marine.application.MarineService;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MarineController {
    private final MarineService marine;private final CurrentActorProvider actors;
    MarineController(MarineService marine,CurrentActorProvider actors){this.marine=marine;this.actors=actors;}
    record CraftRequest(@NotBlank @Size(max=80) String hullId,@NotBlank @Size(max=40) String type,@Positive double lengthMetres,
                        @Min(1) int engines,@Min(1) int capacity,@NotBlank String homePort,@NotBlank String owner) { }
    record InspectionRequest(@NotBlank String purpose,@NotBlank String result,@NotBlank String workshop,String documentId) { }
    record LicenceRequest(@NotBlank String trafficFileNumber,@NotBlank String craftType,@Min(1) int validityDays) { }
    record TransferRequest(@NotBlank String owner) { }
    record RenewalRequest(@Min(1) int validityDays) { }
    @PostMapping("/v1/marine-craft") @PreAuthorize("hasAuthority('marine.craft.manage')")
    ResponseEntity<MarineService.Craft> register(@Valid @RequestBody CraftRequest b){var c=marine.register(b.hullId(),b.type(),b.lengthMetres(),b.engines(),b.capacity(),b.homePort(),b.owner(),actors.current().userId());return ResponseEntity.created(URI.create("/v1/marine-craft/"+c.hullId())).body(c);}
    @PostMapping("/v1/marine-craft/{hullId}/inspections") @PreAuthorize("hasAuthority('marine.inspection.record')")
    MarineService.Inspection inspect(@PathVariable String hullId,@Valid @RequestBody InspectionRequest b){return marine.inspect(hullId,b.purpose(),b.result(),b.workshop(),b.documentId(),actors.current().userId());}
    @PostMapping("/v1/marine-craft/{hullId}/renewals") @PreAuthorize("hasAuthority('marine.craft.manage')")
    MarineService.Craft renew(@PathVariable String hullId,@Valid @RequestBody RenewalRequest b){return marine.renew(hullId,b.validityDays(),actors.current().userId());}
    @PostMapping("/v1/marine-craft/{hullId}/ownership-transfers") @PreAuthorize("hasAuthority('marine.craft.manage')")
    MarineService.Craft transfer(@PathVariable String hullId,@Valid @RequestBody TransferRequest b){return marine.transfer(hullId,b.owner(),actors.current().userId());}
    @PostMapping("/v1/marine-operator-licences") @PreAuthorize("hasAuthority('marine.operator-licence.issue')")
    ResponseEntity<MarineService.OperatorLicence> licence(@Valid @RequestBody LicenceRequest b){var l=marine.issueLicence(b.trafficFileNumber(),b.craftType(),b.validityDays(),actors.current().userId());return ResponseEntity.created(URI.create("/v1/marine-operator-licences/"+l.id())).body(l);}
}
