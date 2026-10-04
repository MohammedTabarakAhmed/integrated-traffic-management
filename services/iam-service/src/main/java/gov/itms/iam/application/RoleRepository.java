package gov.itms.iam.application;

import gov.itms.iam.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Port for role definitions (CC-005). History is kept by the store (DAT-002). */
public interface RoleRepository {

    Optional<Role> find(String code);

    List<Role> findAll();

    void insert(Role role, String actorId, Instant at);

    /** Saves {@code role} only if the stored version is still {@code expectedVersion}; false otherwise. */
    boolean update(Role role, long expectedVersion, String actorId, Instant at);

    /** Every version, oldest first; empty if the role does not exist. */
    List<RoleVersion> history(String code);

    /** One historical version and the period during which it was current. */
    record RoleVersion(Role role, Instant validFrom, Instant validTo, String changedBy) {
    }
}
