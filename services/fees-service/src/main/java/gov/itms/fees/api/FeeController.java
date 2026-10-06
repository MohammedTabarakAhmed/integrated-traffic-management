package gov.itms.fees.api;

import gov.itms.fees.api.ApiModels.Collection;
import gov.itms.fees.api.ApiModels.DecisionRequest;
import gov.itms.fees.api.ApiModels.FeeVersionDto;
import gov.itms.fees.api.ApiModels.QuoteDto;
import gov.itms.fees.api.ApiModels.QuoteRequest;
import gov.itms.fees.api.ApiModels.RejectRequest;
import gov.itms.fees.api.ApiModels.SubmitVersionRequest;
import gov.itms.fees.application.Caller;
import gov.itms.fees.application.FeeService;
import gov.itms.fees.application.QuoteService;
import gov.itms.fees.domain.FeeVersion;
import gov.itms.fees.domain.Quote;
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

/** Fee versions and quotes of {@code fees-service.v1.yaml} (CC-040..CC-047). */
@RestController
class FeeController {

    private final FeeService fees;
    private final QuoteService quotes;
    private final Callers callers;

    FeeController(FeeService fees, QuoteService quotes, Callers callers) {
        this.fees = fees;
        this.quotes = quotes;
        this.callers = callers;
    }

    @PostMapping("/v1/fee-versions")
    @PreAuthorize("hasAuthority('fees.version.submit')")
    ResponseEntity<FeeVersionDto> submit(@Valid @RequestBody SubmitVersionRequest body) {
        FeeVersion v = fees.submit(body.toDomain(), body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/fee-versions/" + v.id())).body(FeeVersionDto.of(v));
    }

    @GetMapping("/v1/fee-versions")
    @PreAuthorize("hasAuthority('fees.version.read')")
    Collection<FeeVersionDto> list(@RequestParam(required = false) String feeCode,
                                   @RequestParam(required = false) String serviceCode,
                                   @RequestParam(required = false) FeeVersion.Status status) {
        Caller caller = callers.current();
        if (feeCode != null) {
            return new Collection<>(fees.byFee(feeCode, caller).stream()
                    .filter(v -> status == null || v.status() == status).map(FeeVersionDto::of).toList());
        }
        if (serviceCode == null) {
            throw gov.itms.fees.domain.Codes.invalid("feeCode or serviceCode");
        }
        return new Collection<>(fees.byService(serviceCode, status, caller).stream().map(FeeVersionDto::of).toList());
    }

    @GetMapping("/v1/fee-versions/{id}")
    @PreAuthorize("hasAuthority('fees.version.read')")
    FeeVersionDto get(@PathVariable UUID id) {
        return FeeVersionDto.of(fees.get(id, callers.current()));
    }

    @PostMapping("/v1/fee-versions/{id}/approvals")
    @PreAuthorize("hasAuthority('fees.version.approve')")
    FeeVersionDto approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return FeeVersionDto.of(fees.approve(id, body.reason(), callers.current()));
    }

    /** A checker rejects; the maker may withdraw their own pending version. */
    @PostMapping("/v1/fee-versions/{id}/rejections")
    @PreAuthorize("hasAuthority('fees.version.approve') or hasAuthority('fees.version.submit')")
    FeeVersionDto reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest body) {
        return FeeVersionDto.of(fees.reject(id, body.reason(), callers.current()));
    }

    @PostMapping("/v1/quotes")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<QuoteDto> quote(@Valid @RequestBody QuoteRequest body) {
        Quote q = quotes.create(body.toDomain(quotes.today()), callers.current());
        return ResponseEntity.created(URI.create("/v1/quotes/" + q.id())).body(QuoteDto.of(q, quotes.now()));
    }

    @GetMapping("/v1/quotes/{id}")
    @PreAuthorize("isAuthenticated()")
    QuoteDto getQuote(@PathVariable UUID id) {
        return QuoteDto.of(quotes.get(id), quotes.now());
    }
}
