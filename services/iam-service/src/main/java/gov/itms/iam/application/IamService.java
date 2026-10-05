package gov.itms.iam.application;

import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.ApprovalLimit;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.Codes;
import gov.itms.iam.domain.DomainException;
import gov.itms.iam.domain.Instants;
import gov.itms.iam.domain.Permission;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.iam.domain.RoleConflict;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity and access use cases (CC-001…CC-005). Every change is audited in the same transaction as the data it
 * changes (CC-120, NFR-015). Data-scope rules for administrators:
 * <ul>
 *   <li>assignments outside the caller's {@value #ASSIGNMENT_READ} scope are invisible (not found);</li>
 *   <li>creating or revoking an assignment needs {@value #ASSIGNMENT_MANAGE} covering all of its scopes;</li>
 *   <li>delegations (CC-004) are made by the holder of the source, or by a caller whose {@value #DELEGATION_CREATE}
 *       covers the source's scopes; parties to a delegation always see it, others need {@value #DELEGATION_READ}
 *       in scope.</li>
 *   <li>deactivating a leaver (SEC-007) needs {@value #SUBJECT_DEACTIVATE} covering every assignment it revokes.</li>
 * </ul>
 * Nobody receives a role that conflicts with one they hold, by assignment or by delegation (CC-002).
 */
public class IamService {

    public static final String ASSIGNMENT_READ = "iam.assignment.read";
    public static final String ASSIGNMENT_MANAGE = "iam.assignment.manage";
    public static final String DELEGATION_CREATE = "iam.delegation.create";
    public static final String DELEGATION_READ = "iam.delegation.read";
    public static final String SUBJECT_DEACTIVATE = "iam.subject.deactivate";
    static final String BOOTSTRAP_ROLE = "IT_ADMINISTRATOR";
    static final String BOOTSTRAP_ACTOR = "SYSTEM_BOOTSTRAP";

    private final PermissionRepository permissions;
    private final RoleRepository roles;
    private final AssignmentRepository assignments;
    private final ApprovalLimitRepository limits;
    private final RoleConflictRepository conflicts;
    private final AuditRecorder audit;
    private final Clock clock;

    /** A role's approval limits together with the role version they belong to (CC-003). */
    public record RoleApprovalLimits(String roleCode, long version, List<ApprovalLimit> limits) {
        public RoleApprovalLimits {
            limits = List.copyOf(limits);
        }
    }

    /** A subject who holds both roles of an ACTIVE conflicting pair, with the assignments involved (CC-002). */
    public record RoleConflictViolation(RoleConflict conflict, String subjectId, String subjectUsername,
                                        List<UUID> assignmentIds) {
        public RoleConflictViolation {
            assignmentIds = List.copyOf(assignmentIds);
        }
    }

    /** What one leaver deactivation revoked (SEC-007); empty when there was nothing left to revoke. */
    public record SubjectDeactivation(String subjectId, Instant deactivatedAt, List<RoleAssignment> revoked) {
        public SubjectDeactivation {
            revoked = List.copyOf(revoked);
        }
    }

    public IamService(PermissionRepository permissions, RoleRepository roles, AssignmentRepository assignments,
                      ApprovalLimitRepository limits, RoleConflictRepository conflicts, AuditRecorder audit,
                      Clock clock) {
        this.permissions = permissions;
        this.roles = roles;
        this.assignments = assignments;
        this.limits = limits;
        this.conflicts = conflicts;
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
        Role current = changeableRole(code, expectedVersion, caller);
        Role updated = current.redefine(name, description, active, perms);
        requireCatalogue(updated.permissions());
        if (!roles.update(updated, expectedVersion, actor.id(), now())) {
            throw DomainException.conflict("STALE_ROLE", "the role was changed by someone else; open it again");
        }
        audit.record("IAM_ROLE_UPDATED", "Role", updated.code(), current, updated, why);
        return updated;
    }

    // ---------------------------------------------------------------- approval limits (CC-003)

    @Transactional(readOnly = true)
    public RoleApprovalLimits approvalLimits(String code) {
        Role role = role(code);
        return new RoleApprovalLimits(role.code(), role.version(), limits.forRole(role.code()));
    }

    /**
     * Replaces all approval limits of a role (CC-003). The change creates a new role version, so a stale edit is
     * refused, and the same rule as {@link #updateRole} applies: nobody changes the limits of a role they hold.
     */
    @Transactional
    public RoleApprovalLimits replaceApprovalLimits(String code, Collection<ApprovalLimit> newLimits,
                                                    long expectedVersion, String reason, Actor actor,
                                                    Authorisation caller) {
        String why = Codes.reason(reason);
        List<ApprovalLimit> valid = ApprovalLimit.validSet(newLimits);
        Role current = changeableRole(code, expectedVersion, caller);
        Role next = current.redefine(current.name(), current.description(), current.active(), current.permissions());
        Instant at = now();
        if (!roles.update(next, expectedVersion, actor.id(), at)) {
            throw DomainException.conflict("STALE_ROLE", "the role was changed by someone else; open it again");
        }
        RoleApprovalLimits before = new RoleApprovalLimits(current.code(), current.version(),
                limits.forRole(current.code()));
        limits.replace(current.code(), valid, actor.id(), at);
        RoleApprovalLimits after = new RoleApprovalLimits(next.code(), next.version(), valid);
        audit.record("IAM_APPROVAL_LIMITS_REPLACED", "Role", current.code(), before, after, why);
        return after;
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

    /**
     * The caller's own assignments, direct and received by delegation (CC-001, CC-004); {@code status} defaults to
     * ACTIVE. Needs no permission: a holder needs the ids to delegate their own assignments.
     */
    @Transactional(readOnly = true)
    public List<RoleAssignment> myAssignments(RoleAssignment.Status status, Authorisation caller) {
        return assignments.search(caller.subjectId(), null, status == null ? RoleAssignment.Status.ACTIVE : status);
    }

    /**
     * Assigns a role within data scopes (CC-001). Self-assignment, granting beyond one's own scope and a role that
     * conflicts with one the subject holds (CC-002) are refused.
     */
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
        assignments.lockSubjects(List.of(assignment.subjectId()));
        boolean duplicate = assignments.search(assignment.subjectId(), role.code(), RoleAssignment.Status.ACTIVE)
                .stream().anyMatch(a -> a.scopes().equals(scopes) && overlaps(a, assignment));
        if (duplicate) {
            throw DomainException.conflict("ASSIGNMENT_EXISTS",
                    "the subject already holds this role with the same scopes for an overlapping period");
        }
        requireNoConflictingRole(assignment.subjectId(), role.code());
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

    // ---------------------------------------------------------------- delegation (CC-004)

    /**
     * Delegations in which {@code partyId} (default: the caller) is the delegate, the delegator or the holder of the
     * source, limited to what the caller may see.
     */
    @Transactional(readOnly = true)
    public List<RoleAssignment> delegations(String partyId, Authorisation caller) {
        String party = partyId == null ? caller.subjectId() : Codes.subjectId(partyId);
        return assignments.delegations(party).stream().filter(d -> canSeeDelegation(d, caller)).toList();
    }

    /**
     * Delegates an assignment for a set period (CC-004). Allowed for the holder of the source, or for a caller whose
     * {@value #DELEGATION_CREATE} scope covers the source (on behalf of an absent holder).
     */
    @Transactional
    @SuppressWarnings("java:S107") // every field of the delegation request plus the caller
    public RoleAssignment delegate(UUID sourceId, String delegateId, String delegateUsername, Instant validFrom,
                                   Instant validTo, String reason, Actor actor, Authorisation caller) {
        RoleAssignment source = assignments.find(sourceId).orElseThrow(() -> assignmentNotFound(sourceId));
        Scopes sourceScopes = toScopes(source.scopes());
        boolean holder = source.subjectId().equals(actor.id());
        if (!holder && !caller.covers(DELEGATION_CREATE, sourceScopes)) {
            if (caller.covers(ASSIGNMENT_READ, sourceScopes) || caller.covers(DELEGATION_READ, sourceScopes)) {
                throw DomainException.notPermitted("DELEGATION_NOT_ALLOWED",
                        "only the holder of the assignment or an administrator in scope can delegate it", "CC-004");
            }
            throw assignmentNotFound(sourceId);
        }
        // Lock holder and delegate, then read the source again: a concurrent leaver deactivation may have revoked it.
        assignments.lockSubjects(List.of(source.subjectId(), Codes.subjectId(delegateId)));
        RoleAssignment current = assignments.find(sourceId).orElseThrow(() -> assignmentNotFound(sourceId));
        RoleAssignment delegation = RoleAssignment.delegate(current, delegateId, delegateUsername, validFrom, validTo,
                reason, actor, now());
        Role role = roles.find(current.roleCode()).orElseThrow(() -> roleNotFound(current.roleCode()));
        if (!role.active()) {
            throw DomainException.validation("ROLE_INACTIVE", "role " + role.code() + " is inactive", role.code());
        }
        boolean duplicate = assignments.search(delegation.subjectId(), role.code(), RoleAssignment.Status.ACTIVE)
                .stream().anyMatch(a -> a.scopes().equals(delegation.scopes()) && overlaps(a, delegation));
        if (duplicate) {
            throw DomainException.conflict("ASSIGNMENT_EXISTS",
                    "the subject already holds this role with the same scopes for an overlapping period");
        }
        requireNoConflictingRole(delegation.subjectId(), role.code());
        assignments.insert(delegation);
        audit.record("IAM_DELEGATION_CREATED", "RoleAssignment", delegation.id().toString(), null,
                snapshot(delegation), delegation.reason());
        return delegation;
    }

    /** Revokes a delegation: by the delegator, the holder of the source, or an administrator in scope (CC-004). */
    @Transactional
    public RoleAssignment revokeDelegation(UUID id, String reason, Actor actor, Authorisation caller) {
        RoleAssignment delegation = assignments.findForUpdate(id)
                .filter(RoleAssignment::isDelegation)
                .filter(d -> canSeeDelegation(d, caller))
                .orElseThrow(() -> DomainException.notFound("DELEGATION_NOT_FOUND",
                        "delegation " + id + " does not exist"));
        boolean party = delegation.assignedBy().id().equals(actor.id()) || holderOfSource(delegation, actor.id());
        if (!party && !caller.covers(DELEGATION_CREATE, toScopes(delegation.scopes()))) {
            throw DomainException.notPermitted("DELEGATION_NOT_ALLOWED",
                    "only the delegator, the holder or an administrator in scope can revoke this delegation",
                    "CC-004");
        }
        Map<String, Object> before = snapshot(delegation);
        delegation.revoke(actor, reason, now());
        assignments.saveRevocation(delegation);
        audit.record("IAM_DELEGATION_REVOKED", "RoleAssignment", id.toString(), before, snapshot(delegation),
                delegation.revocationReason());
        return delegation;
    }

    private boolean canSeeDelegation(RoleAssignment d, Authorisation caller) {
        String me = caller.subjectId();
        Scopes scopes = toScopes(d.scopes());
        return d.subjectId().equals(me) || d.assignedBy().id().equals(me) || holderOfSource(d, me)
                || caller.covers(DELEGATION_READ, scopes) || caller.covers(DELEGATION_CREATE, scopes);
    }

    private boolean holderOfSource(RoleAssignment delegation, String subjectId) {
        return assignments.find(delegation.delegatedFrom()).map(s -> s.subjectId().equals(subjectId)).orElse(false);
    }

    // ---------------------------------------------------------------- conflicting roles (CC-002)

    @Transactional(readOnly = true)
    public List<RoleConflict> roleConflicts(RoleConflict.Status status) {
        return conflicts.search(status);
    }

    /**
     * Declares two roles that one subject must not hold together. Revokes nothing: current holders of both show up
     * in {@link #roleConflictViolations}.
     */
    @Transactional
    public RoleConflict declareRoleConflict(String role1, String role2, String reason, Actor actor) {
        RoleConflict conflict = RoleConflict.declare(role1, role2, reason, actor, now());
        role(conflict.roleA());
        role(conflict.roleB());
        boolean exists = conflicts.search(RoleConflict.Status.ACTIVE).stream()
                .anyMatch(c -> c.roleA().equals(conflict.roleA()) && c.roleB().equals(conflict.roleB()));
        if (exists) {
            throw DomainException.conflict("ROLE_CONFLICT_EXISTS", "these roles are already declared as conflicting",
                    conflict.roleA(), conflict.roleB());
        }
        conflicts.insert(conflict);
        audit.record("IAM_ROLE_CONFLICT_CREATED", "RoleConflict", conflict.id().toString(), null, snapshot(conflict),
                conflict.reason());
        return conflict;
    }

    @Transactional
    public RoleConflict retireRoleConflict(UUID id, String reason, Actor actor) {
        RoleConflict conflict = conflicts.findForUpdate(id).orElseThrow(() -> DomainException.notFound(
                "ROLE_CONFLICT_NOT_FOUND", "role conflict " + id + " does not exist"));
        Map<String, Object> before = snapshot(conflict);
        conflict.retire(actor, reason, now());
        conflicts.saveRetirement(conflict);
        audit.record("IAM_ROLE_CONFLICT_RETIRED", "RoleConflict", id.toString(), before, snapshot(conflict),
                conflict.retirementReason());
        return conflict;
    }

    /**
     * Subjects who hold both roles of an ACTIVE pair now (ACTIVE assignments that have not ended), considering only
     * assignments inside the caller's {@value #ASSIGNMENT_READ} scope. Ordered by pair, then subject.
     */
    @Transactional(readOnly = true)
    public List<RoleConflictViolation> roleConflictViolations(Authorisation caller) {
        List<RoleConflict> pairs = conflicts.search(RoleConflict.Status.ACTIVE).stream()
                .sorted(Comparator.comparing(RoleConflict::roleA).thenComparing(RoleConflict::roleB)).toList();
        if (pairs.isEmpty()) {
            return List.of();
        }
        Instant now = now();
        Map<String, List<RoleAssignment>> held = new TreeMap<>();
        for (RoleAssignment a : assignments.search(null, null, RoleAssignment.Status.ACTIVE)) {
            if (a.isActiveOrUpcomingAt(now) && caller.covers(ASSIGNMENT_READ, toScopes(a.scopes()))) {
                held.computeIfAbsent(a.subjectId(), s -> new ArrayList<>()).add(a);
            }
        }
        List<RoleConflictViolation> violations = new ArrayList<>();
        for (RoleConflict pair : pairs) {
            held.forEach((subject, list) -> {
                List<RoleAssignment> involved = list.stream().filter(a -> pair.involves(a.roleCode())).toList();
                Set<String> roleCodes = new TreeSet<>(involved.stream().map(RoleAssignment::roleCode).toList());
                if (roleCodes.size() == 2) {
                    String username = involved.stream().map(RoleAssignment::subjectUsername)
                            .filter(Objects::nonNull).findFirst().orElse(null);
                    violations.add(new RoleConflictViolation(pair, subject, username,
                            involved.stream().map(RoleAssignment::id).toList()));
                }
            });
        }
        return violations;
    }

    /** Refuses a role that conflicts with one the subject holds: ACTIVE and not yet ended (CC-002). */
    private void requireNoConflictingRole(String subjectId, String roleCode) {
        Set<String> conflicting = new TreeSet<>();
        for (RoleConflict c : conflicts.search(RoleConflict.Status.ACTIVE)) {
            if (c.involves(roleCode)) {
                conflicting.add(c.otherThan(roleCode));
            }
        }
        if (conflicting.isEmpty()) {
            return;
        }
        Instant now = now();
        assignments.search(subjectId, null, RoleAssignment.Status.ACTIVE).stream()
                .filter(a -> a.isActiveOrUpcomingAt(now) && conflicting.contains(a.roleCode()))
                .findFirst()
                .ifPresent(a -> {
                    throw DomainException.segregationOfDuties("ROLE_CONFLICT", "role " + roleCode
                            + " conflicts with role " + a.roleCode() + ", which the subject holds", roleCode,
                            a.roleCode());
                });
    }

    // ---------------------------------------------------------------- leavers (SEC-007)

    /**
     * Leaver deactivation, normally called by the directory feed the day a person leaves (SEC-007). In one
     * transaction it revokes every ACTIVE assignment of the subject (direct and received delegations) and every ACTIVE
     * delegation made from the subject's assignments. The caller's {@value #SUBJECT_DEACTIVATE} scope must cover all
     * of them, otherwise nothing is revoked. Idempotent: a repeat call revokes nothing and is still audited.
     */
    @Transactional
    public SubjectDeactivation deactivateSubject(String subjectId, String reason, Actor actor, Authorisation caller) {
        String subject = Codes.subjectId(subjectId);
        String why = Codes.reason(reason);
        assignments.lockSubjects(List.of(subject));
        Map<UUID, RoleAssignment> targets = new LinkedHashMap<>();
        for (RoleAssignment d : assignments.delegations(subject)) {
            if (d.status() == RoleAssignment.Status.ACTIVE && !d.subjectId().equals(subject)
                    && holderOfSource(d, subject)) {
                targets.put(d.id(), d);
            }
        }
        for (RoleAssignment a : assignments.search(subject, null, RoleAssignment.Status.ACTIVE)) {
            targets.put(a.id(), a);
        }
        if (targets.values().stream().anyMatch(a -> !caller.covers(SUBJECT_DEACTIVATE, toScopes(a.scopes())))) {
            throw DomainException.outOfScope("SCOPE_EXCEEDED",
                    "some of this person's roles are outside your regions, centres or modules; nothing was revoked");
        }
        Instant at = now();
        List<RoleAssignment> revoked = new ArrayList<>();
        for (UUID id : targets.keySet()) {
            RoleAssignment a = assignments.findForUpdate(id).orElseThrow(() -> assignmentNotFound(id));
            if (a.status() != RoleAssignment.Status.ACTIVE) {
                continue; // revoked concurrently by someone else
            }
            Map<String, Object> before = snapshot(a);
            a.revoke(actor, why, at);
            assignments.saveRevocation(a);
            audit.record(a.isDelegation() ? "IAM_DELEGATION_REVOKED" : "IAM_ROLE_REVOKED", "RoleAssignment",
                    id.toString(), before, snapshot(a), why);
            revoked.add(a);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("subjectId", subject);
        summary.put("revokedCount", revoked.size());
        summary.put("revokedAssignmentIds", revoked.stream().map(a -> a.id().toString()).toList());
        audit.record("IAM_SUBJECT_DEACTIVATED", "Subject", subject, null, summary, why);
        return new SubjectDeactivation(subject, at, revoked);
    }

    // ---------------------------------------------------------------- effective authorisation

    /**
     * Grants of every assignment effective now whose role is active (CC-001), each with its role's approval limits
     * (CC-003). A delegation grants nothing unless its source is effective now too (CC-004). Grants keep their own
     * scopes and are never merged.
     */
    @Transactional(readOnly = true)
    public Authorisation effectiveAuthorisation(String subjectId) {
        Instant now = now();
        Map<String, Optional<Role>> roleCache = new LinkedHashMap<>();
        Map<String, List<Authorisation.ApprovalLimit>> limitCache = new LinkedHashMap<>();
        List<Authorisation.Grant> grants = new ArrayList<>();
        for (RoleAssignment a : assignments.search(subjectId, null, RoleAssignment.Status.ACTIVE)) {
            if (!a.isEffectiveAt(now) || (a.isDelegation() && !sourceEffectiveAt(a, now))) {
                continue;
            }
            roleCache.computeIfAbsent(a.roleCode(), roles::find)
                    .filter(Role::active)
                    .ifPresent(r -> grants.add(new Authorisation.Grant(r.code(), r.permissions(), toScopes(a.scopes()),
                            a.validTo(), limitCache.computeIfAbsent(r.code(), this::grantLimits))));
        }
        return new Authorisation(subjectId, grants, now);
    }

    private boolean sourceEffectiveAt(RoleAssignment delegation, Instant at) {
        return assignments.find(delegation.delegatedFrom()).map(s -> s.isEffectiveAt(at)).orElse(false);
    }

    private List<Authorisation.ApprovalLimit> grantLimits(String roleCode) {
        return limits.forRole(roleCode).stream()
                .map(l -> new Authorisation.ApprovalLimit(l.limitType(), l.maxAmountMinor(), l.currency()))
                .toList();
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

    /** The role, if the caller may change it: they do not hold it (CC-002) and saw its current version. */
    private Role changeableRole(String code, long expectedVersion, Authorisation caller) {
        Role current = role(code);
        if (caller.roles().contains(current.code())) {
            throw DomainException.segregationOfDuties("ROLE_HELD_BY_ACTOR",
                    "you hold this role, so another administrator must change it");
        }
        if (current.version() != expectedVersion) {
            throw DomainException.conflict("STALE_ROLE", "the role was changed by someone else; open it again");
        }
        return current;
    }

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
        return Instants.stored(clock.instant());
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
        m.put("source", a.source().name());
        m.put("delegatedFrom", a.delegatedFrom() == null ? null : a.delegatedFrom().toString());
        m.put("assignedBy", a.assignedBy().id());
        m.put("revokedBy", a.revokedBy() == null ? null : a.revokedBy().id());
        m.put("revocationReason", a.revocationReason());
        return m;
    }

    /** Audit-friendly view of a conflicting-role pair. */
    static Map<String, Object> snapshot(RoleConflict c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id().toString());
        m.put("roleA", c.roleA());
        m.put("roleB", c.roleB());
        m.put("reason", c.reason());
        m.put("status", c.status().name());
        m.put("createdBy", c.createdBy().id());
        m.put("retiredBy", c.retiredBy() == null ? null : c.retiredBy().id());
        m.put("retirementReason", c.retirementReason());
        return m;
    }
}
