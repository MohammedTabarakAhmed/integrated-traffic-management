package gov.itms.iam.application;

import gov.itms.iam.domain.AccessReviewCampaign;
import gov.itms.iam.domain.AccessReviewItem;
import gov.itms.iam.domain.ApprovalLimit;
import gov.itms.iam.domain.Permission;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.iam.domain.RoleConflict;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
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
import java.util.UUID;
import java.util.stream.Collectors;

/** In-memory test doubles for the application ports. They mimic the database rules that matter to the use cases. */
final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    static final class Permissions implements PermissionRepository {
        final Map<String, Permission> rows = new LinkedHashMap<>();

        @Override
        public List<Permission> findAll() {
            return rows.values().stream().sorted(Comparator.comparing(Permission::code)).toList();
        }

        @Override
        public Set<String> existing(Collection<String> codes) {
            return codes.stream().filter(rows::containsKey).collect(Collectors.toSet());
        }
    }

    static final class Roles implements RoleRepository {
        final Map<String, Role> rows = new LinkedHashMap<>();
        final List<RoleVersion> versions = new ArrayList<>();

        @Override
        public Optional<Role> find(String code) {
            return Optional.ofNullable(rows.get(code));
        }

        @Override
        public List<Role> findAll() {
            return rows.values().stream().sorted(Comparator.comparing(Role::code)).toList();
        }

        @Override
        public void insert(Role role, String actorId, Instant at) {
            if (rows.putIfAbsent(role.code(), role) != null) {
                throw new IllegalStateException("duplicate key");
            }
            versions.add(new RoleVersion(role, at, null, actorId));
        }

        @Override
        public boolean update(Role role, long expectedVersion, String actorId, Instant at) {
            Role current = rows.get(role.code());
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            rows.put(role.code(), role);
            for (int i = 0; i < versions.size(); i++) {
                RoleVersion v = versions.get(i);
                if (v.role().code().equals(role.code()) && v.validTo() == null) {
                    versions.set(i, new RoleVersion(v.role(), v.validFrom(), at, v.changedBy()));
                }
            }
            versions.add(new RoleVersion(role, at, null, actorId));
            return true;
        }

        @Override
        public List<RoleVersion> history(String code) {
            return versions.stream().filter(v -> v.role().code().equals(code)).toList();
        }
    }

    static final class Assignments implements AssignmentRepository {
        final Map<UUID, RoleAssignment> rows = new LinkedHashMap<>();

        @Override
        public void insert(RoleAssignment a) {
            if (a.assignedBy().id().equals(a.subjectId())) {
                throw new IllegalStateException("ck_assignment_sod");
            }
            rows.put(a.id(), a);
        }

        @Override
        public Optional<RoleAssignment> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<RoleAssignment> findForUpdate(UUID id) {
            return find(id);
        }

        @Override
        public void saveRevocation(RoleAssignment a) {
            rows.put(a.id(), a);
        }

        final List<String> locked = new ArrayList<>();

        @Override
        public void lockSubjects(Collection<String> subjectIds) {
            locked.addAll(subjectIds.stream().sorted().toList());
        }

        @Override
        public List<RoleAssignment> search(String subjectId, String roleCode, RoleAssignment.Status status) {
            return rows.values().stream()
                    .filter(a -> subjectId == null || a.subjectId().equals(subjectId))
                    .filter(a -> roleCode == null || a.roleCode().equals(roleCode))
                    .filter(a -> status == null || a.status() == status)
                    .sorted(Comparator.comparing(RoleAssignment::assignedAt).reversed())
                    .toList();
        }

        @Override
        public List<RoleAssignment> activeDirect(Instant at) {
            return rows.values().stream()
                    .filter(a -> !a.isDelegation() && a.isActiveOrUpcomingAt(at))
                    .sorted(Comparator.comparing(RoleAssignment::assignedAt))
                    .toList();
        }

        @Override
        public List<RoleAssignment> delegations(String partyId) {
            return rows.values().stream()
                    .filter(RoleAssignment::isDelegation)
                    .filter(d -> partyId == null || d.subjectId().equals(partyId)
                            || d.assignedBy().id().equals(partyId)
                            || rows.get(d.delegatedFrom()).subjectId().equals(partyId))
                    .sorted(Comparator.comparing(RoleAssignment::assignedAt).reversed())
                    .toList();
        }
    }

    static final class ApprovalLimits implements ApprovalLimitRepository {
        final Map<String, List<ApprovalLimit>> rows = new LinkedHashMap<>();
        final List<String> history = new ArrayList<>();

        @Override
        public List<ApprovalLimit> forRole(String roleCode) {
            return rows.getOrDefault(roleCode, List.of());
        }

        @Override
        public void replace(String roleCode, List<ApprovalLimit> limits, String actorId, Instant at) {
            rows.put(roleCode, List.copyOf(limits));
            history.add(roleCode + " by " + actorId + ": " + limits);
        }
    }

    static final class RoleConflicts implements RoleConflictRepository {
        final Map<UUID, RoleConflict> rows = new LinkedHashMap<>();

        @Override
        public void insert(RoleConflict c) {
            boolean activeTwin = rows.values().stream().anyMatch(x -> x.status() == RoleConflict.Status.ACTIVE
                    && x.roleA().equals(c.roleA()) && x.roleB().equals(c.roleB()));
            if (activeTwin) {
                throw new IllegalStateException("uq_role_conflict_active");
            }
            rows.put(c.id(), c);
        }

        @Override
        public Optional<RoleConflict> findForUpdate(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void saveRetirement(RoleConflict c) {
            rows.put(c.id(), c);
        }

        @Override
        public List<RoleConflict> search(RoleConflict.Status status) {
            return rows.values().stream()
                    .filter(c -> status == null || c.status() == status)
                    .sorted(Comparator.comparing(RoleConflict::createdAt).reversed())
                    .toList();
        }
    }

    /**
     * Access reviews. Mimics the database rules that matter to the use cases: one PENDING item per assignment
     * (skipped on insert), and a REVOKED item needs its assignment revoked first.
     */
    static final class AccessReviews implements AccessReviewRepository {
        final Map<UUID, AccessReviewCampaign> campaigns = new LinkedHashMap<>();
        final Map<UUID, AccessReviewItem> items = new LinkedHashMap<>();
        final List<String> locks = new ArrayList<>();
        private final Assignments assignments;

        AccessReviews(Assignments assignments) {
            this.assignments = assignments;
        }

        @Override
        public void insertCampaign(AccessReviewCampaign campaign) {
            campaigns.put(campaign.id(), campaign);
        }

        @Override
        public Optional<AccessReviewCampaign> findCampaign(UUID id) {
            return Optional.ofNullable(campaigns.get(id));
        }

        @Override
        public Optional<AccessReviewCampaign> findCampaignForShare(UUID id) {
            locks.add("share " + id);
            return findCampaign(id);
        }

        @Override
        public Optional<AccessReviewCampaign> findCampaignForUpdate(UUID id) {
            locks.add("update " + id);
            return findCampaign(id);
        }

        @Override
        public List<AccessReviewCampaign> searchCampaigns(AccessReviewCampaign.Status status) {
            return campaigns.values().stream()
                    .filter(c -> status == null || c.status() == status)
                    .sorted(Comparator.comparing(AccessReviewCampaign::createdAt).reversed())
                    .toList();
        }

        @Override
        public void saveClosure(AccessReviewCampaign campaign) {
            campaigns.put(campaign.id(), campaign);
        }

        @Override
        public int insertItemsSkippingPending(List<AccessReviewItem> newItems) {
            int inserted = 0;
            for (AccessReviewItem item : newItems) {
                boolean pendingElsewhere = items.values().stream().anyMatch(i -> i.status()
                        == AccessReviewItem.Status.PENDING && i.assignmentId().equals(item.assignmentId()));
                boolean active = assignments.find(item.assignmentId())
                        .map(a -> a.status() == RoleAssignment.Status.ACTIVE).orElse(false);
                if (!pendingElsewhere && active) {
                    items.put(item.id(), item);
                    inserted++;
                }
            }
            return inserted;
        }

        @Override
        public Optional<AccessReviewItem> findItem(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<AccessReviewItem> findItemForUpdate(UUID id) {
            locks.add("item " + id);
            return findItem(id);
        }

        @Override
        public List<AccessReviewItem> searchItems(UUID campaignId, AccessReviewItem.Status status, String subjectId) {
            return items.values().stream()
                    .filter(i -> campaignId == null || i.campaignId().equals(campaignId))
                    .filter(i -> status == null || i.status() == status)
                    .filter(i -> subjectId == null || i.subjectId().equals(subjectId))
                    .toList();
        }

        @Override
        public void saveItemDecision(AccessReviewItem item) {
            if (item.status() == AccessReviewItem.Status.REVOKED && assignments.find(item.assignmentId())
                    .map(a -> a.status() != RoleAssignment.Status.REVOKED).orElse(true)) {
                throw new IllegalStateException("a REVOKED review item needs its role assignment to be revoked first");
            }
            items.put(item.id(), item);
        }

        @Override
        public Map<AccessReviewItem.Status, Integer> counts(UUID campaignId) {
            Map<AccessReviewItem.Status, Integer> counts = new LinkedHashMap<>();
            items.values().stream().filter(i -> i.campaignId().equals(campaignId))
                    .forEach(i -> counts.merge(i.status(), 1, Integer::sum));
            return counts;
        }

        @Override
        public Map<UUID, Instant> lastKeptAt(Collection<UUID> assignmentIds) {
            Map<UUID, Instant> kept = new LinkedHashMap<>();
            items.values().stream()
                    .filter(i -> i.status() == AccessReviewItem.Status.KEPT && assignmentIds.contains(i.assignmentId()))
                    .forEach(i -> kept.merge(i.assignmentId(), i.decidedAt(), (a, b) -> a.isAfter(b) ? a : b));
            return kept;
        }

        @Override
        public Map<UUID, UUID> pendingItemIds(Collection<UUID> assignmentIds) {
            Map<UUID, UUID> pending = new LinkedHashMap<>();
            items.values().stream()
                    .filter(i -> i.status() == AccessReviewItem.Status.PENDING
                            && assignmentIds.contains(i.assignmentId()))
                    .forEach(i -> pending.put(i.assignmentId(), i.id()));
            return pending;
        }
    }

    record AuditEntry(String actorId, String action, String entityType, String entityId, Object oldValue,
                      Object newValue, String reason) {
    }

    static final class Audit implements AuditRecorder {
        final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            entries.add(new AuditEntry(null, action, entityType, entityId, oldValue, newValue, reason));
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            entries.add(new AuditEntry(Objects.requireNonNull(actor).userId(), action, entityType, entityId, oldValue,
                    newValue, reason));
        }

        List<String> actions() {
            return entries.stream().map(AuditEntry::action).toList();
        }
    }
}
