package gov.itms.transaction.infrastructure;

import gov.itms.platform.client.ServiceCallException;
import gov.itms.transaction.application.EligibilityChecker;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.Subject;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls {@code POST /v1/eligibility-checks} of hold-eligibility-service (CC-032; ADR-034) with this service's own
 * account ({@code ServiceClients}). Anything but a complete 200 answer for the service asked about is "unavailable":
 * the submission is refused with 503 {@code ELIGIBILITY_UNAVAILABLE}, never let through.
 */
class RemoteEligibilityChecker implements EligibilityChecker {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteEligibilityChecker.class);

    private final RestClient client;

    RemoteEligibilityChecker(RestClient client) {
        this.client = client;
    }

    /** The answer of hold-eligibility-service ({@code Eligibility} in its OpenAPI). */
    record Answer(String serviceCode, Boolean eligible, List<Block> blocks) {
    }

    @Override
    public List<Block> check(String serviceCode, List<Subject> subjects, String transactionReference,
                             Channel channel) {
        Answer answer;
        try {
            answer = client.post().uri("/v1/eligibility-checks")
                    .header("X-Channel", channel.name())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("serviceCode", serviceCode, "transactionReference", transactionReference,
                            "subjects", subjects.stream().map(s -> Map.of("type", s.type().name(), "id", s.id()))
                                    .toList()))
                    .retrieve()
                    .body(Answer.class);
        } catch (RestClientResponseException e) {
            LOG.warn("eligibility check for {} refused by hold-eligibility-service: HTTP {}", serviceCode,
                    e.getStatusCode().value());
            throw unavailable();
        } catch (RestClientException | ServiceCallException e) {
            LOG.warn("eligibility check for {} failed: {}", serviceCode, e.getClass().getSimpleName());
            throw unavailable();
        }
        if (answer == null || !serviceCode.equals(answer.serviceCode()) || answer.eligible() == null
                || answer.blocks() == null || answer.eligible() != answer.blocks().isEmpty()
                || answer.blocks().stream().anyMatch(b -> b == null || b.reasonCode() == null || b.nextStep() == null
                        || b.message() == null || b.message().isBlank())) {
            LOG.warn("eligibility check for {}: incomplete answer from hold-eligibility-service", serviceCode);
            throw unavailable();
        }
        return List.copyOf(answer.blocks());
    }

    private static DomainException unavailable() {
        return new DomainException(DomainException.Kind.UNAVAILABLE, "ELIGIBILITY_UNAVAILABLE",
                "eligibility cannot be checked right now", List.of("CC-032"));
    }
}
