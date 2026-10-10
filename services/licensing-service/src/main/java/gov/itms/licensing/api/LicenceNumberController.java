package gov.itms.licensing.api;

import gov.itms.licensing.api.ApiModels.Collection;
import gov.itms.licensing.api.ApiModels.DecisionRequest;
import gov.itms.licensing.api.ApiModels.NumberRangeDto;
import gov.itms.licensing.api.ApiModels.NumberReportDto;
import gov.itms.licensing.api.ApiModels.RejectRequest;
import gov.itms.licensing.api.ApiModels.SubmitNumberRangeRequest;
import gov.itms.licensing.api.ApiModels.UsedNumberDto;
import gov.itms.licensing.application.LicenceNumberService;
import gov.itms.licensing.domain.LicenceNumberRange;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Licence number ranges and their report of {@code licensing-service.v1.yaml} (DL-061). */
@RestController
class LicenceNumberController {

    private final LicenceNumberService numbers;
    private final Callers callers;

    LicenceNumberController(LicenceNumberService numbers, Callers callers) {
        this.numbers = numbers;
        this.callers = callers;
    }

    @PostMapping("/v1/licence-number-ranges")
    @PreAuthorize("hasAuthority('licensing.number-range.submit')")
    ResponseEntity<NumberRangeDto> submit(@Valid @RequestBody SubmitNumberRangeRequest body) {
        LicenceNumberRange r = numbers.submit(body.toDomain(), body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/licence-number-ranges/" + r.id())).body(NumberRangeDto.of(r));
    }

    @GetMapping("/v1/licence-number-ranges")
    @PreAuthorize("hasAuthority('licensing.number-range.read')")
    Collection<NumberRangeDto> list(@RequestParam(required = false) String region,
                                    @RequestParam(required = false) LicenceNumberRange.Status status) {
        return new Collection<>(numbers.list(region, callers.current()).stream()
                .filter(r -> status == null || r.status() == status).map(NumberRangeDto::of).toList());
    }

    @GetMapping("/v1/licence-number-ranges/{rangeId}")
    @PreAuthorize("hasAuthority('licensing.number-range.read')")
    NumberRangeDto get(@PathVariable UUID rangeId) {
        return NumberRangeDto.of(numbers.get(rangeId, callers.current()));
    }

    @PostMapping("/v1/licence-number-ranges/{rangeId}/approvals")
    @PreAuthorize("hasAuthority('licensing.number-range.approve')")
    NumberRangeDto approve(@PathVariable UUID rangeId, @Valid @RequestBody DecisionRequest body) {
        return NumberRangeDto.of(numbers.approve(rangeId, body.reason(), callers.current()));
    }

    /** A checker rejects; the maker may withdraw their own pending range. */
    @PostMapping("/v1/licence-number-ranges/{rangeId}/rejections")
    @PreAuthorize("hasAuthority('licensing.number-range.approve') or hasAuthority('licensing.number-range.submit')")
    NumberRangeDto reject(@PathVariable UUID rangeId, @Valid @RequestBody RejectRequest body) {
        return NumberRangeDto.of(numbers.reject(rangeId, body.reason(), callers.current()));
    }

    @GetMapping("/v1/licence-number-ranges/{rangeId}/numbers")
    @PreAuthorize("hasAuthority('licensing.number-range.read')")
    Collection<UsedNumberDto> used(@PathVariable UUID rangeId, @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "100") int limit) {
        return new Collection<>(numbers.used(rangeId, offset, limit, callers.current()).stream()
                .map(UsedNumberDto::of).toList());
    }

    @GetMapping("/v1/licence-number-reports/{region}")
    @PreAuthorize("hasAuthority('licensing.number-range.read')")
    NumberReportDto report(@PathVariable String region) {
        return NumberReportDto.of(numbers.report(region, callers.current()));
    }
}
