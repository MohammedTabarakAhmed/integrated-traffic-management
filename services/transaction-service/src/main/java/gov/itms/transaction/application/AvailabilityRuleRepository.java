package gov.itms.transaction.application;

import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.AvailabilityRule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for availability rules (CC-026). */
public interface AvailabilityRuleRepository {

    /** Rules of one service, ended ones included, by start date. */
    List<AvailabilityRule> findByService(String serviceCode);

    /** Rules of every service. */
    List<AvailabilityRule> findAll();

    Optional<AvailabilityRule> find(UUID id);

    void insert(AvailabilityRule rule, Actor createdBy, Instant now);

    /** Stores the new end date and the change that ended the rule. */
    void saveEnd(AvailabilityRule rule);
}
