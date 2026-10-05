package gov.itms.transaction.api;

import gov.itms.platform.web.Problems;
import gov.itms.transaction.application.EligibilityBlockedException;
import gov.itms.transaction.application.EligibilityChecker;
import gov.itms.transaction.domain.DomainException;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain rule violations to problem+json with a plain-language message in the caller's language (CH-053,
 * CC-010). Message keys are {@code problem.<CODE>} in {@code messages*.properties}; the English text of the exception
 * is the fallback.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainExceptionAdvice {

    private final MessageSource messages;

    DomainExceptionAdvice(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case SEGREGATION_OF_DUTIES, OUT_OF_SCOPE -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String detail = messages.getMessage("problem." + e.code(), e.args(), e.getMessage(),
                LocaleContextHolder.getLocale());
        return Problems.of(status, e.code(), detail, e.requirementRefs());
    }

    /**
     * CC-032, API_SPECIFICATION §3: the customer is told every reason and its next step in plain language; the first
     * block's next step is also given as {@code nextStep}.
     */
    @ExceptionHandler(EligibilityBlockedException.class)
    ProblemDetail eligibilityBlocked(EligibilityBlockedException e) {
        String detail = e.blocks().stream().map(EligibilityChecker.Block::message)
                .collect(Collectors.joining(" "));
        ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), detail, e.requirementRefs());
        problem.setProperty("nextStep", e.blocks().get(0).nextStep());
        problem.setProperty("blocks", e.blocks().stream().map(b -> Map.of("reasonCode", b.reasonCode(),
                "nextStep", b.nextStep(), "message", b.message())).toList());
        return problem;
    }
}
