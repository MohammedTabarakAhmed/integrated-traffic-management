package gov.itms.vehicle.api;

import gov.itms.vehicle.application.VehicleTransactions;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vehicle transactions after registration (Increment 2; ADR-059) of {@code vehicle-service.v1.yaml}: customs feed,
 * bans, approvals, transfers, changes, cards, de-registration, certificates, temporary permits, mortgages, fleets and
 * dealers.
 */
@RestController
class TransactionController {

    private final VehicleTransactions transactions;
    private final Callers callers;
    private final Clock clock;

    TransactionController(VehicleTransactions transactions, Callers callers, Clock clock) {
        this.transactions = transactions;
        this.callers = callers;
        this.clock = clock;
    }

    // ---- customs and dealer feed (VL-002)

    record CustomsBody(@NotBlank @Size(max = 60) String certificateNo, @NotNull Records.CustomsSource source,
                       @NotBlank String issuerCode, @NotBlank @Size(max = 17) String vin,
                       @NotNull @Valid VehicleController.SpecDto spec, @NotNull LocalDate issuedOn) {
    }

    record CustomsDto(String certificateNo, Records.CustomsSource source, String issuerCode, String vin,
                      VehicleController.SpecDto spec, LocalDate issuedOn, boolean used) {
        static CustomsDto of(Records.CustomsCertificate c) {
            return new CustomsDto(c.certificateNo(), c.source(), c.issuerCode(), c.vin(),
                    VehicleController.SpecDto.of(c.spec()), c.issuedOn(), c.usedBy() != null);
        }
    }

    @PostMapping("/v1/customs-certificates")
    @PreAuthorize("hasAuthority('vehicle.customs.feed')")
    ResponseEntity<CustomsDto> receiveCustoms(@Valid @RequestBody CustomsBody b) {
        Records.CustomsCertificate c = transactions.receiveCustoms(b.certificateNo(), b.source(), b.issuerCode(),
                b.vin(), b.spec().toDomain(), b.issuedOn(), callers.current());
        return ResponseEntity.created(URI.create("/v1/customs-certificates/" + c.certificateNo()))
                .body(CustomsDto.of(c));
    }

    @GetMapping("/v1/customs-certificates/{certificateNo}")
    @PreAuthorize("hasAnyAuthority('vehicle.read', 'vehicle.create')")
    CustomsDto customs(@PathVariable String certificateNo) {
        return CustomsDto.of(transactions.customs(certificateNo, callers.current()));
    }

    // ---- bans (VL-006)

    record BanBody(@NotBlank String reasonCode, @NotBlank @Size(max = 60) String reference) {
    }

    record LiftBody(@NotBlank @Size(max = 500) String reason) {
    }

    record BanDto(UUID id, String reasonCode, String reference, String imposedBy, Instant imposedAt, boolean active,
                  String liftedBy, Instant liftedAt, String liftReason) {
        static BanDto of(Records.Ban b) {
            return new BanDto(b.id(), b.reasonCode(), b.reference(), b.imposedBy(), b.imposedAt(), b.active(),
                    b.liftedBy(), b.liftedAt(), b.liftReason());
        }
    }

    @PostMapping("/v1/vehicles/{vin}/bans")
    @PreAuthorize("hasAuthority('vehicle.ban.manage')")
    ResponseEntity<BanDto> ban(@PathVariable String vin, @Valid @RequestBody BanBody b) {
        return ResponseEntity.status(201).body(BanDto.of(transactions.ban(vin, b.reasonCode(), b.reference(),
                callers.current())));
    }

    @PostMapping("/v1/vehicles/{vin}/bans/{banId}/lift")
    @PreAuthorize("hasAuthority('vehicle.ban.manage')")
    BanDto liftBan(@PathVariable String vin, @PathVariable UUID banId, @Valid @RequestBody LiftBody b) {
        return BanDto.of(transactions.liftBan(vin, banId, b.reason(), callers.current()));
    }

    // ---- approvals (VL-018, VL-019)

    record ApprovalBody(@NotNull Records.ApprovalKind kind, @Size(max = 30) Map<String, String> detail) {
    }

    record DecisionBody(@NotNull Boolean approve, @Size(max = 500) String note) {
    }

    @PostMapping("/v1/vehicles/{vin}/approvals")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<Records.Approval> submitApproval(@PathVariable String vin, @Valid @RequestBody ApprovalBody b) {
        return ResponseEntity.status(201).body(transactions.submitApproval(vin, b.kind(), b.detail(),
                callers.current()));
    }

