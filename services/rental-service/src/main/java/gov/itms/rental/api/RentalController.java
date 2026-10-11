package gov.itms.rental.api;

import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.rental.application.RentalService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Rental company and contract endpoints (TR-020..TR-033). */
@RestController
class RentalController {
    private final RentalService rentals;
    private final CurrentActorProvider actors;
    RentalController(RentalService rentals, CurrentActorProvider actors) {
        this.rentals = rentals;
        this.actors = actors;
    }

    record CompanyRequest(@NotBlank String trafficFileNumber, @NotBlank String commercialLicence,
                          @NotBlank String region) { }
    record BranchRequest(@NotBlank String code,@NotBlank @Size(max=120) String name,@NotBlank String region) { }
    record CompanyStatusRequest(@NotBlank @Size(max=500) String reason) { }
    record ContractRequest(@NotBlank String companyFile, @NotBlank String vehiclePlate, String renterFile,
                           String visitorPassport, String foreignLicence,
                           @Size(max = 10) List<@NotBlank String> additionalDrivers,
                           @NotNull Instant startsAt, @NotNull Instant endsAt, @NotBlank String pickupBranch,
                           @NotBlank String returnBranch, @NotBlank String type, @Min(0) long bondMinor) { }
    record InspectionRequest(@NotBlank String type, @NotNull Instant inspectedAt, @Min(0) long odometerKm,
                             @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100)
                             int fuelPercent, @NotNull @Size(max = 100) List<@NotBlank @Size(max = 500) String> damageNotes,
                             @NotNull @Size(max = 100) List<@NotBlank @Size(max = 64) String> evidenceIds,
                             @Size(max = 1000) String notes) { }

    @PostMapping("/v1/rental-companies")
    @PreAuthorize("hasAuthority('rental.company.manage')")
    ResponseEntity<RentalService.Company> registerCompany(@Valid @RequestBody CompanyRequest body) {
        requireScope("rental.company.manage", body.region());
        RentalService.Company company = rentals.registerCompany(body.trafficFileNumber(), body.commercialLicence(),
                body.region(), actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/rental-companies/" + company.id())).body(company);
    }

    @PostMapping("/v1/rental-companies/{companyFile}/branches")
    @PreAuthorize("hasAuthority('rental.company.manage')")
    ResponseEntity<RentalService.Branch> branch(@PathVariable String companyFile,@Valid @RequestBody BranchRequest body) {
        requireScope("rental.company.manage",rentals.companyRegion(companyFile));
        RentalService.Branch branch=rentals.addBranch(companyFile,body.code(),body.name(),body.region(),actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/rental-companies/"+companyFile+"/branches/"+branch.code())).body(branch);
    }

    @PostMapping("/v1/rental-companies/{companyFile}/suspensions")
    @PreAuthorize("hasAuthority('rental.company.manage')")
    RentalService.Company suspend(@PathVariable String companyFile,@Valid @RequestBody CompanyStatusRequest body) {
        requireScope("rental.company.manage",rentals.companyRegion(companyFile));
        return rentals.suspendCompany(companyFile,body.reason(),actors.current().userId());
    }

    @PostMapping("/v1/rental-companies/{companyFile}/reinstatements")
    @PreAuthorize("hasAuthority('rental.company.manage')")
    RentalService.Company reinstate(@PathVariable String companyFile,@Valid @RequestBody CompanyStatusRequest body) {
        requireScope("rental.company.manage",rentals.companyRegion(companyFile));
        return rentals.reinstateCompany(companyFile,body.reason(),actors.current().userId());
    }

    @PostMapping("/v1/rental-contracts")
    @PreAuthorize("hasAuthority('rental.contract.record')")
    ResponseEntity<RentalService.Contract> create(@Valid @RequestBody ContractRequest body) {
        requireScope("rental.contract.record", rentals.companyRegion(body.companyFile()));
        RentalService.Contract contract = rentals.createContract(body.companyFile(), body.vehiclePlate(),
                body.renterFile(), body.visitorPassport(), body.foreignLicence(), body.additionalDrivers(),
                body.startsAt(), body.endsAt(), body.pickupBranch(), body.returnBranch(), body.type(),
                body.bondMinor(), actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/rental-contracts/" + contract.id())).body(contract);
    }

    /** TR-032: the response for a plate and instant; covered=false when no contract covers it. National read. */
    record CoverageResponse(boolean covered, UUID contractId, String companyFile, String renterFile,
                            boolean visitor) { }

    @GetMapping("/v1/rental-contracts/coverage")
    @PreAuthorize("hasAuthority('rental.contract.coverage')")
    CoverageResponse coverage(@org.springframework.web.bind.annotation.RequestParam String plate,
                              @org.springframework.web.bind.annotation.RequestParam Instant at) {
        return rentals.coverage(plate, at)
                .map(c -> new CoverageResponse(true, c.contractId(), c.companyFile(), c.renterFile(), c.visitor()))
                .orElseGet(() -> new CoverageResponse(false, null, null, null, false));
    }

    @GetMapping("/v1/rental-contracts/{id}")
    @PreAuthorize("hasAuthority('rental.contract.read')")
    RentalService.Contract get(@PathVariable UUID id) {
        RentalService.Contract contract = rentals.contract(id);
        requireScope("rental.contract.read", contract.region());
        return contract;
    }

    @PostMapping("/v1/rental-contracts/{id}/inspections")
    @PreAuthorize("hasAuthority('rental.contract.record')")
    ResponseEntity<RentalService.Inspection> inspect(@PathVariable UUID id,
                                                     @Valid @RequestBody InspectionRequest body) {
        requireScope("rental.contract.record", rentals.contract(id).region());
        RentalService.Inspection inspection = rentals.recordInspection(id, body.type(), body.inspectedAt(),
                body.odometerKm(), body.fuelPercent(), body.damageNotes(), body.evidenceIds(), body.notes(),
                actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/rental-contracts/" + id + "/inspections/" + inspection.id()))
                .body(inspection);
    }

    @GetMapping("/v1/rental-contracts/{id}/inspections")
    @PreAuthorize("hasAuthority('rental.contract.read')")
    List<RentalService.Inspection> inspections(@PathVariable UUID id) {
        requireScope("rental.contract.read", rentals.contract(id).region());
        return rentals.inspections(id);
    }

    private void requireScope(String permission, String region) {
        if (!actors.authorisation().allows(permission, new ScopeTarget(region, null, null))) {
            throw new org.springframework.security.access.AccessDeniedException("regional permission required");
        }
    }
}
