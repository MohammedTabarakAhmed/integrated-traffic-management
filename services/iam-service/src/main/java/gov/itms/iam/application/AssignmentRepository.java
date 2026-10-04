package gov.itms.iam.application;

import gov.itms.iam.domain.RoleAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for role assignments (CC-001). Assignments are never deleted. */
public interface AssignmentRepository {

    void insert(RoleAssignment assignment);

    Optional<RoleAssignment> find(UUID id);

    /** Loads and locks the assignment until the transaction ends, so it cannot be revoked twice concurrently. */
    Optional<RoleAssignment> findForUpdate(UUID id);

    void saveRevocation(RoleAssignment assignment);

    /** Newest first. Every filter is optional ({@code null} = any). */
    List<RoleAssignment> search(String subjectId, String roleCode, RoleAssignment.Status status);
}
