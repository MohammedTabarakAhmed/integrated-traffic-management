package gov.itms.platform.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Platform-wide translation of framework exceptions into problem+json (CH-053, SEC-022). Services add their own
 * advice (with higher precedence) for domain exceptions. Internal details (stack traces, SQL) never leave the service.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    private final MessageSource messages;

    public ProblemHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        ProblemDetail p = Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message("VALIDATION_FAILED",
                "Some fields are missing or invalid. Correct them and try again."), List.of("SEC-022"));
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler({HandlerMethodValidationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalidRequest(Exception e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message("VALIDATION_FAILED",
                "Some fields are missing or invalid. Correct them and try again."), List.of("SEC-022"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", message("FORBIDDEN",
                "You do not have permission to do this. Ask your administrator if you need access."), List.of("CC-001"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail notFound(NoResourceFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", message("NOT_FOUND", "The requested resource was not found."), null);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message("INTERNAL_ERROR",
                "Something went wrong on our side. Try again later; if it continues, contact support with the reference number."), null);
    }

    private String message(String code, String fallback) {
        return messages.getMessage("problem." + code, null, fallback, LocaleContextHolder.getLocale());
    }
}
