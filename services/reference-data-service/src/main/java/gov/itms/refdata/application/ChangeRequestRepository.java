package gov.itms.refdata.application;

import gov.itms.refdata.domain.ChangeRequest;
import gov.itms.refdata.domain.ChangeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for maker-checker change requests (CC-114). */
public interface ChangeRequestRepository {

    void insert(ChangeRequest change);

    Optional<ChangeRequest> find(UUID id);

    /** Loads the change and locks it until the transaction ends, so two checkers cannot decide it twice. */
    Optional<ChangeRequest> findForDecision(UUID id);

    void saveDecision(ChangeRequest change);

    /** Newest first; {@code status == null} returns all. */
    List<ChangeRequest> findByStatus(ChangeStatus status);

    /** True if a pending change already targets the same list/item/region. */
    boolean pendingExistsFor(String listCode, String itemCode, String regionCode);
}
