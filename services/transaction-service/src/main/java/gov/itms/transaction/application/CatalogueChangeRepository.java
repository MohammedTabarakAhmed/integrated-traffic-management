package gov.itms.transaction.application;

import gov.itms.transaction.domain.CatalogueChange;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for maker-checker catalogue changes (CC-114). */
public interface CatalogueChangeRepository {

    /**
     * Stores a new pending change. Throws a CONFLICT {@code CHANGE_ALREADY_PENDING} domain exception if another change
     * to the same service is already pending.
     */
    void insert(CatalogueChange change);

    Optional<CatalogueChange> find(UUID id);

    /** Reads the change and locks it until the end of the transaction. */
    Optional<CatalogueChange> findForDecision(UUID id);

    /** Newest first; all statuses when {@code status} is null. */
    List<CatalogueChange> findByStatus(CatalogueChange.Status status);

    boolean pendingExistsFor(String serviceCode);

    void saveDecision(CatalogueChange change);
}
