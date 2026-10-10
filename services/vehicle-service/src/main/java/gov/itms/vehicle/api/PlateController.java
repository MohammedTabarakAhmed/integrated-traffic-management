package gov.itms.vehicle.api;

import gov.itms.vehicle.application.PlateService;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Registration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plate numbers after the standard allocation (VL-033…VL-039; ADR-059) of {@code vehicle-service.v1.yaml}:
 * reservations, retention, sales and exchanges, loss reports, auctions, manufacturing orders and trial plates.
 */
@RestController
class PlateController {

    private final PlateService plates;
    private final Callers callers;

    PlateController(PlateService plates, Callers callers) {
        this.plates = plates;
        this.callers = callers;
    }

    record CategoryBody(@NotBlank String customerCategory) {
    }

    record ReservationBody(@NotBlank String seriesCode, @Min(1) long number, @NotBlank String holderFileNumber,
                           @NotBlank String customerCategory) {
    }

    record SaleBody(@NotBlank String buyerFileNumber, @NotBlank String customerCategory) {
    }

    record ExchangeBody(@NotNull UUID plateId, @NotBlank String customerCategory) {
    }

    record LossBody(@NotBlank String kind, @NotBlank @Size(max = 60) String policeReference,
                    @NotBlank String replacement, @NotBlank String customerCategory) {
    }

    @GetMapping("/v1/plates/{plateId}")
    @PreAuthorize("hasAuthority('vehicle.read')")
    Plates.Plate plate(@PathVariable UUID plateId) {
        return plates.plate(plateId, callers.current());
    }

    @GetMapping("/v1/plates")
    @PreAuthorize("hasAuthority('vehicle.read')")
    VehicleController.Collection<Plates.Plate> heldBy(@RequestParam String holderFileNumber) {
        return new VehicleController.Collection<>(plates.heldBy(holderFileNumber, callers.current()));
    }

    @PostMapping("/v1/plate-reservations")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> reserve(@Valid @RequestBody ReservationBody b) {
        return created(plates.reserve(b.seriesCode(), b.number(), b.holderFileNumber(), b.customerCategory(),
                callers.current()));
    }

    @PostMapping("/v1/plates/{plateId}/retention-renewals")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> renewRetention(@PathVariable UUID plateId,
                                                                @Valid @RequestBody CategoryBody b) {
        return created(plates.renewRetention(plateId, b.customerCategory(), callers.current()));
    }

    @PostMapping("/v1/plates/{plateId}/sales")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> sell(@PathVariable UUID plateId, @Valid @RequestBody SaleBody b) {
        return created(plates.sell(plateId, b.buyerFileNumber(), b.customerCategory(), callers.current()));
    }

    @PostMapping("/v1/vehicles/{vin}/plate-exchanges")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> exchange(@PathVariable String vin,
                                                          @Valid @RequestBody ExchangeBody b) {
        return created(plates.exchange(vin, b.plateId(), b.customerCategory(), callers.current()));
    }

    @PostMapping("/v1/plates/{plateId}/loss-reports")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> reportLoss(@PathVariable UUID plateId,
                                                            @Valid @RequestBody LossBody b) {
        return created(plates.reportLoss(plateId, b.kind(), b.policeReference(), b.replacement(),
                b.customerCategory(), callers.current()));
    }

    // ---- auctions (VL-034)

    record LotBody(@NotBlank String seriesCode, @Min(1) long number, @Min(0) long reserveMinor) {
    }

    record AuctionBody(@NotBlank @Size(max = 200) String title, @NotNull Plates.AuctionChannel salesChannel,
                       String partnerCode, @NotNull Instant startsAt, @NotNull Instant endsAt,
                       @NotBlank String region, @NotNull @Size(min = 1, max = 500) List<@Valid LotBody> lots) {
    }

    record BidBody(@NotNull UUID plateId, @NotBlank String bidderFileNumber, @Min(1) long amountMinor) {
    }

    @PostMapping("/v1/plate-auctions")
    @PreAuthorize("hasAuthority('vehicle.plate.auction')")
    ResponseEntity<PlateService.AuctionView> createAuction(@Valid @RequestBody AuctionBody b) {
        Plates.Auction a = plates.createAuction(b.title(), b.salesChannel(), b.partnerCode(), b.startsAt(),
                b.endsAt(), b.region(), b.lots().stream().map(l -> new PlateService.LotRequest(l.seriesCode(),
                        l.number(), l.reserveMinor())).toList(), callers.current());
        return ResponseEntity.status(201).body(plates.auction(a.id(), callers.current()));
    }

