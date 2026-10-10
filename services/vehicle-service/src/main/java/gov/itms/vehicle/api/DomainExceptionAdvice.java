package gov.itms.vehicle.api;

import gov.itms.platform.web.Problems;
import gov.itms.vehicle.application.EligibilityBlockedException;
import gov.itms.vehicle.application.Ports;
import gov.itms.vehicle.domain.DomainException;
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
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * Holds block the renewal (VL-011, VF-023): {@code detail} joins every reason in plain language and {@code blocks}
     * lists them; the first block's next step is also given as {@code nextStep}.
     */
    @ExceptionHandler(EligibilityBlockedException.class)
    ProblemDetail eligibilityBlocked(EligibilityBlockedException e) {
        String detail = e.blocks().stream().map(Ports.Eligibility.Block::message).collect(Collectors.joining(" "));
        ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), detail, e.requirementRefs());
        problem.setProperty("nextStep", e.blocks().get(0).nextStep());
        problem.setProperty("blocks", e.blocks().stream().map(b -> Map.of("reasonCode", b.reasonCode(),
                "nextStep", b.nextStep(), "message", b.message())).toList());
        return problem;
    }

    /** An insurer, fees-service or payment-service did not answer: nothing was changed (ICD-02 fails closed). */
    @ExceptionHandler(gov.itms.vehicle.application.Ports.UnavailableException.class)
    ProblemDetail unavailable(gov.itms.vehicle.application.Ports.UnavailableException e) {
        String detail = messages.getMessage("problem." + e.code(), null, e.getMessage(),
                LocaleContextHolder.getLocale());
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, e.code(), detail, List.of("VL-024", "VL-011"));
    }

    /** Two changes to the same record crossed; the second must re-read (DAT-002). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrent(OptimisticLockingFailureException e) {
        String detail = messages.getMessage("problem.VERSION_MISMATCH", new Object[] {"?"},
                "the record changed concurrently", LocaleContextHolder.getLocale());
        return Problems.of(HttpStatus.CONFLICT, "VERSION_MISMATCH", detail, List.of("DAT-002"));
    }
}
