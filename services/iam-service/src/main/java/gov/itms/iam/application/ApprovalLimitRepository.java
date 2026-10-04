package gov.itms.iam.application;

import gov.itms.iam.domain.ApprovalLimit;
import java.time.Instant;
import java.util.List;

/** Port for approval limits per role (CC-003). History is kept by the store (DAT-002). */
public interface ApprovalLimitRepository {

    /** The role's current limits, ordered by limit type then currency; empty if it has none. */
    List<ApprovalLimit> forRole(String roleCode);

    /**
     * Makes {@code limits} the role's complete set: removes the others, changes amounts that differ and adds new
     * ones. Must run in the same transaction as the role version change that authorises it.
     */
    void replace(String roleCode, List<ApprovalLimit> limits, String actorId, Instant at);
}