    @GetMapping("/v1/plate-auctions/{auctionId}")
    @PreAuthorize("hasAnyAuthority('vehicle.read', 'vehicle.service', 'vehicle.plate.auction')")
    PlateService.AuctionView auction(@PathVariable UUID auctionId) {
        return plates.auction(auctionId, callers.current());
    }

    @PostMapping("/v1/plate-auctions/{auctionId}/bids")
    @PreAuthorize("hasAnyAuthority('vehicle.service', 'vehicle.plate.auction')")
    ResponseEntity<Plates.Bid> bid(@PathVariable UUID auctionId, @Valid @RequestBody BidBody b) {
        return ResponseEntity.status(201).body(plates.bid(auctionId, b.plateId(), b.bidderFileNumber(),
                b.amountMinor(), callers.current()));
    }

    @PostMapping("/v1/plate-auctions/{auctionId}/close")
    @PreAuthorize("hasAuthority('vehicle.plate.auction')")
    PlateService.AuctionView close(@PathVariable UUID auctionId) {
        return plates.closeAuction(auctionId, callers.current());
    }

    // ---- manufacturing orders (VL-036)

    record OrderBody(@NotBlank String factoryCode) {
    }

    record FactoryBody(@NotNull Plates.OrderStatus status, @Size(max = 500) Map<UUID, String> serials) {
    }

    @PostMapping("/v1/plate-orders")
    @PreAuthorize("hasAuthority('vehicle.plate.manage')")
    ResponseEntity<PlateService.OrderView> raise(@Valid @RequestBody OrderBody b) {
        Plates.Order o = plates.raiseOrder(b.factoryCode(), callers.current());
        return ResponseEntity.status(201).body(plates.order(o.id(), callers.current()));
    }

    @GetMapping("/v1/plate-orders/{orderId}")
    @PreAuthorize("hasAnyAuthority('vehicle.plate.manage', 'vehicle.plate.factory')")
    PlateService.OrderView order(@PathVariable UUID orderId) {
        return plates.order(orderId, callers.current());
    }

    @PostMapping("/v1/plate-orders/{orderId}/status")
    @PreAuthorize("hasAuthority('vehicle.plate.factory')")
    PlateService.OrderView factory(@PathVariable UUID orderId, @Valid @RequestBody FactoryBody b) {
        return plates.factoryUpdate(orderId, b.status(), b.serials(), callers.current());
    }

    // ---- trial plates (VL-038)

    record TrialBody(@NotBlank String dealerFileNumber, @NotBlank String region, @Min(1) @Max(366) int days,
                     @NotBlank String customerCategory) {
    }

    @PostMapping("/v1/trial-plates")
    @PreAuthorize("hasAuthority('vehicle.service')")
    ResponseEntity<VehicleController.RequestDto> issueTrial(@Valid @RequestBody TrialBody b) {
        return created(plates.issueTrial(b.dealerFileNumber(), b.region(), b.days(), b.customerCategory(),
                callers.current()));
    }

    @GetMapping("/v1/trial-plates/{trialId}")
    @PreAuthorize("hasAnyAuthority('vehicle.read', 'vehicle.service')")
    Plates.Trial trial(@PathVariable UUID trialId) {
        return plates.trial(trialId, callers.current());
    }

    @PostMapping("/v1/trial-plates/{trialId}/exchange")
    @PreAuthorize("hasAuthority('vehicle.service')")
    Plates.Trial exchangeTrial(@PathVariable UUID trialId) {
        return plates.exchangeTrial(trialId, callers.current());
    }

    @PostMapping("/v1/trial-plates/{trialId}/cancel")
    @PreAuthorize("hasAuthority('vehicle.service')")
    Plates.Trial cancelTrial(@PathVariable UUID trialId) {
        return plates.cancelTrial(trialId, callers.current());
    }

    private static ResponseEntity<VehicleController.RequestDto> created(Registration.Request r) {
        return ResponseEntity.status(201).body(VehicleController.RequestDto.of(r));
    }
}
