package gov.itms.platform.web;

import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Builds the ITMS problem+json shape (API_SPECIFICATION §3, CH-053). */
public final class Problems {

    private static final String TYPE_BASE = "urn:itms:problem:";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail, List<String> requirementRefs) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        if (requirementRefs != null && !requirementRefs.isEmpty()) {
            problem.setProperty("requirementRefs", requirementRefs);
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