    @PostMapping("/v1/vehicles/{vin}/approvals/{approvalId}/decision")
    @PreAuthorize("hasAuthority('vehicle.approve')")
    Records.Approval decide(@PathVariable String vin, @PathVariable UUID approvalId,
                            @Valid @RequestBody DecisionBody b) {
        return transactions.decideApproval(vin, approvalId, b.approve(), b.note(), callers.current());
    }

    // ---- transfer (VL-012)

    record TransferBody(@NotBlank String buyerFileNumber, @NotNull Boolean buyerAcceptsFines,
                        @NotNull Boolean sellerRetainsPlate, @NotBlank String customerCategory) {
    }

    @PostMapping("/v1/vehicles/{vin}/transfers")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> transfer(@PathVariable String vin,
                                                          @Valid @RequestBody TransferBody b) {
        return created(transactions.startTransfer(vin, b.buyerFileNumber(), b.buyerAcceptsFines(),
                b.sellerRetainsPlate(), b.customerCategory(), callers.current()));
    }

    @PostMapping("/v1/vehicles/{vin}/transfers/{requestId}/consent")
    @PreAuthorize("hasAuthority('vehicle.service')")
    VehicleController.RequestDto consent(@PathVariable String vin, @PathVariable UUID requestId) {
        return VehicleController.RequestDto.of(transactions.consentTransfer(vin, requestId, callers.current()));
    }

    // ---- change of details (VL-013)

    record ChangeBody(@Size(min = 1, max = 3) List<@NotBlank String> colours, String engineNo, String bodyType,
                      String category, @NotBlank String customerCategory) {
    }

    @PostMapping("/v1/vehicles/{vin}/changes")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> change(@PathVariable String vin, @Valid @RequestBody ChangeBody b) {
        return created(transactions.startChange(vin, b.colours(), b.engineNo(), b.bodyType(), b.category(),
                b.customerCategory(), callers.current()));
    }

    // ---- cards (VL-014, VL-022)

    record CardBody(@NotBlank String reason, @NotNull Records.Delivery delivery, @Size(max = 300) String address,
                    @NotBlank String customerCategory) {
    }

    record CardStatusBody(@NotNull Records.CardStatus status, @Size(max = 60) String trackingRef) {
    }

    @PostMapping("/v1/vehicles/{vin}/card-replacements")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> replaceCard(@PathVariable String vin,
                                                             @Valid @RequestBody CardBody b) {
        return created(transactions.startCardReplacement(vin, b.reason(), b.delivery(), b.address(),
                b.customerCategory(), callers.current()));
    }

    @GetMapping("/v1/vehicles/{vin}/cards")
    @PreAuthorize("hasAnyAuthority('vehicle.read', 'vehicle.card.manage')")
    VehicleController.Collection<Records.Card> cards(@PathVariable String vin) {
        return new VehicleController.Collection<>(transactions.cards(vin, callers.current()));
    }

    @PostMapping("/v1/vehicles/{vin}/cards/{cardId}/status")
    @PreAuthorize("hasAuthority('vehicle.card.manage')")
    Records.Card moveCard(@PathVariable String vin, @PathVariable UUID cardId, @Valid @RequestBody CardStatusBody b) {
        return transactions.moveCard(vin, cardId, b.status(), b.trackingRef(), callers.current());
    }

    // ---- de-registration (VL-015)

    record DeregistrationBody(@NotBlank String reason, @NotNull Boolean retainPlate, String destinationCountry,
                              @NotBlank String customerCategory) {
    }

    @PostMapping("/v1/vehicles/{vin}/deregistrations")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> deregister(@PathVariable String vin,
                                                            @Valid @RequestBody DeregistrationBody b) {
        return created(transactions.startDeregistration(vin, b.reason(), b.retainPlate(), b.destinationCountry(),
                b.customerCategory(), callers.current()));
    }

    // ---- certificates (VL-017, VL-020)

    record CertificateBody(@NotNull Records.CertificateKind kind, @NotBlank String customerCategory) {
    }

    record RecoveryBody(@NotBlank String insurerCode, @NotBlank @Size(max = 60) String claimRef,
                        @NotBlank String reason) {
    }

    @PostMapping("/v1/vehicles/{vin}/certificates")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> requestCertificate(@PathVariable String vin,
                                                                    @Valid @RequestBody CertificateBody b) {
        return created(transactions.requestCertificate(vin, b.kind(), b.customerCategory(), callers.current()));
    }

