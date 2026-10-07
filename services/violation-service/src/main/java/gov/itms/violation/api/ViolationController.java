package gov.itms.violation.api;

import gov.itms.violation.application.ViolationService;
import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.Violation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catalogue, violations and black points of {@code violation-service.v1.yaml}. */
@RestController
class ViolationController {

    private final ViolationService violations;
    private final Callers callers;

    ViolationController(ViolationService violations, Callers callers) {
        this.violations = violations;
        this.callers = callers;
    }

    record BandDto(int fromKmh, Integer toKmh, long fineMinor, int blackPoints) {
    }

    record CatalogueDto(@NotBlank String code, Integer versionNo, String region, @NotBlank String legalArticle,
                        @NotBlank String descriptionNl, @NotBlank String descriptionEn, long fineMinor, int blackPoints,
                        int impoundDays, int suspensionDays, boolean courtReferral,
                        @NotNull CatalogueEntry.AppliesTo appliesTo, @Size(max = 20) List<@NotNull BandDto> speedBands,
                        Integer repeatWithinDays, Long repeatFineMinor, @NotBlank String financialItem,
                        @NotBlank String beneficiary, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
                        String reason) {
        CatalogueEntry toDomain() {
            return new CatalogueEntry(code, 0, region, legalArticle, descriptionNl, descriptionEn, fineMinor,
                    blackPoints, impoundDays, suspensionDays, courtReferral, appliesTo,
                    speedBands == null ? List.of() : speedBands.stream().map(b -> new CatalogueEntry.SpeedBand(
                            b.fromKmh(), b.toKmh(), b.fineMinor(), b.blackPoints())).toList(),
                    repeatWithinDays, repeatFineMinor, financialItem, beneficiary, effectiveFrom, effectiveTo, "-",
                    Instant.EPOCH);
        }

        static CatalogueDto of(CatalogueEntry e) {
            return new CatalogueDto(e.code(), e.versionNo(), e.region(), e.legalArticle(), e.descriptionNl(),
                    e.descriptionEn(), e.fineMinor(), e.blackPoints(), e.impoundDays(), e.suspensionDays(),
                    e.courtReferral(), e.appliesTo(), e.speedBands().stream().map(b -> new BandDto(b.fromKmh(),
                    b.toKmh(), b.fineMinor(), b.blackPoints())).toList(), e.repeatWithinDays(), e.repeatFineMinor(),
                    e.financialItem(), e.beneficiary(), e.effectiveFrom(), e.effectiveTo(), null);
        }
    }

    record ReportDto(@NotBlank String catalogueCode, @NotNull Instant occurredAt, @NotBlank String region,
                     @NotBlank @Size(max = 300) String location, String plate, String vin, String ownerFileNumber,
                     String driverFileNumber, Integer speedLimitKmh, Integer measuredSpeedKmh,
                     @NotNull Violation.Source source, String deviceId, @Size(max = 20) List<String> evidenceIds) {
        Violation.Report toDomain() {
            return new Violation.Report(catalogueCode, occurredAt, region, location, plate, vin, ownerFileNumber,
                    driverFileNumber, speedLimitKmh, measuredSpeedKmh, source, deviceId, evidenceIds);
        }
    }

    record ViolationDto(String number, String catalogueCode, int catalogueVersion, Instant occurredAt, String region,
                        String location, String plate, String responsibleFileNumber, Integer overKmh, long fineMinor,
                        int blackPoints, boolean repeat, Violation.Status status, String cancelReason,
                        List<String> evidenceIds, Instant recordedAt) {
        static ViolationDto of(Violation v) {
            return new ViolationDto(v.number(), v.catalogueCode(), v.catalogueVersion(), v.report().occurredAt(),
                    v.report().region(), v.report().location(), v.report().plate(),
                    v.report().responsibleFileNumber(), v.report().overKmh(), v.fineMinor(), v.blackPoints(),
                    v.repeat(), v.status(), v.cancelReason(), v.report().evidenceIds(), v.recordedAt());
        }
    }

    record CancelRequest(@NotBlank String reasonCode, @Size(min = 3, max = 500) String note) {
    }

    record StandingDto(String fileNumber, int activePoints, int threshold, BlackPoints.Suspension activeSuspension,
                       List<BlackPoints.Entry> entries, List<BlackPoints.Suspension> suspensions) {
        static StandingDto of(ViolationService.Standing s) {
            return new StandingDto(s.fileNumber(), s.activePoints(), s.threshold(), s.activeSuspension(), s.entries(),
                    s.suspensions());
        }
    }

    record Collection<T>(List<T> items) {
    }

    @PostMapping("/v1/violation-catalogue")
    @PreAuthorize("hasAuthority('violation.catalogue.manage')")
    ResponseEntity<CatalogueDto> publish(@Valid @RequestBody CatalogueDto body) {
        CatalogueEntry e = violations.publish(body.toDomain(), body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/violation-catalogue/" + e.code())).body(CatalogueDto.of(e));
    }

    @GetMapping("/v1/violation-catalogue")
    @PreAuthorize("isAuthenticated()")
    Collection<CatalogueDto> catalogue() {
        return new Collection<>(violations.catalogue().stream().map(CatalogueDto::of).toList());
    }

    @GetMapping("/v1/violation-catalogue/{code}")
    @PreAuthorize("isAuthenticated()")
    Collection<CatalogueDto> versions(@PathVariable String code) {
        return new Collection<>(violations.versions(code).stream().map(CatalogueDto::of).toList());
    }

    @PostMapping("/v1/violations")
    @PreAuthorize("hasAuthority('violation.record')")
    ResponseEntity<ViolationDto> record(@Valid @RequestBody ReportDto body) {
        Violation v = violations.record(body.toDomain(), callers.current());
        return ResponseEntity.created(URI.create("/v1/violations/" + v.number())).body(ViolationDto.of(v));
    }

    @GetMapping("/v1/violations")
    @PreAuthorize("hasAuthority('violation.read')")
    Collection<ViolationDto> byFile(@RequestParam String fileNumber,
                                    @RequestParam(required = false) Violation.Status status,
                                    @RequestParam(defaultValue = "100") int limit) {
        return new Collection<>(violations.byFile(fileNumber, status, limit, callers.current()).stream()
                .map(ViolationDto::of).toList());
    }

    @GetMapping("/v1/violations/{number}")
    @PreAuthorize("hasAuthority('violation.read')")
    ViolationDto get(@PathVariable String number) {
        return ViolationDto.of(violations.get(number, callers.current()));
    }

    @PostMapping("/v1/violations/{number}/cancellations")
    @PreAuthorize("hasAuthority('violation.cancel')")
    ViolationDto cancel(@PathVariable String number, @Valid @RequestBody CancelRequest body) {
        return ViolationDto.of(violations.cancel(number, body.reasonCode(), body.note(), callers.current()));
    }

    @GetMapping("/v1/black-point-accounts/{fileNumber}")
    @PreAuthorize("hasAuthority('violation.read')")
    StandingDto standing(@PathVariable String fileNumber) {
        return StandingDto.of(violations.standing(fileNumber, callers.current()));
    }
}
