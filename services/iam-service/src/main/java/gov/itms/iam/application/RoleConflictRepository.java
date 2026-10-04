package gov.itms.iam.application;

import gov.itms.iam.domain.RoleConflict;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for conflicting-role pairs (CC-002). Pairs are never deleted. */
public interface RoleConflictRepository {

    void insert(RoleConflict conflict);

    /** Loads and locks the pair until the transaction ends, so it cannot be retired twice concurrently. */
    Optional<RoleConflict> findForUpdate(UUID id);

    void saveRetirement(RoleConflict conflict);

    /** Newest first. {@code null} = any status. */
    List<RoleConflict> search(RoleConflict.Status status);
}
