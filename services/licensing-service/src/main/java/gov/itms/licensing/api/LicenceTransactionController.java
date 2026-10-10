package gov.itms.licensing.api;

import gov.itms.licensing.api.ApiModels.AutoRenewalDto;
import gov.itms.licensing.api.ApiModels.CardDto;
import gov.itms.licensing.api.ApiModels.ChangeRequest;
import gov.itms.licensing.api.ApiModels.Collection;
import gov.itms.licensing.api.ApiModels.ConsentRequest;
import gov.itms.licensing.api.ApiModels.EyeTestDto;
import gov.itms.licensing.api.ApiModels.EyeTestRequest;
import gov.itms.licensing.api.ApiModels.RenewRequest;
import gov.itms.licensing.api.ApiModels.ReplacementRequest;
import gov.itms.licensing.api.ApiModels.RequestDto;
import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.application.LicenceTransactions;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Renewal, automatic renewal, replacement, change of details, cards and eye tests (DL-007, DL-051…DL-065). */
@RestController
class LicenceTransactionController {

    private final LicenceTransactions transactions;
    private final LicenceService licences;
    private final Callers callers;

    LicenceTransactionController(LicenceTransactions transactions, LicenceService licences, Callers callers) {
        this.transactions = transactions;
        this.licences = licences;
        this.callers = callers;
    }

    private static ResponseEntity<RequestDto> created(LicenceRequest r) {
        return ResponseEntity.created(URI.create("/v1/licence-requests/" + r.id())).body(RequestDto.of(r));
    }

    @PostMapping("/v1/licences/{licenceId}/renewals")
    @PreAuthorize("hasAuthority('licensing.licence.renew')")
    ResponseEntity<RequestDto> renew(@PathVariable UUID licenceId, @Valid @RequestBody RenewRequest body) {
        return created(transactions.requestRenewal(licenceId, body.toDomain(), callers.current()));
    }

    @PostMapping("/v1/licences/{licenceId}/replacements")
    @PreAuthorize("hasAuthority('licensing.licence.replace')")
    ResponseEntity<RequestDto> replace(@PathVariable UUID licenceId, @Valid @RequestBody ReplacementRequest body) {
        return created(transactions.requestReplacement(licenceId, body.reason(), body.delivery(), body.address(),
                callers.current()));
    }

    @PostMapping("/v1/licences/{licenceId}/changes")
    @PreAuthorize("hasAuthority('licensing.licence.change')")
    ResponseEntity<RequestDto> change(@PathVariable UUID licenceId, @Valid @RequestBody ChangeRequest body) {
        return created(transactions.requestChange(licenceId, body.toDomain(), callers.current()));
    }

    @GetMapping("/v1/licences/{licenceId}/cards")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<CardDto> cards(@PathVariable UUID licenceId) {
        return new Collection<>(licences.cards(licenceId, callers.current()).stream().map(CardDto::of).toList());
    }

    // ---- eye tests (DL-007)

    @PostMapping("/v1/eye-tests")
    @PreAuthorize("hasAuthority('licensing.eye-test.record')")
    ResponseEntity<EyeTestDto> eyeTest(@Valid @RequestBody EyeTestRequest body) {
        Records.EyeTest t = transactions.recordEyeTest(body.toDomain(), callers.current());
        return ResponseEntity.created(URI.create("/v1/eye-tests?trafficFileNumber=" + t.trafficFileNumber()))
                .body(EyeTestDto.of(t));
    }

    @GetMapping("/v1/eye-tests")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<EyeTestDto> eyeTests(@RequestParam String trafficFileNumber) {
        return new Collection<>(transactions.eyeTests(trafficFileNumber, callers.current()).stream()
                .map(EyeTestDto::of).toList());
    }

    // ---- automatic renewal (DL-065)

    @PostMapping("/v1/licences/{licenceId}/auto-renewal")
    @PreAuthorize("hasAuthority('licensing.auto-renewal.manage')")
    AutoRenewalDto consent(@PathVariable UUID licenceId, @Valid @RequestBody ConsentRequest body) {
        return AutoRenewalDto.of(transactions.consent(licenceId, body.paymentMethodRef(), callers.current()));
    }

    @PostMapping("/v1/licences/{licenceId}/auto-renewal/withdrawal")
    @PreAuthorize("hasAuthority('licensing.auto-renewal.manage')")
    AutoRenewalDto withdraw(@PathVariable UUID licenceId) {
        return AutoRenewalDto.of(transactions.withdraw(licenceId, callers.current()));
    }

    @GetMapping("/v1/licences/{licenceId}/auto-renewal")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    AutoRenewalDto autoRenewal(@PathVariable UUID licenceId) {
        return AutoRenewalDto.of(transactions.autoRenewal(licenceId, callers.current()).orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "CONSENT_NOT_FOUND",
                        "automatic renewal was never switched on for this licence", List.of("DL-065"))));
    }
}