    @GetMapping("/v1/vehicles/{vin}/certificates")
    @PreAuthorize("hasAuthority('vehicle.read')")
    VehicleController.Collection<Records.Certificate> certificates(@PathVariable String vin) {
        return new VehicleController.Collection<>(transactions.certificates(vin, callers.current()));
    }

    @PostMapping("/v1/vehicles/{vin}/insurance-recovery-certificates")
    @PreAuthorize("hasAuthority('vehicle.insurance.recovery')")
    ResponseEntity<Records.Certificate> recovery(@PathVariable String vin, @Valid @RequestBody RecoveryBody b) {
        return ResponseEntity.status(201).body(transactions.insuranceRecovery(vin, b.insurerCode(), b.claimRef(),
                b.reason(), callers.current()));
    }

    // ---- temporary entry and exit (VL-016)

    record EntryBody(@NotBlank @Size(max = 17) String vin, @NotBlank String foreignPlate, @NotBlank String country,
                     @NotBlank String holderFileNumber, @NotBlank String borderPoint, @NotNull LocalDate validFrom,
                     @NotNull LocalDate validTo, @NotBlank String region, @NotBlank String customerCategory) {
    }

    record ExitBody(@NotBlank String country, @NotBlank String borderPoint, @NotNull LocalDate validFrom,
                    @NotNull LocalDate validTo, @NotBlank String customerCategory) {
    }

    @PostMapping("/v1/temporary-permits")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> entryPermit(@Valid @RequestBody EntryBody b) {
        return created(transactions.startEntryPermit(b.vin(), b.foreignPlate(), b.country(), b.holderFileNumber(),
                b.borderPoint(), b.validFrom(), b.validTo(), b.region(), b.customerCategory(), callers.current()));
    }

    @PostMapping("/v1/vehicles/{vin}/exit-permits")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> exitPermit(@PathVariable String vin, @Valid @RequestBody ExitBody b) {
        return created(transactions.startExitPermit(vin, b.country(), b.borderPoint(), b.validFrom(), b.validTo(),
                b.customerCategory(), callers.current()));
    }

    @GetMapping("/v1/temporary-permits/{permitId}")
    @PreAuthorize("hasAnyAuthority('vehicle.read', 'vehicle.service')")
    Records.TemporaryPermit permit(@PathVariable UUID permitId) {
        return transactions.permit(permitId, callers.current());
    }

    @PostMapping("/v1/temporary-permits/{permitId}/close")
    @PreAuthorize("hasAuthority('vehicle.service')")
    Records.TemporaryPermit closePermit(@PathVariable UUID permitId) {
        return transactions.closePermit(permitId, callers.current());
    }

    // ---- mortgages (VL-021)

    record MortgageBody(@NotBlank String bankCode, @NotBlank @Size(max = 60) String contractRef) {
    }

    record ConsentBody(@NotNull UUID transferId) {
    }

    @PostMapping("/v1/vehicles/{vin}/mortgages")
    @PreAuthorize("hasAuthority('vehicle.mortgage.manage')")
    ResponseEntity<Records.Mortgage> mortgage(@PathVariable String vin, @Valid @RequestBody MortgageBody b) {
        return ResponseEntity.status(201).body(transactions.registerMortgage(vin, b.bankCode(), b.contractRef(),
                callers.current()));
    }

    @PostMapping("/v1/vehicles/{vin}/mortgages/{mortgageId}/release")
    @PreAuthorize("hasAuthority('vehicle.mortgage.manage')")
    Records.Mortgage release(@PathVariable String vin, @PathVariable UUID mortgageId) {
        return transactions.releaseMortgage(vin, mortgageId, callers.current());
    }

    @PostMapping("/v1/vehicles/{vin}/mortgages/{mortgageId}/transfer-consents")
    @PreAuthorize("hasAuthority('vehicle.mortgage.manage')")
    Records.Mortgage mortgageConsent(@PathVariable String vin, @PathVariable UUID mortgageId,
                                     @Valid @RequestBody ConsentBody b) {
        return transactions.consentMortgageTransfer(vin, mortgageId, b.transferId(), callers.current());
    }

    // ---- the vehicle file's records (VL-005)

    @GetMapping("/v1/vehicles/{vin}/records")
    @PreAuthorize("hasAuthority('vehicle.read')")
    VehicleTransactions.FileRecords records(@PathVariable String vin) {
        return transactions.records(vin, callers.current());
    }

