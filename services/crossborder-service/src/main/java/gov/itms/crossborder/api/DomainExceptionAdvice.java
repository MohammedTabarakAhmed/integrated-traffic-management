package gov.itms.crossborder.api;

import gov.itms.crossborder.domain.DomainException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class DomainExceptionAdvice {
    @ExceptionHandler(DomainException.class)
    ProblemDetail handle(DomainException e) {
        HttpStatus status=switch(e.kind()) { case VALIDATION -> HttpStatus.BAD_REQUEST; case NOT_FOUND -> HttpStatus.NOT_FOUND; case CONFLICT -> HttpStatus.CONFLICT; };
        ProblemDetail p=ProblemDetail.forStatusAndDetail(status,e.getMessage());
        p.setType(URI.create("urn:itms:crossborder:"+e.code().toLowerCase(java.util.Locale.ROOT)));
        p.setTitle(e.code()); p.setProperty("requirements",e.requirements()); return p;
    }
}
