package gov.itms.device.api;

import gov.itms.device.application.Ports;
import gov.itms.device.domain.DomainException;
import gov.itms.platform.web.Problems;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps device-domain failures to problem+json responses. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainExceptionAdvice {

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return Problems.of(status, e.code(), e.getMessage(), e.requirementRefs());
    }

    @ExceptionHandler(Ports.UnavailableException.class)
    ProblemDetail unavailable(Ports.UnavailableException e) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "VIOLATION_SERVICE_UNAVAILABLE", e.getMessage(),
                List.of("VF-010", "VF-011"));
    }
}
