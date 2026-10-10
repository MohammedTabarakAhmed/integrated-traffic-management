package gov.itms.licensing.api;

import gov.itms.licensing.api.ApiModels.AddConditionRequest;
import gov.itms.licensing.api.ApiModels.Collection;
import gov.itms.licensing.api.ApiModels.HistoryDto;
import gov.itms.licensing.api.ApiModels.IssueRequest;
import gov.itms.licensing.api.ApiModels.LicenceDto;
import gov.itms.licensing.api.ApiModels.RejectRequest;
import gov.itms.licensing.api.ApiModels.RequestDto;
import gov.itms.licensing.api.ApiModels.TransferDto;
import gov.itms.licensing.api.ApiModels.TransferRequest;
import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.domain.ExaminationTransfer;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceRequest;
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

/** Examination transfers, licence issue, licences and their conditions of {@code licensing-service.v1.yaml}. */
@RestController
class LicenceController {

    private final LicenceService licences;
    private final Callers callers;

    LicenceController(LicenceService licences, Callers callers) {
        this.licences = licences;
        this.callers = callers;
    }

    // ---- examination transfers (DL-036)

    @PostMapping("/v1/examination-transfers")
    @PreAuthorize("hasAuthority('licensing.transfer.receive')")
    ResponseEntity<TransferDto> receive(@Valid @RequestBody TransferRequest body) {
        ExaminationTransfer t = licences.receive(body.toDomain(), callers.current());
        return ResponseEntity.created(URI.create("/v1/examination-transfers/" + t.id())).body(TransferDto.of(t));
    }

    @GetMapping("/v1/examination-transfers")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<TransferDto> transfers(@RequestParam String trafficFileNumber) {
        return new Collection<>(licences.transfers(trafficFileNumber, callers.current()).stream()
                .map(TransferDto::of).toList());
    }

    @GetMapping("/v1/examination-transfers/{transferId}")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    TransferDto transfer(@PathVariable UUID transferId) {
        return TransferDto.of(licences.transfer(transferId, callers.current()));
    }

    @PostMapping("/v1/examination-transfers/{transferId}/reversal")
    @PreAuthorize("hasAuthority('licensing.transfer.reverse')")
    TransferDto reverse(@PathVariable UUID transferId, @Valid @RequestBody RejectRequest body) {
        return TransferDto.of(licences.reverse(transferId, body.reason(), callers.current()));
    }

    // ---- new licence (DL-050)

    @PostMapping("/v1/licence-issues")
    @PreAuthorize("hasAuthority('licensing.licence.issue')")
    ResponseEntity<RequestDto> issue(@Valid @RequestBody IssueRequest body) {
        LicenceRequest r = licences.requestIssue(body.toDomain(licences.today()), callers.current());
        return ResponseEntity.created(URI.create("/v1/licence-requests/" + r.id())).body(RequestDto.of(r));
    }

    @GetMapping("/v1/licence-requests/{requestId}")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    RequestDto request(@PathVariable UUID requestId) {
        return RequestDto.of(licences.request(requestId, callers.current()));
    }

    // ---- licences (DL-021…023)

    @GetMapping("/v1/licences")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<LicenceDto> list(@RequestParam(required = false) String trafficFileNumber,
                                @RequestParam(required = false) String licenceNumber) {
        if ((trafficFileNumber == null) == (licenceNumber == null)) {
            throw gov.itms.licensing.domain.DomainException.validation("QUERY_INVALID",
                    "give either trafficFileNumber or licenceNumber", List.of("DL-021"));
        }
        List<Licence> found = licenceNumber != null
                ? List.of(licences.licenceByNumber(licenceNumber, callers.current()))
                : licences.licencesOf(trafficFileNumber, callers.current());
        return new Collection<>(found.stream().map(l -> LicenceDto.of(l, licences.today())).toList());
    }

    @GetMapping("/v1/licences/{licenceId}")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    LicenceDto get(@PathVariable UUID licenceId) {
        return LicenceDto.of(licences.licence(licenceId, callers.current()), licences.today());
    }

    @GetMapping("/v1/licences/{licenceId}/history")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<HistoryDto> history(@PathVariable UUID licenceId) {
        return new Collection<>(licences.history(licenceId, callers.current()).stream().map(HistoryDto::of).toList());
    }

    @GetMapping("/v1/licence-condition-codes")
    @PreAuthorize("hasAuthority('licensing.licence.read')")
    Collection<String> conditionCodes() {
        return new Collection<>(licences.conditionCodes());
    }

    @PostMapping("/v1/licences/{licenceId}/conditions")
    @PreAuthorize("hasAuthority('licensing.licence.condition')")
    LicenceDto addCondition(@PathVariable UUID licenceId, @Valid @RequestBody AddConditionRequest body) {
        Licence l = licences.addCondition(licenceId, new Licence.Condition(body.code(), body.note(),
                licences.today()), body.reason(), callers.current());
        return LicenceDto.of(l, licences.today());
    }

    @PostMapping("/v1/licences/{licenceId}/conditions/{code}/removal")
    @PreAuthorize("hasAuthority('licensing.licence.condition')")
    LicenceDto removeCondition(@PathVariable UUID licenceId, @PathVariable String code,
                               @Valid @RequestBody RejectRequest body) {
        return LicenceDto.of(licences.removeCondition(licenceId, code, body.reason(), callers.current()),
                licences.today());
    }
}
