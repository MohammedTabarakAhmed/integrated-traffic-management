package gov.itms.roadpermit.api;

import gov.itms.roadpermit.domain.DomainException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class DomainExceptionAdvice {
    @ExceptionHandler(DomainException.class)
    ProblemDetail handle(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(URI.create("urn:itms:road-permit:" + e.code().toLowerCase(java.util.Locale.ROOT)));
        problem.setTitle(e.code());
        problem.setProperty("requirements", e.requirements());
        return problem;
    }
}