    // ---- fleets (VL-026) and dealers (VL-027)

    record BulkBody(@NotNull @Size(min = 1, max = 200) List<@NotBlank String> vins,
                    @NotBlank String customerCategory) {
    }

    record AssignmentBody(@NotBlank String vin, @NotBlank String driverFileNumber, LocalDate validFrom,
                          LocalDate validTo) {
    }

    record HandoverBody(@NotBlank String vin, @NotBlank String buyerFileNumber) {
    }

    @GetMapping(value = "/v1/fleets/{fileNumber}/vehicles", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('vehicle.read')")
    VehicleController.Collection<VehicleController.VehicleDto> fleet(@PathVariable String fileNumber) {
        LocalDate today = LocalDate.now(clock);
        return new VehicleController.Collection<>(transactions.fleet(fileNumber, callers.current()).stream()
                .map(v -> VehicleController.VehicleDto.of(v, today)).toList());
    }

    /** The fleet list as a download (VL-026). */
    @GetMapping(value = "/v1/fleets/{fileNumber}/vehicles", produces = "text/csv")
    @PreAuthorize("hasAuthority('vehicle.read')")
    ResponseEntity<String> fleetCsv(@PathVariable String fileNumber) {
        LocalDate today = LocalDate.now(clock);
        StringBuilder csv = new StringBuilder("vin,plate,make,model,modelYear,category,status,registrationExpiry,"
                + "registrationValid\n");
        for (Vehicle v : transactions.fleet(fileNumber, callers.current())) {
            csv.append(v.vin()).append(',').append(v.plateNumber() == null ? "" : v.plateNumber()).append(',')
                    .append(v.spec().make()).append(',').append(v.spec().model()).append(',')
                    .append(v.spec().modelYear()).append(',').append(v.spec().category()).append(',')
                    .append(v.status()).append(',')
                    .append(v.registrationExpiry() == null ? "" : v.registrationExpiry()).append(',')
                    .append(v.registrationValid(today)).append('\n');
        }
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"fleet.csv\"")
                .contentType(MediaType.parseMediaType("text/csv")).body(csv.toString());
    }

    @PostMapping("/v1/fleets/{fileNumber}/renewals")
    @PreAuthorize("hasAuthority('vehicle.register')")
    VehicleController.Collection<VehicleTransactions.BulkOutcome> bulkRenewal(@PathVariable String fileNumber,
                                                                             @Valid @RequestBody BulkBody b) {
        return new VehicleController.Collection<>(transactions.bulkRenewal(fileNumber, b.vins(),
                b.customerCategory(), callers.current()));
    }

    @PostMapping("/v1/fleets/{fileNumber}/driver-assignments")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<Records.DriverAssignment> assign(@PathVariable String fileNumber,
                                                    @Valid @RequestBody AssignmentBody b) {
        return ResponseEntity.status(201).body(transactions.assignDriver(fileNumber, b.vin(), b.driverFileNumber(),
                b.validFrom(), b.validTo(), callers.current()));
    }

    @GetMapping("/v1/fleets/{fileNumber}/driver-assignments")
    @PreAuthorize("hasAuthority('vehicle.read')")
    VehicleController.Collection<Records.DriverAssignment> assignments(@PathVariable String fileNumber) {
        return new VehicleController.Collection<>(transactions.assignments(fileNumber, callers.current()));
    }

    @PostMapping("/v1/dealers/{fileNumber}/registrations")
    @PreAuthorize("hasAuthority('vehicle.register')")
    VehicleController.Collection<VehicleTransactions.BulkOutcome> bulkRegistration(@PathVariable String fileNumber,
                                                                                  @Valid @RequestBody BulkBody b) {
        return new VehicleController.Collection<>(transactions.bulkFirstRegistration(fileNumber, b.vins(),
                b.customerCategory(), callers.current()));
    }

    @PostMapping("/v1/dealers/{fileNumber}/handovers")
    @PreAuthorize("hasAuthority('vehicle.service')")
    VehicleController.VehicleDto handover(@PathVariable String fileNumber, @Valid @RequestBody HandoverBody b) {
        return VehicleController.VehicleDto.of(transactions.handover(fileNumber, b.vin(), b.buyerFileNumber(),
                callers.current()), LocalDate.now(clock));
    }

    private static ResponseEntity<VehicleController.RequestDto> created(Registration.Request r) {
        return ResponseEntity.status(201).body(VehicleController.RequestDto.of(r));
    }
}
