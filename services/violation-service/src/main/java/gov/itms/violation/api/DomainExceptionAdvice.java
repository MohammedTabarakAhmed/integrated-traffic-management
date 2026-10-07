package gov.itms.violation.api;

import gov.itms.platform.web.Problems;
import gov.itms.violation.domain.DomainException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;

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
            case OUT_OF_SCOPE -> HttpStatus.FORBIDDEN;
            case SEGREGATION_OF_DUTIES -> HttpStatus.FORBIDDEN;
        };
        String detail = messages.getMessage("problem." + e.code(), e.args(), e.getMessage(),
                LocaleContextHolder.getLocale());
        return Problems.of(status, e.code(), detail, e.requirementRefs());
    }

    /** payment-service or hold-eligibility-service did not answer: nothing was changed. */
    @ExceptionHandler(gov.itms.violation.application.Ports.UnavailableException.class)
    ProblemDetail unavailable(gov.itms.violation.application.Ports.UnavailableException e) {
        String detail = messages.getMessage("problem." + e.code(), null, e.getMessage(),
                LocaleContextHolder.getLocale());
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, e.code(), detail, List.of("VF-070", "VF-032"));
    }

    /** Two changes to the same record crossed; the second must re-read (DAT-002). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrent(OptimisticLockingFailureException e) {
        String detail = messages.getMessage("problem.VERSION_MISMATCH", new Object[] {"?"},
                "the record changed concurrently", LocaleContextHolder.getLocale());
        return Problems.of(HttpStatus.CONFLICT, "VERSION_MISMATCH", detail, List.of("DAT-002"));
    }
}
