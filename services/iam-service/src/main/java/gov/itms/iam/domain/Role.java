package gov.itms.iam.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A named set of permissions, administered at runtime without code changes (CC-005). Roles are never deleted; an
 * inactive role grants nothing. Each change creates a new version (DAT-002).
 */
public record Role(String code, BilingualName name, String description, boolean active,
                   SortedSet<String> permissions, long version) {

    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_PERMISSIONS = 500;

    public Role {
        Codes.roleCode(code);
        if (name == null) {
            throw DomainException.validation("NAME_REQUIRED", "name is required in both languages", "name");
        }
        description = cleanDescription(description);
        permissions = cleanPermissions(permissions);
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    public static Role create(String code, BilingualName name, String description, Collection<String> permissions) {
        return new Role(code, name, description, true, permissions == null ? null : new TreeSet<>(permissions), 1);
    }

    /** The next version of this role with a new definition (full replacement, CC-005). */
    public Role redefine(BilingualName newName, String newDescription, boolean newActive,
                         Collection<String> newPermissions) {
        return new Role(code, newName, newDescription, newActive,
                newPermissions == null ? null : new TreeSet<>(newPermissions), version + 1);
    }

    private static String cleanDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip();
        if (clean.length() > MAX_DESCRIPTION) {
            throw DomainException.validation("DESCRIPTION_TOO_LONG", "the description can be at most 1000 characters");
        }
        return clean;
    }

    private static SortedSet<String> cleanPermissions(Collection<String> values) {
        if (values == null) {
            throw DomainException.validation("PERMISSIONS_REQUIRED", "the permission list is required (it may be empty)");
        }
        if (values.size() > MAX_PERMISSIONS) {
            throw DomainException.validation("TOO_MANY_PERMISSIONS", "a role can have at most 500 permissions");
        }
        SortedSet<String> clean = new TreeSet<>();
        values.forEach(p -> clean.add(Codes.permissionCode(p)));
        return Collections.unmodifiableSortedSet(clean);
    }
}
