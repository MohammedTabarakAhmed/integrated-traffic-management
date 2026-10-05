package gov.itms.transaction.application;

import gov.itms.transaction.domain.DomainException;
import java.util.List;

/**
 * Submission refused because holds block the service (CC-032): 422 {@code ELIGIBILITY_BLOCKED}, with each reason and
 * next step as hold-eligibility-service worded them for the customer. The transaction stays a Draft.
 */
public class EligibilityBlockedException extends DomainException {

    private final transient List<EligibilityChecker.Block> blocks;

    public EligibilityBlockedException(String serviceCode, List<EligibilityChecker.Block> blocks) {
        super(Kind.VALIDATION, "ELIGIBILITY_BLOCKED", "service " + serviceCode + " cannot be submitted: "
                + blocks.stream().map(EligibilityChecker.Block::reasonCode).toList(), List.of("CC-032"));
        this.blocks = List.copyOf(blocks);
    }

    public List<EligibilityChecker.Block> blocks() {
        return blocks;
    }
}
