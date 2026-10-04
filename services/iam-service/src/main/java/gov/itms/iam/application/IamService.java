package gov.itms.iam.application;

import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.Codes;
import gov.itms.iam.domain.DomainException;
import gov.itms.iam.domain.Permission;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity and access use cases (CC-001, CC-002, CC-005). Every change is audited in the same transaction as the data
 * it changes (CC-120, NFR-015). Data-scope rules for administrators:
 * <ul>
 *   <li>assignments outside the caller's {@value #ASSIGNMENT_READ} scope are invisible (not found);</li>
 *   <li>creating or revoking an assignment needs {@value #ASSIGNMENT_MANAGE} covering all of its scopes.</li>
 * </ul>
 */
public class IamService {

    public static final String ASSIGNMENT_READ = "iam.assignment.read";
    public static final String ASSIGNMENT_MANAGE = "iam.assignment.manage";
    static final String BOOTSTRAP_ROLE = "IT_ADMINISTRATOR";
    static final String BOOTSTRAP_ACTOR = "SYSTEM_BOOTSTRAP";

    private final PermissionRepository permissions;
    private final RoleRepository roles;
    private final AssignmentRepository assignments;
    private final AuditRecorder audit;
    private final Clock clock;

    public IamService(PermissionRepository permissions, RoleRepository roles, AssignmentRepository assignments,
                      AuditRecorder audit, Clock clock) {
        this.permissions = permissions;
        this.roles = roles;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- permissions and roles

    @Transactional(readOnly = true)
    public List<Permission> permissions() {
        return permissions.findAll();
    }

    @Transactional(readOnly = true)
    public List<Role> roles() {
        return roles.findAll();
    }

    @Transactional(readOnly = true)
    public Role role(String code) {
        return roles.find(Codes.roleCode(code)).orElseThrow(() -> roleNotFound(code));
    }

    @Transactional(readOnly = true)
    public List<RoleRepository.RoleVersion> roleHistory(String code) {
        List<RoleRepository.RoleVersion> versions = roles.history(Codes.roleCode(code));
        if (versions.isEmpty()) {
            throw roleNotFound(code);
        }
        return versions;
    }

    /** Creates a role at runtime (CC-005). */
    @Transactional
    public Role createRole(String code, BilingualName name, String description, Collection<String> perms,
                           String reason, Actor actor) {
        String why = Codes.reason(reason);
        Role role = Role.create(code, name, description, perms);
        requireCatalogue(role.permissions());
        if (roles.find(role.code()).isPresent()) {
            throw DomainException.conflict("ROLE_EXISTS", "role " + role.code() + " already exists", role.code());
        }
        roles.insert(role, actor.id(), now());
        audit.record("IAM_ROLE_CREATED", "Role", role.code(), null, role, why);
        return role;
    }

    /**
     * Replaces a role definition (CC-005). The caller cannot change a role they currently hold, so nobody widens their
     * own rights (CC-002).
     */
    @Transactional
    public Role updateRole(String code, BilingualName name, String description, boolean active,
                           Collection<String> perms, long expectedVersion, String reason, Actor actor,
                           Authorisation caller) {
        String why = Codes.reason(reason);
        Role current = role(code);
        if (caller.roles().contains(current.code())) {
            throw DomainException.segregationOfDuties("ROLE_HELD_BY_ACTOR",
                    "you hold this role, so another administrator must change it");
        }
        if (current.version() != expectedVersion) {
            throw DomainException.conflict("STALE_ROLE", "the role was changed by someone else; open it again");
        }
        Role updated = current.redefine(name, description, active, perms);
        requireCatalogue(updated.permissions());
        if (!roles.update(updated, expectedVersion, actor.id(), now())) {
            throw DomainException.conflict("STALE_ROLE", "the role was changed by someone else; open it again");
        }
        audit.record("IAM_ROLE_UPDATED", "Role", updated.code(), current, updated, why);
        return updated;
    }

    // ---------------------------------------------------------------- assignments

    @Transactional(readOnly = true)
    public List<RoleAssignment> assignments(String subjectId, String roleCode, RoleAssignment.Status status,
                                            Authorisation caller) {
        return assignments.search(subjectId == null ? null : Codes.subjectId(subjectId),
                        roleCode == null ? null : Codes.roleCode(roleCode), status).stream()
                .filter(a -> caller.covers(ASSIGNMENT_READ, toScopes(a.scopes())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleAssignment assignment(UUID id, Authorisation caller) {
        return assignments.find(id)
                .filter(a -> caller.covers(ASSIGNMENT_READ, toScopes(a.scopes())))
                .orElseThrow(() -> assignmentNotFound(id));
    }

    /** Assigns a role within data scopes (CC-001). Self-assignment and granting beyond one's own scope are refused. */
    @Transactional
    public RoleAssignment assign(String subjectId, String subjectUsername, String roleCode, AssignmentScopes scopes,
                                 Instant validFrom, Instant validTo, String reason, Actor actor, Authorisation caller) {
        RoleAssignment assignment = RoleAssignment.assign(subjectId, subjectUsername, roleCode, scopes, validFrom,
                validTo, reason, actor, now());
        if (!caller.covers(ASSIGNMENT_MANAGE, toScopes(scopes))) {
            throw DomainException.outOfScope("SCOPE_EXCEEDED",
                    "you can only assign roles within your own regions, centres and modules");
        }
        Role role = roles.find(assignment.roleCode()).orElseThrow(() -> roleNotFound(roleCode));
        if (!role.active()) {
            throw DomainException.validation("ROLE_INACTIVE", "role " + role.code() + " is inactive", role.code());
        }
        boolean duplicate = assignments.search(assignment.subjectId(), role.code(), RoleAssignment.Status.ACTIVE)
                .stream().anyMatch(a -> a.scopes().equals(scopes) && overlaps(a, assignment));
        if (duplicate) {
            throw DomainException.conflict("ASSIGNMENT_EXISTS",
                    "the subject already holds this role with the same scopes for an overlapping period");
        }
        assignments.insert(assignment);
        audit.record("IAM_ROLE_ASSIGNED", "RoleAssignment", assignment.id().toString(), null, snapshot(assignment),
                assignment.reason());
        return assignment;
    }

    @Transactional
    public RoleAssignment revoke(UUID id, String reason, Actor actor, Authorisation caller) {
        RoleAssignment assignment = assignments.findForUpdate(id)
                .filter(a -> caller.covers(ASSIGNMENT_READ, toScopes(a.scopes())))
                .orElseThrow(() -> assignmentNotFound(id));
        if (!caller.covers(ASSIGNMENT_MANAGE, toScopes(assignment.scopes()))) {
            throw DomainException.outOfScope("SCOPE_EXCEEDED",
                    "you can only revoke assignments within your own regions, centres and modules");
        }
        Map<String, Object> before = snapshot(assignment);
        assignment.revoke(actor, reason, now());
        assignments.saveRevocation(assignment);
        audit.record("IAM_ROLE_REVOKED", "RoleAssignment", id.toString(), before, snapshot(assignment),
                assignment.revocationReason());
        return assignment;
    }

    // ---------------------------------------------------------------- effective authorisation

    /**
     * Grants of every assignment effective now whose role is active (CC-001). Grants keep their own scopes and are
     * never merged.
     */
    @Transactional(readOnly = true)
    public Authorisation effectiveAuthorisation(String subjectId) {
        Instant now = now();
        Map<String, Optional<Role>> roleCache = new LinkedHashMap<>();
        List<Authorisation.Grant> grants = new ArrayList<>();
        for (RoleAssignment a : assignments.search(subjectId, null, RoleAssignment.Status.ACTIVE)) {
            if (!a.isEffectiveAt(now)) {
                continue;
            }
            roleCache.computeIfAbsent(a.roleCode(), roles::find)
                    .filter(Role::active)
                    .ifPresent(r -> grants.add(new Authorisation.Grant(r.code(), r.permissions(), toScopes(a.scopes()),
                            a.validTo())));
        }
        return new Authorisation(subjectId, grants, now);
    }

    /**
     * Start-up bootstrap: if nobody can manage assignments, give the configured subject the IT administrator role
     * nationally, so the first real administrator can sign in and administer. Audited as a system action. Returns
     * whether an assignment was created.
     */
    @Transactional
    public boolean bootstrapAdministrator(String subjectId, String username) {
        Instant now = now();
        boolean someoneCanManage = assignments.search(null, null, RoleAssignment.Status.ACTIVE).stream()
                .filter(a -> a.isEffectiveAt(now))
                .anyMatch(a -> roles.find(a.roleCode())
                        .filter(r -> r.active() && r.permissions().contains(ASSIGNMENT_MANAGE)).isPresent());
        if (someoneCanManage) {
            return false;
        }
        Role role = roles.find(BOOTSTRAP_ROLE).orElseThrow(() -> roleNotFound(BOOTSTRAP_ROLE));
        RoleAssignment assignment = RoleAssignment.assign(subjectId, username, role.code(), AssignmentScopes.national(),
                now, null, "Initial administrator (bootstrap)", new Actor(BOOTSTRAP_ACTOR, BOOTSTRAP_ACTOR), now);
        assignments.insert(assignment);
        audit.recordAs(CurrentActor.system(BOOTSTRAP_ACTOR), "IAM_ROLE_ASSIGNED", "RoleAssignment",
                assignment.id().toString(), null, snapshot(assignment), assignment.reason());
        return true;
    }

    // ---------------------------------------------------------------- internals

    private void requireCatalogue(Set<String> requested) {
        Set<String> missing = new TreeSet<>(requested);
        missing.removeAll(permissions.existing(requested));
        if (!missing.isEmpty()) {
            throw DomainException.validation("UNKNOWN_PERMISSION",
                    "these permissions are not in the catalogue: " + missing, String.join(", ", missing));
        }
    }

    private static boolean overlaps(RoleAssignment a, RoleAssignment b) {
        boolean aEndsAfterBStarts = a.validTo() == null || a.validTo().isAfter(b.validFrom());
        boolean bEndsAfterAStarts = b.validTo() == null || b.validTo().isAfter(a.validFrom());
        return aEndsAfterBStarts && bEndsAfterAStarts;
    }

    static Scopes toScopes(AssignmentScopes s) {
        return new Scopes(s.regions(), s.centres(), s.modules());
    }

    private Instant now() {
        return clock.instant();
    }

    private static DomainException roleNotFound(String code) {
        return DomainException.notFound("ROLE_NOT_FOUND", "role " + code + " does not exist", code);
    }

    private static DomainException assignmentNotFound(UUID id) {
        return DomainException.notFound("ASSIGNMENT_NOT_FOUND", "assignment " + id + " does not exist");
    }

    /** Audit-friendly view of an assignment (keeps nulls, stable key order). */
    static Map<String, Object> snapshot(RoleAssignment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id().toString());
        m.put("subjectId", a.subjectId());
        m.put("roleCode", a.roleCode());
        m.put("scopes", a.scopes());
        m.put("validFrom", a.validFrom().toString());
        m.put("validTo", a.validTo() == null ? null : a.validTo().toString());
        m.put("status", a.status().name());
        m.put("assignedBy", a.assignedBy().id());
        m.put("revokedBy", a.revokedBy() == null ? null : a.revokedBy().id());
        m.put("revocationReason", a.revocationReason());
        return m;
    }
}
