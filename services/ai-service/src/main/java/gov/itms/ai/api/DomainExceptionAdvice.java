package gov.itms.ai.api;

import gov.itms.ai.domain.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * Global exception handler mapping domain exceptions to RFC 9457 problem responses.
 */
@RestControllerAdvice
public class DomainExceptionAdvice {

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomainException(DomainException ex) {
        HttpStatus status = switch (ex.kind()) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case OUT_OF_SCOPE -> HttpStatus.FORBIDDEN;
            case SEGREGATION_OF_DUTIES -> HttpStatus.FORBIDDEN;
            case MODEL_UNAVAILABLE, UNAVAILABLE, KILL_SWITCH -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setType(URI.create("https://itms.gov/probs/" + ex.code()));
        problem.setTitle(ex.code());
        problem.setProperty("requirementRefs", ex.requirementRefs());
        if (ex.args() != null && ex.args().length > 0) {
            problem.setProperty("args", ex.args());
        }
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://itms.gov/probs/ILLEGAL_STATE"));
        problem.setTitle("ILLEGAL_STATE");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://itms.gov/probs/INVALID_ARGUMENT"));
        problem.setTitle("INVALID_ARGUMENT");
        return problem;
    }
}