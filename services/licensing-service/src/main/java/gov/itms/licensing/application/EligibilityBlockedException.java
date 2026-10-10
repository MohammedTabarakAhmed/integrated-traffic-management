package gov.itms.licensing.application;

import gov.itms.licensing.domain.DomainException;
import java.util.List;

/**
 * A licensing transaction refused because holds block it (DL-050, CC-032): 422 {@code ELIGIBILITY_BLOCKED}, with each
 * reason and next step as hold-eligibility-service worded them for the customer. Nothing is quoted or charged.
 */
public class EligibilityBlockedException extends DomainException {

    private final transient List<Ports.Eligibility.Block> blocks;

    public EligibilityBlockedException(String serviceCode, List<Ports.Eligibility.Block> blocks) {
        super(Kind.VALIDATION, "ELIGIBILITY_BLOCKED", "service " + serviceCode + " is blocked: "
                + blocks.stream().map(Ports.Eligibility.Block::reasonCode).toList(), List.of("DL-050", "CC-032"));
        this.blocks = List.copyOf(blocks);
    }

    public List<Ports.Eligibility.Block> blocks() {
        return blocks;
    }
}
