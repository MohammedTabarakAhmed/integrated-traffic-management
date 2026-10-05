package gov.itms.transaction.application;

import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.Subject;
import java.util.List;

/**
 * Port to hold-eligibility-service's eligibility check (CC-032; ADR-034), called through its API with this service's
 * own account, never through its database.
 */
public interface EligibilityChecker {

    /** One reason the service is blocked, as the customer may be told it (reason code, next step, plain text). */
    record Block(String reasonCode, String nextStep, String message) {
    }

    /**
     * The reasons {@code serviceCode} is blocked for {@code subjects} today; empty when eligible. Throws a
     * {@code DomainException} of kind {@code UNAVAILABLE} when the answer cannot be obtained: the submission is then
     * refused, never let through (fail closed).
     */
    List<Block> check(String serviceCode, List<Subject> subjects, Channel channel);
}
