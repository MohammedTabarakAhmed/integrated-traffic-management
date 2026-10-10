package gov.itms.vehicle.api;

import gov.itms.vehicle.application.Ports;
import gov.itms.vehicle.application.VehicleService;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
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

/** Vehicles, inspections, registration, renewal and plate series of {@code vehicle-service.v1.yaml}. */
@RestController
class VehicleController {

    private final VehicleService vehicles;
    private final Callers callers;
    private final java.time.Clock clock;

    VehicleController(VehicleService vehicles, Callers callers, java.time.Clock clock) {
        this.vehicles = vehicles;
        this.callers = callers;
        this.clock = clock;
    }

    record SpecDto(@NotBlank String engineNo, @NotBlank String make, @NotBlank String model, int modelYear,
                   @NotBlank String bodyType, @NotNull @Size(min = 1, max = 3) List<@NotBlank String> colours,
                   @NotBlank String fuelType, @NotBlank String steering, int seats, int emptyWeightKg,
                   int grossWeightKg, int axles, int cylinders, @NotBlank String category) {
        Vehicle.Spec toDomain() {
            return new Vehicle.Spec(engineNo, make, model, modelYear, bodyType, colours, fuelType, steering, seats,
                    emptyWeightKg, grossWeightKg, axles, cylinders, category);
        }

        static SpecDto of(Vehicle.Spec s) {
            return new SpecDto(s.engineNo(), s.make(), s.model(), s.modelYear(), s.bodyType(), s.colours(),
                    s.fuelType(), s.steering(), s.seats(), s.emptyWeightKg(), s.grossWeightKg(), s.axles(),
                    s.cylinders(), s.category());
        }
    }

    /** {@code spec} and {@code vin} may be left out when {@code customsCertificateNo} pre-fills them (VL-002). */
    record CreateRequest(@Size(max = 17) String vin, @Valid SpecDto spec, @NotBlank String ownerFileNumber,
                         @NotBlank String region, @Size(max = 60) String customsCertificateNo) {
    }

    record VehicleDto(String vin, SpecDto spec, String ownerFileNumber, String region, Vehicle.Status status,
                      boolean registrationValid, LocalDate registrationExpiry, String plateNumber, Instant createdAt) {
        static VehicleDto of(Vehicle v, LocalDate today) {
            return new VehicleDto(v.vin(), SpecDto.of(v.spec()), v.ownerFileNumber(), v.region(), v.status(),
                    v.registrationValid(today), v.registrationExpiry(), v.plateNumber(), v.createdAt());
        }
    }

    record InspectionRequest(@NotBlank String centreCode, @NotNull LocalDate inspectedOn, @Min(0) long odometerKm,
                             @Size(max = 50) Map<String, String> results, @NotNull Boolean passed,
                             @Size(max = 30) List<String> photoDocumentIds) {
    }

    record InspectionDto(UUID id, String centreCode, LocalDate inspectedOn, long odometerKm,
                         Map<String, String> results, boolean passed, List<String> photoDocumentIds,
                         Instant recordedAt) {
        static InspectionDto of(Registration.Inspection i) {
            return new InspectionDto(i.id(), i.centreCode(), i.inspectedOn(), i.odometerKm(), i.results(),
                    i.passed(), i.photoDocumentIds(), i.recordedAt());
        }
    }

    /** {@code plateId}: the owner's own retained number (VL-035); {@code cardDelivery}: VL-022. */
    record RegistrationRequestBody(@NotBlank String customerCategory, UUID plateId,
                                   Records.Delivery cardDelivery, @Size(max = 300) String deliveryAddress) {
    }

    record RequestDto(UUID id, Registration.Kind kind, Registration.Status status, UUID payableId, long amountMinor,
                      String currency, LocalDate newExpiry, Instant createdAt, Map<String, String> details,
                      String payerFileNumber) {
        static RequestDto of(Registration.Request r) {
            return new RequestDto(r.id(), r.kind(), r.status(), r.payableId(), r.amountMinor(), r.currency(),
                    r.newExpiry(), r.createdAt(), r.payload(), r.payerFile());
        }
    }

