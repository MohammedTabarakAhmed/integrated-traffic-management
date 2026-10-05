package gov.itms.hold.api;

import gov.itms.hold.api.ApiModels.EligibilityBlockDto;
import gov.itms.hold.api.ApiModels.EligibilityCheckRequest;
import gov.itms.hold.api.ApiModels.EligibilityDto;
import gov.itms.hold.application.EligibilityService;
import gov.itms.hold.domain.Eligibility;
import gov.itms.hold.domain.EligibilityPolicy;
import gov.itms.hold.domain.EligibilityPolicy.Block;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Eligibility checks of {@code hold-eligibility-service.v1.yaml} (CC-032). The answer is a query result (200 with
 * {@code eligible}); the service that takes the submission refuses it. Customer texts come from
 * {@code eligibility.reason.*} and {@code eligibility.next-step.*} in the caller's language (CC-010, CH-053).
 */
@RestController
@RequestMapping("/v1/eligibility-checks")
class EligibilityController {

    private final EligibilityService service;
    private final Callers callers;
    private final MessageSource messages;

    EligibilityController(EligibilityService service, Callers callers, MessageSource messages) {
        this.service = service;
        this.callers = callers;
        this.messages = messages;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('eligibility.check')")
    EligibilityDto check(@Valid @RequestBody EligibilityCheckRequest body) {
        Eligibility result = service.check(body.serviceCode(), body.toDomain(), callers.current());
        Locale locale = LocaleContextHolder.getLocale();
        List<EligibilityBlockDto> blocks = result.blocks().stream().map(b -> dto(b, locale)).toList();
        return new EligibilityDto(result.serviceCode(), result.checkedOn(), result.eligible(), blocks);
    }

    private EligibilityBlockDto dto(Block block, Locale locale) {
        String reason = messages.getMessage(EligibilityPolicy.REASON_KEY + block.reasonCode(), null, locale);
        String step = messages.getMessage(EligibilityPolicy.NEXT_STEP_KEY + block.nextStep(), null, locale);
        return new EligibilityBlockDto(block.reasonCode(), block.nextStep(), reason + " " + step);
    }
}
