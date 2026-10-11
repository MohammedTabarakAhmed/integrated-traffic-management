package gov.itms.reporting.api;

import gov.itms.platform.web.Problems;
import gov.itms.reporting.domain.DomainException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps refused report requests to problem+json with a plain-language message in the caller's language (CH-053, CC-010):
 * keys {@code problem.<CODE>} in {@code messages*.properties}, the exception's English text as fallback.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainExceptionAdvice {

    private final MessageSource messages;

    DomainExceptionAdvice(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail handle(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        String detail = messages.getMessage("problem." + e.code(), null, e.getMessage(),
                LocaleContextHolder.getLocale());
        return Problems.of(status, e.code(), detail, e.requirements());
    }
}
