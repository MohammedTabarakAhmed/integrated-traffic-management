package gov.itms.licensing.api;

import gov.itms.licensing.api.ApiModels.Collection;
import gov.itms.licensing.api.ApiModels.RequestDto;
import gov.itms.licensing.application.ForeignService;
import gov.itms.licensing.domain.Foreign;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import jakarta.validation.Valid;
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

/** Exchange rules, foreign licences, exchange, temporary and international permits (DL-054…DL-056, DL-063). */
@RestController
class ForeignController {

    record RuleRequest(@NotBlank String countryCode, @NotNull Foreign.ExchangeMode mode,
                       @Size(max = 30) Map<String, String> classMap,
                       @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record DecisionBody(@Size(min = 3, max = 500) String reason) {
    }

    record RuleDto(UUID id, String countryCode, Foreign.ExchangeMode mode, Map<String, String> classMap,
                   Foreign.RuleStatus status, String submittedBy, Instant submittedAt, String decidedBy,
                   Instant decidedAt) {
        static RuleDto of(Foreign.CountryRule r) {
            return new RuleDto(r.id(), r.countryCode(), r.mode(), r.classMap(), r.status(), r.submittedBy(),
                    r.submittedAt(), r.decidedBy(), r.decidedAt());
        }
    }

    record ForeignRequest(@NotBlank String trafficFileNumber, @NotBlank String countryCode,
                          @NotBlank String foreignNumber, @NotNull @Size(min = 1, max = 30) List<String> classes,
                          LocalDate issuedOn, @NotNull LocalDate expiresOn, @NotBlank String language,
                          @NotBlank String documentId, String translationDocumentId) {
        Foreign.ForeignLicence toDomain() {
            return new Foreign.ForeignLicence(UUID.randomUUID(), trafficFileNumber, countryCode, foreignNumber,
                    classes, issuedOn, expiresOn, language, documentId, translationDocumentId,
                    Foreign.ForeignStatus.RECORDED, null, "-", Instant.EPOCH);
        }
    }

    record ForeignDto(UUID id, String trafficFileNumber, String countryCode, String foreignNumber,
                      List<String> classes, LocalDate issuedOn, LocalDate expiresOn, String language,
                      boolean translated, Foreign.ForeignStatus status, UUID licenceId, Instant recordedAt) {
        static ForeignDto of(Foreign.ForeignLicence f) {
            return new ForeignDto(f.id(), f.trafficFileNumber(), f.countryCode(), f.foreignNumber(), f.classes(),
                    f.issuedOn(), f.expiresOn(), f.language(), f.translationDocumentId() != null, f.status(),
                    f.licenceId(), f.recordedAt());
        }
    }

    record ExchangeRequest(@NotBlank String region, String customerCategory, LocalDate residencyExpiresOn,
                           Records.Delivery delivery, @Size(max = 300) String address) {
    }

    record PermitRequest(@NotNull Foreign.PermitKind kind, @NotBlank String trafficFileNumber,
                         @NotBlank String region, UUID licenceId, UUID foreignLicenceId) {
    }

    record PermitDto(UUID id, String permitNumber, Foreign.PermitKind kind, String trafficFileNumber, String region,
                     UUID licenceId, UUID foreignLicenceId, List<String> classes, LocalDate validFrom,
                     LocalDate validUntil, String status, Instant issuedAt) {
        static PermitDto of(Foreign.TemporaryPermit p, LocalDate today) {
            return new PermitDto(p.id(), p.permitNumber(), p.kind(), p.trafficFileNumber(), p.region(),
                    p.licenceId(), p.foreignLicenceId(), p.classes(), p.validFrom(), p.validUntil(),
                    p.statusOn(today), p.issuedAt());
        }
    }

    record ReasonBody(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record IdpRequest(String printer) {
    }

    record PrintedRequest(@NotBlank String printer) {
    }

    record IdpDto(UUID id, String permitNumber, UUID licenceId, LocalDate issuedOn, LocalDate validUntil,
                  String printer, Foreign.IdpStatus status, Instant printedAt, Instant issuedAt) {
        static IdpDto of(Foreign.InternationalPermit p) {
            return new IdpDto(p.id(), p.permitNumber(), p.licenceId(), p.issuedOn(), p.validUntil(), p.printer(),
                    p.status(), p.printedAt(), p.issuedAt());
        }
    }

    private final ForeignService foreign;
    private final Callers callers;

    ForeignController(ForeignService foreign, Callers callers) {
        this.foreign = foreign;
        this.callers = callers;
    }

    private static ResponseEntity<RequestDto> created(LicenceRequest r) {
        return ResponseEntity.created(URI.create("/v1/licence-requests/" + r.id())).body(RequestDto.of(r));
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneOffset.UTC);
    }

    // ---- country rules (DL-054)

    @PostMapping("/v1/exchange-country-rules")
    @PreAuthorize("hasAuthority('licensing.exchange-country.submit')")
    ResponseEntity<RuleDto> submitRule(@Valid @RequestBody RuleRequest body) {
        Foreign.CountryRule r = foreign.submitRule(body.countryCode(), body.mode(), body.classMap(), body.reason(),
                callers.current());
        return ResponseEntity.created(URI.create("/v1/exchange-country-rules?countryCode=" + r.countryCode()))
                .body(RuleDto.of(r));
    }

    @GetMapping("/v1/exchange-country-rules")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<RuleDto> rules(@RequestParam(required = false) String countryCode) {
        return new Collection<>(foreign.rules(countryCode, callers.current()).stream().map(RuleDto::of).toList());
    }

    @PostMapping("/v1/exchange-country-rules/{ruleId}/approvals")
    @PreAuthorize("hasAuthority('licensing.exchange-country.approve')")
    RuleDto approveRule(@PathVariable UUID ruleId, @Valid @RequestBody DecisionBody body) {
        return RuleDto.of(foreign.decideRule(ruleId, true, body.reason(), callers.current()));
    }

    @PostMapping("/v1/exchange-country-rules/{ruleId}/rejections")
    @PreAuthorize("hasAuthority('licensing.exchange-country.approve')"
            + " or hasAuthority('licensing.exchange-country.submit')")
    RuleDto rejectRule(@PathVariable UUID ruleId, @Valid @RequestBody ReasonBody body) {
        return RuleDto.of(foreign.decideRule(ruleId, false, body.reason(), callers.current()));
    }

    // ---- foreign licences (DL-063) and exchange (DL-054)

    @PostMapping("/v1/foreign-licences")
    @PreAuthorize("hasAuthority('licensing.foreign.record')")
    ResponseEntity<ForeignDto> record(@Valid @RequestBody ForeignRequest body) {
        Foreign.ForeignLicence f = foreign.record(body.toDomain(), callers.current());
        return ResponseEntity.created(URI.create("/v1/foreign-licences?trafficFileNumber=" + f.trafficFileNumber()))
                .body(ForeignDto.of(f));
    }

    /** A person's foreign licences, or the master file by country and expiry (DL-063). */
    @GetMapping("/v1/foreign-licences")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<ForeignDto> foreignLicences(@RequestParam(required = false) String trafficFileNumber,
                                           @RequestParam(required = false) String countryCode,
                                           @RequestParam(required = false) LocalDate expiringBefore,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "100") int limit) {
        List<Foreign.ForeignLicence> found = trafficFileNumber != null
                ? foreign.foreignOf(trafficFileNumber, callers.current())
                : foreign.foreignMasterFile(countryCode, expiringBefore, offset, limit, callers.current());
        return new Collection<>(found.stream().map(ForeignDto::of).toList());
    }

