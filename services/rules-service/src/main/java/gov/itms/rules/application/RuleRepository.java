package gov.itms.rules.application;

import gov.itms.rules.domain.DecisionRecord;
import gov.itms.rules.domain.RuleVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rule versions and the decision log (CC-050..CC-053). */
public interface RuleRepository {

    int nextVersionNo(String ruleSetCode);

    /** Serialises changes to one rule set. */
    void lockRuleSet(String ruleSetCode);

    void insert(RuleVersion version);

    Optional<RuleVersion> find(UUID id);

    Optional<RuleVersion> findForUpdate(UUID id);

    /** Every version of a rule set, oldest first. */
    List<RuleVersion> byRuleSet(String ruleSetCode);

    void saveDecision(RuleVersion version);

    void saveEnd(RuleVersion version);

    void insertDecision(DecisionRecord decision);

    Optional<DecisionRecord> findDecision(UUID id);

    /** Logged decisions of a rule set, newest first, from {@code since}; at most {@code limit}. */
    List<DecisionRecord> decisions(String ruleSetCode, Instant since, int limit);
}
