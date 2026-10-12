package gov.itms.examination.api;

import gov.itms.examination.domain.DomainException;
import gov.itms.platform.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures to problem+json with the platform's code and requirement references (API_SPECIFICATION §3). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainExceptionAdvice {

    @ExceptionHandler(DomainException.class)
    ProblemDetail handle(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case OUT_OF_SCOPE -> HttpStatus.FORBIDDEN;
        };
        return Problems.of(status, e.code(), e.getMessage(), e.requirements());
    }
}