    @PostMapping("/v1/foreign-licences/{foreignLicenceId}/exchanges")
    @PreAuthorize("hasAuthority('licensing.licence.exchange')")
    ResponseEntity<RequestDto> exchange(@PathVariable UUID foreignLicenceId,
                                        @Valid @RequestBody ExchangeRequest body) {
        return created(foreign.requestExchange(foreignLicenceId, new ForeignService.ExchangeSpec(body.region(),
                body.customerCategory(), body.residencyExpiresOn(), body.delivery(), body.address()),
                callers.current()));
    }

    // ---- temporary permits (DL-055)

    @PostMapping("/v1/temporary-permits")
    @PreAuthorize("hasAuthority('licensing.permit.issue')")
    ResponseEntity<RequestDto> permit(@Valid @RequestBody PermitRequest body) {
        return created(foreign.requestPermit(body.kind(), body.trafficFileNumber(), body.region(), body.licenceId(),
                body.foreignLicenceId(), callers.current()));
    }

    /** A person's temporary permits, or the master file by expiry (DL-063). */
    @GetMapping("/v1/temporary-permits")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<PermitDto> permits(@RequestParam(required = false) String trafficFileNumber,
                                  @RequestParam(required = false) LocalDate expiringBefore,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "100") int limit) {
        List<Foreign.TemporaryPermit> found = trafficFileNumber != null
                ? foreign.permitsOf(trafficFileNumber, callers.current())
                : foreign.permitMasterFile(expiringBefore, offset, limit, callers.current());
        LocalDate today = today();
        return new Collection<>(found.stream().map(p -> PermitDto.of(p, today)).toList());
    }

    @PostMapping("/v1/temporary-permits/{permitId}/cancellation")
    @PreAuthorize("hasAuthority('licensing.permit.issue')")
    PermitDto cancelPermit(@PathVariable UUID permitId, @Valid @RequestBody ReasonBody body) {
        return PermitDto.of(foreign.cancelPermit(permitId, body.reason(), callers.current()), today());
    }

    // ---- international permits (DL-056)

    @PostMapping("/v1/licences/{licenceId}/international-permits")
    @PreAuthorize("hasAuthority('licensing.idp.issue')")
    ResponseEntity<RequestDto> idp(@PathVariable UUID licenceId, @Valid @RequestBody IdpRequest body) {
        return created(foreign.requestIdp(licenceId, body.printer(), callers.current()));
    }

    @GetMapping("/v1/licences/{licenceId}/international-permits")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<IdpDto> idps(@PathVariable UUID licenceId) {
        return new Collection<>(foreign.idpsOf(licenceId, callers.current()).stream().map(IdpDto::of).toList());
    }

    @PostMapping("/v1/international-permits/{permitId}/printed")
    @PreAuthorize("hasAuthority('licensing.idp.print')")
    IdpDto printed(@PathVariable UUID permitId, @Valid @RequestBody PrintedRequest body) {
        return IdpDto.of(foreign.markPrinted(permitId, body.printer(), callers.current()));
    }
}
