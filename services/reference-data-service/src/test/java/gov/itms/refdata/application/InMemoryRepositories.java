package gov.itms.refdata.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.refdata.domain.ChangeRequest;
import gov.itms.refdata.domain.ChangeStatus;
import gov.itms.refdata.domain.ReferenceItem;
import gov.itms.refdata.domain.ReferenceList;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory test doubles for the application ports. They mimic the database constraints that matter to the rules. */
final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    static final class Lists implements ReferenceListRepository {
        final Map<String, ReferenceList> rows = new LinkedHashMap<>();

        @Override
        public Optional<ReferenceList> find(String code) {
            return Optional.ofNullable(rows.get(code));
        }

        @Override
        public List<ReferenceList> findAll() {
            return rows.values().stream().sorted(Comparator.comparing(ReferenceList::code)).toList();
        }

        @Override
        public void insert(ReferenceList list, String actorId, UUID changeId, Instant at) {
            if (rows.putIfAbsent(list.code(), list) != null) {
                throw new IllegalStateException("duplicate key");
            }
        }
    }

    static final class Items implements ReferenceItemRepository {
        final List<ReferenceItem> rows = new ArrayList<>();
        final List<ItemVersion> history = new ArrayList<>();

        @Override
        public Optional<ReferenceItem> find(String listCode, String code, String regionCode) {
            return rows.stream().filter(i -> i.listCode().equals(listCode) && i.code().equals(code)
                    && Objects.equals(i.regionCode(), regionCode)).findFirst();
        }

        @Override
        public List<ReferenceItem> findByList(String listCode, String regionCode) {
            return rows.stream().filter(i -> i.listCode().equals(listCode)
                    && (i.regionCode() == null || i.regionCode().equals(regionCode))).toList();
        }

        @Override
        public void insert(ReferenceItem item, String actorId, UUID changeId, Instant at) {
            if (find(item.listCode(), item.code(), item.regionCode()).isPresent()) {
                throw new IllegalStateException("duplicate key");
            }
            rows.add(item);
            history.add(new ItemVersion(item, at, null, actorId, changeId));
        }

        @Override
        public boolean update(ReferenceItem item, long expectedVersion, String actorId, UUID changeId, Instant at) {
            for (int i = 0; i < rows.size(); i++) {
                ReferenceItem current = rows.get(i);
                if (current.id().equals(item.id())) {
                    if (current.version() != expectedVersion) {
                        return false;
                    }
                    rows.set(i, item);
                    for (int h = 0; h < history.size(); h++) {
                        ItemVersion v = history.get(h);
                        if (v.item().id().equals(item.id()) && v.validTo() == null) {
                            history.set(h, new ItemVersion(v.item(), v.validFrom(), at, v.changedBy(), v.changeId()));
                        }
                    }
                    history.add(new ItemVersion(item, at, null, actorId, changeId));
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<ItemVersion> history(String listCode, String code, String regionCode) {
            return history.stream().filter(v -> v.item().listCode().equals(listCode) && v.item().code().equals(code)
                    && Objects.equals(v.item().regionCode(), regionCode)).toList();
        }
    }

    static final class Changes implements ChangeRequestRepository {
        final Map<UUID, ChangeRequest> rows = new LinkedHashMap<>();

        @Override
        public void insert(ChangeRequest change) {
            rows.put(change.id(), change);
        }

        @Override
        public Optional<ChangeRequest> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<ChangeRequest> findForDecision(UUID id) {
            return find(id);
        }

        @Override
        public void saveDecision(ChangeRequest change) {
            rows.put(change.id(), change);
        }

        @Override
        public List<ChangeRequest> findByStatus(ChangeStatus status) {
            return rows.values().stream().filter(c -> status == null || c.status() == status)
                    .sorted(Comparator.comparing(ChangeRequest::submittedAt).reversed()).toList();
        }

        @Override
        public boolean pendingExistsFor(String listCode, String itemCode, String regionCode) {
            return rows.values().stream().anyMatch(c -> c.status() == ChangeStatus.PENDING_APPROVAL
                    && c.listCode().equals(listCode) && Objects.equals(c.itemCode(), itemCode)
                    && Objects.equals(c.regionCode(), regionCode));
        }
    }

    record AuditEntry(String action, String entityType, String entityId, Object oldValue, Object newValue,
                      String reason) {
    }

    static final class Audit implements AuditRecorder {
        final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            entries.add(new AuditEntry(action, entityType, entityId, oldValue, newValue, reason));
        }

        @Override
        public void recordAs(gov.itms.platform.security.CurrentActor actor, String action, String entityType,
                             String entityId, Object oldValue, Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }

        List<String> actions() {
            return entries.stream().map(AuditEntry::action).toList();
        }
    }
}
