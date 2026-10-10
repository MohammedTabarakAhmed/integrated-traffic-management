package gov.itms.licensing.api;

import gov.itms.licensing.api.ApiModels.Collection;
import gov.itms.licensing.api.ApiModels.LicenceClassDto;
import gov.itms.licensing.api.ApiModels.LicenceDto;
import gov.itms.licensing.api.ApiModels.RequestDto;
import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.application.LicenceStatusService;
import gov.itms.licensing.domain.Licence;
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

/** Cancellation and surrender, suspensions, status enquiry and no-objection certificates (DL-058, DL-059). */
@RestController
class LicenceStatusController {

    record EndRequest(@NotNull Licence.EndGround ground, @Size(max = 200) String reference,
                      @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record SuspensionDto(UUID id, String source, String sourceRef, int ordinal, LocalDate from, LocalDate until,
                         Instant receivedAt) {
        static SuspensionDto of(Records.Suspension s) {
            return new SuspensionDto(s.id(), s.source(), s.sourceRef(), s.ordinal(), s.from(), s.until(),
                    s.receivedAt());
        }
    }

    record StatusDto(String licenceNumber, String status, LocalDate expiresOn, LocalDate suspendedUntil,
                     boolean probationary, List<LicenceClassDto> classes) {
        static StatusDto of(LicenceStatusService.Status s) {
            return new StatusDto(s.licenceNumber(), s.status(), s.expiresOn(), s.suspendedUntil(), s.probationary(),
                    s.classes().stream().map(LicenceClassDto::of).toList());
        }
    }

    record CertificateRequest(@NotBlank @Size(min = 3, max = 200) String purpose,
                              @NotBlank @Size(min = 3, max = 200) String addressee) {
    }

    record CertificateDto(UUID id, String number, String purpose, String addressee, String verificationCode,
                          LocalDate issuedOn, LocalDate validUntil, Instant issuedAt) {
        static CertificateDto of(Records.Certificate c) {
            return new CertificateDto(c.id(), c.number(), c.purpose(), c.addressee(), c.verificationCode(),
                    c.issuedOn(), c.validUntil(), c.issuedAt());
        }
    }

    private final LicenceStatusService status;
    private final LicenceService licences;
    private final Callers callers;

    LicenceStatusController(LicenceStatusService status, LicenceService licences, Callers callers) {
        this.status = status;
        this.licences = licences;
        this.callers = callers;
    }

    @PostMapping("/v1/licences/{licenceId}/cancellations")
    @PreAuthorize("hasAuthority('licensing.licence.cancel')")
    LicenceDto end(@PathVariable UUID licenceId, @Valid @RequestBody EndRequest body) {
        return LicenceDto.of(status.end(licenceId, body.ground(), body.reference(), body.reason(), callers.current()),
                licences.today());
    }

    @GetMapping("/v1/licences/{licenceId}/suspensions")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<SuspensionDto> suspensions(@PathVariable UUID licenceId) {
        return new Collection<>(status.suspensions(licenceId, callers.current()).stream().map(SuspensionDto::of)
                .toList());
    }

    @GetMapping("/v1/licence-status/{licenceNumber}")
    @PreAuthorize("hasAuthority('licensing.licence.status')")
    StatusDto status(@PathVariable String licenceNumber) {
        return StatusDto.of(status.status(licenceNumber, callers.current()));
    }

    @PostMapping("/v1/licences/{licenceId}/no-objection-certificates")
    @PreAuthorize("hasAuthority('licensing.noc.request')")
    ResponseEntity<RequestDto> requestCertificate(@PathVariable UUID licenceId,
                                                  @Valid @RequestBody CertificateRequest body) {
        LicenceRequest r = status.requestCertificate(licenceId, body.purpose(), body.addressee(), callers.current());
        return ResponseEntity.created(URI.create("/v1/licence-requests/" + r.id())).body(RequestDto.of(r));
    }

    @GetMapping("/v1/licences/{licenceId}/no-objection-certificates")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<CertificateDto> certificates(@PathVariable UUID licenceId) {
        return new Collection<>(status.certificates(licenceId, callers.current()).stream().map(CertificateDto::of)
                .toList());
    }

    @GetMapping("/v1/no-objection-certificates/{number}")
    @PreAuthorize("hasAuthority('licensing.licence.status')")
    Map<String, Object> verify(@PathVariable String number, @RequestParam String code) {
        return status.verify(number, code, callers.current());
    }
}
