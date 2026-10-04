package gov.itms.transaction.application;

import gov.itms.transaction.domain.ServiceDefinition;
import gov.itms.transaction.domain.ServiceSpec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for service definitions (CC-020) and their history (DAT-002). */
public interface ServiceDefinitionRepository {

    /** All definitions, by code. */
    List<ServiceDefinition> findAll();

    Optional<ServiceDefinition> find(String code);

    /** Reads the definition and locks it until the end of the transaction. */
    Optional<ServiceDefinition> findForUpdate(String code);

    void insert(ServiceDefinition definition);

    /** Saves the next version; fails if the stored version is not {@code definition.version() - 1}. */
    void update(ServiceDefinition definition);

    /** Every version, oldest first. */
    List<ServiceVersion> history(String code);

    /** One historical version and the period during which it was current. */
    record ServiceVersion(ServiceSpec spec, long version, Instant validFrom, Instant validTo, String changedBy,
                          UUID changeId) {
    }
}