    record HistoryDto(String event, String actorId, Map<String, Object> detail, Instant at) {
        static HistoryDto of(Ports.VehicleRepository.HistoryEntry e) {
            return new HistoryDto(e.event(), e.actorId(), e.detail(), e.at());
        }
    }

    record SeriesDto(@NotBlank String code, @NotBlank String region, @NotBlank String plateCategory,
                     @NotBlank String colourCode, @NotBlank String design, @NotNull String prefix, long firstNumber,
                     long lastNumber, String premiumPattern, @NotNull Boolean active) {
        Plates.Series toDomain() {
            return new Plates.Series(code, region, plateCategory, colourCode, design, prefix, firstNumber, lastNumber,
                    premiumPattern, active);
        }

        static SeriesDto of(Plates.Series s) {
            return new SeriesDto(s.code(), s.region(), s.plateCategory(), s.colourCode(), s.design(), s.prefix(),
                    s.firstNumber(), s.lastNumber(), s.premiumPattern(), s.active());
        }
    }

    record Collection<T>(List<T> items) {
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    @PostMapping("/v1/vehicles")
    @PreAuthorize("hasAuthority('vehicle.create')")
    ResponseEntity<VehicleDto> create(@Valid @RequestBody CreateRequest body) {
        Vehicle v = vehicles.create(body.vin(), body.spec() == null ? null : body.spec().toDomain(),
                body.ownerFileNumber(), body.region(), body.customsCertificateNo(), callers.current());
        return ResponseEntity.created(URI.create("/v1/vehicles/" + v.vin())).body(VehicleDto.of(v, today()));
    }

    @GetMapping("/v1/vehicles/{vin}")
    @PreAuthorize("hasAuthority('vehicle.read')")
    VehicleDto get(@PathVariable String vin) {
        return VehicleDto.of(vehicles.get(vin, callers.current()), today());
    }

    @GetMapping(value = "/v1/vehicles", params = "plate")
    @PreAuthorize("hasAuthority('vehicle.read')")
    VehicleDto byPlate(@RequestParam String plate) {
        return VehicleDto.of(vehicles.byPlate(plate, callers.current()), today());
    }

    @GetMapping("/v1/vehicles/{vin}/history")
    @PreAuthorize("hasAuthority('vehicle.read')")
    Collection<HistoryDto> history(@PathVariable String vin) {
        return new Collection<>(vehicles.history(vin, callers.current()).stream().map(HistoryDto::of).toList());
    }

    @PostMapping("/v1/vehicles/{vin}/inspection-results")
    @PreAuthorize("hasAuthority('vehicle.inspection.record')")
    ResponseEntity<InspectionDto> inspection(@PathVariable String vin, @Valid @RequestBody InspectionRequest body) {
        Registration.Inspection i = vehicles.recordInspection(vin, body.centreCode(), body.inspectedOn(),
                body.odometerKm(), body.results(), body.passed(), body.photoDocumentIds(), callers.current());
        return ResponseEntity.created(URI.create("/v1/vehicles/" + vin + "/history")).body(InspectionDto.of(i));
    }

    @PostMapping("/v1/vehicles/{vin}/registrations")
    @PreAuthorize("hasAuthority('vehicle.register')")
    ResponseEntity<RequestDto> register(@PathVariable String vin, @Valid @RequestBody RegistrationRequestBody body) {
        Registration.Request r = vehicles.startFirstRegistration(vin, body.customerCategory(), body.plateId(),
                body.cardDelivery(), body.deliveryAddress(), callers.current());
        return ResponseEntity.status(201).body(RequestDto.of(r));
    }

    @PostMapping("/v1/vehicles/{vin}/renewals")
    @PreAuthorize("hasAuthority('vehicle.register')")
    ResponseEntity<RequestDto> renew(@PathVariable String vin, @Valid @RequestBody RegistrationRequestBody body) {
        Registration.Request r = vehicles.startRenewal(vin, body.customerCategory(), callers.current());
        return ResponseEntity.status(201).body(RequestDto.of(r));
    }

    @PostMapping("/v1/plate-series")
    @PreAuthorize("hasAuthority('vehicle.plate.manage')")
    SeriesDto saveSeries(@Valid @RequestBody SeriesDto body) {
        return SeriesDto.of(vehicles.saveSeries(body.toDomain(), callers.current()));
    }
}
