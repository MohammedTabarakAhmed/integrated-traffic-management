package gov.itms.iam.application;

import gov.itms.iam.domain.Permission;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Port for the permission catalogue (read-only at runtime; loaded by migration). */
public interface PermissionRepository {

    List<Permission> findAll();

    /** The subset of {@code codes} that exists in the catalogue. */
    Set<String> existing(Collection<String> codes);
}
