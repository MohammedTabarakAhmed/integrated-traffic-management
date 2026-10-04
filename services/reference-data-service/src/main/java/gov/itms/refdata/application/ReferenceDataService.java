package gov.itms.refdata.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.refdata.domain.BilingualName;
import gov.itms.refdata.domain.ChangeRequest;
import gov.itms.refdata.domain.ChangeRequest.Actor;
import gov.itms.refdata.domain.ChangeStatus;
import gov.itms.refdata.domain.ChangeType;
import gov.itms.refdata.domain.Codes;
import gov.itms.refdata.domain.DomainException;
import gov.itms.refdata.domain.ItemData;
import gov.itms.refdata.domain.ReferenceItem;
import gov.itms.refdata.domain.ReferenceList;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference data use cases (CC-110, CC-112, CC-114, CC-121, DAT-002). Reads return what is in force; every write is a
 * maker-checker change that is audited in the same transaction as the data it changes (CC-120, NFR-015).
 */
public class ReferenceDataService {

    static final String ENTITY_CHANGE = "ReferenceDataChange";
    static final String ENTITY_ITEM = "ReferenceItem";
    static final String ENTITY_LIST = "ReferenceList";

    private final ReferenceListRepository lists;
    private final ReferenceItemRepository items;
    private final ChangeRequestRepository changes;
    private final AuditRecorder audit;
    private final Clock clock;

    public ReferenceDataService(ReferenceListRepository lists, ReferenceItemRepository items,
                                ChangeRequestRepository changes, AuditRecorder audit, Clock clock) {
        this.lists = lists;
        this.items = items;
        this.changes = changes;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<ReferenceList> lists() {
        return lists.findAll();
    }

    /**
     * Items in force on {@code asOf} (default today). With a region, a regional variant replaces the national item with
     * the same code (CC-112).
     */
    @Transactional(readOnly = true)
    public List<ReferenceItem> items(String listCode, LocalDate asOf, String regionCode, boolean includeInactive) {
        requireList(listCode);
        Codes.optional("region", regionCode);
        LocalDate date = asOf != null ? asOf : LocalDate.now(clock);
        Map<String, ReferenceItem> byCode = items.findByList(listCode, regionCode).stream()
                .collect(Collectors.toMap(ReferenceItem::code, Function.identity(),
                        (a, b) -> a.regionCode() != null ? a : b));
        return byCode.values().stream()
                .filter(i -> i.data().isEffectiveOn(date))
                .filter(i -> includeInactive || i.data().active())
                .sorted(Comparator.comparingInt((ReferenceItem i) -> i.data().sortOrder()).thenComparing(ReferenceItem::code))
                .toList();
    }

    /** The regional variant if one exists for {@code regionCode}, otherwise the national item. */
    @Transactional(readOnly = true)
    public ReferenceItem item(String listCode, String code, String regionCode) {
        requireList(listCode);
        if (regionCode != null) {
            var regional = items.find(listCode, code, Codes.require("region", regionCode));
            if (regional.isPresent()) {
                return regional.get();
            }
        }
        return items.find(listCode, code, null).orElseThrow(() -> itemNotFound(listCode, code));
    }

    /** Version history, oldest first (DAT-002, CC-121). */
    @Transactional(readOnly = true)
    public List<ReferenceItemRepository.ItemVersion> history(String listCode, String code, String regionCode) {
        requireList(listCode);
        var versions = items.history(listCode, code, Codes.optional("region", regionCode));
        if (versions.isEmpty()) {
            throw itemNotFound(listCode, code);
        }
        return versions;
    }

    @Transactional(readOnly = true)
    public List<ChangeRequest> changes(ChangeStatus status) {
        return changes.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public ChangeRequest change(UUID id) {
        return changes.find(id).orElseThrow(() -> changeNotFound(id));
    }

    // ---------------------------------------------------------------- maker-checker commands

    /** Maker submits a change (CC-114). It is validated now and re-validated when approved. */
    @Transactional
    public ChangeRequest submit(ChangeRequest change) {
        validateAgainstCurrentState(change);
        if (changes.pendingExistsFor(change.listCode(), change.itemCode(), change.regionCode())) {
            throw DomainException.conflict("CHANGE_ALREADY_PENDING",
                    "another change to the same entry is waiting for approval");
        }
        changes.insert(change);
        audit.record("REFDATA_CHANGE_SUBMITTED", ENTITY_CHANGE, change.id().toString(), null, snapshot(change),
                change.reason());
        return change;
    }

    /** Checker approves and the change is applied in the same transaction (CC-114, CC-002). */
    @Transactional
    public ChangeRequest approve(UUID id, Actor checker) {
        ChangeRequest change = changes.findForDecision(id).orElseThrow(() -> changeNotFound(id));
        Map<String, Object> before = snapshot(change);
        change.approve(checker, now());
        validateAgainstCurrentState(change);
        apply(change);
        changes.saveDecision(change);
        audit.record("REFDATA_CHANGE_APPROVED", ENTITY_CHANGE, id.toString(), before, snapshot(change), null);
        return change;
    }

    @Transactional
    public ChangeRequest reject(UUID id, Actor checker, String reason) {
        ChangeRequest change = changes.findForDecision(id).orElseThrow(() -> changeNotFound(id));
        Map<String, Object> before = snapshot(change);
        change.reject(checker, reason, now());
        changes.saveDecision(change);
        audit.record("REFDATA_CHANGE_REJECTED", ENTITY_CHANGE, id.toString(), before, snapshot(change),
                change.decisionReason());
        return change;
    }

    // ---------------------------------------------------------------- internals

    private void validateAgainstCurrentState(ChangeRequest change) {
        switch (change.type()) {
            case CREATE_LIST -> {
                if (lists.find(change.listCode()).isPresent()) {
                    throw DomainException.conflict("LIST_EXISTS", "list " + change.listCode() + " already exists",
                            change.listCode());
                }
                if (change.parentListCode() != null) {
                    requireList(change.parentListCode());
                }
            }
            case CREATE_ITEM -> {
                ReferenceList list = requireList(change.listCode());
                if (items.find(change.listCode(), change.itemCode(), change.regionCode()).isPresent()) {
                    throw DomainException.conflict("ITEM_EXISTS", "item " + change.itemCode() + " already exists",
                            change.itemCode());
                }
                checkParent(list, change.itemData());
            }
            case UPDATE_ITEM -> {
                ReferenceList list = requireList(change.listCode());
                ReferenceItem current = items.find(change.listCode(), change.itemCode(), change.regionCode())
                        .orElseThrow(() -> itemNotFound(change.listCode(), change.itemCode()));
                if (current.version() != change.baseVersion()) {
                    throw DomainException.conflict("STALE_CHANGE",
                            "the item was changed after this change was prepared; prepare it again");
                }
                checkParent(list, change.itemData());
            }
        }
    }

    private void checkParent(ReferenceList list, ItemData data) {
        if (!list.hasParent()) {
            if (data.parentCode() != null) {
                throw DomainException.validation("PARENT_NOT_ALLOWED",
                        "list " + list.code() + " has no parent list, so parentCode must be empty");
            }
            return;
        }
        if (data.parentCode() == null) {
            throw DomainException.validation("PARENT_REQUIRED",
                    "list " + list.code() + " needs a parentCode from list " + list.parentListCode());
        }
        if (items.find(list.parentListCode(), data.parentCode(), null).isEmpty()) {
            throw DomainException.validation("PARENT_NOT_FOUND",
                    "parentCode " + data.parentCode() + " does not exist in list " + list.parentListCode());
        }
    }

    private void apply(ChangeRequest change) {
        String actorId = change.checker().id();
        Instant at = change.decidedAt();
        switch (change.type()) {
            case CREATE_LIST -> {
                ReferenceList list = new ReferenceList(change.listCode(), change.listName(), change.parentListCode());
                lists.insert(list, actorId, change.id(), at);
                audit.record("REFDATA_LIST_CREATED", ENTITY_LIST, list.code(), null, list, change.reason());
            }
            case CREATE_ITEM -> {
                ReferenceItem item = new ReferenceItem(UUID.randomUUID(), change.listCode(), change.itemCode(),
                        change.regionCode(), change.itemData(), 1);
                items.insert(item, actorId, change.id(), at);
                audit.record("REFDATA_ITEM_CREATED", ENTITY_ITEM, itemKey(item), null, item, change.reason());
            }
            case UPDATE_ITEM -> {
                ReferenceItem current = items.find(change.listCode(), change.itemCode(), change.regionCode())
                        .orElseThrow(() -> itemNotFound(change.listCode(), change.itemCode()));
                ReferenceItem updated = current.withData(change.itemData());
                if (!items.update(updated, current.version(), actorId, change.id(), at)) {
                    throw DomainException.conflict("STALE_CHANGE",
                            "the item was changed after this change was prepared; prepare it again");
                }
                audit.record("REFDATA_ITEM_UPDATED", ENTITY_ITEM, itemKey(updated), current, updated, change.reason());
            }
        }
    }

    private ReferenceList requireList(String listCode) {
        return lists.find(Codes.require("listCode", listCode))
                .orElseThrow(() -> DomainException.notFound("LIST_NOT_FOUND", "list " + listCode + " does not exist",
                        listCode));
    }

    private Instant now() {
        return clock.instant();
    }

    static String itemKey(ReferenceItem item) {
        return item.listCode() + "/" + item.code() + (item.regionCode() == null ? "" : "@" + item.regionCode());
    }

    private static DomainException itemNotFound(String listCode, String code) {
        return DomainException.notFound("ITEM_NOT_FOUND", "item " + code + " does not exist in list " + listCode, code,
                listCode);
    }

    private static DomainException changeNotFound(UUID id) {
        return DomainException.notFound("CHANGE_NOT_FOUND", "change " + id + " does not exist", id);
    }

    /** Audit-friendly view of a change (keeps nulls, stable key order). */
    static Map<String, Object> snapshot(ChangeRequest c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id().toString());
        m.put("changeType", c.type().name());
        m.put("status", c.status().name());
        m.put("listCode", c.listCode());
        m.put("itemCode", c.itemCode());
        m.put("regionCode", c.regionCode());
        m.put("listName", c.listName());
        m.put("parentListCode", c.parentListCode());
        m.put("itemData", c.itemData());
        m.put("baseVersion", c.baseVersion());
        m.put("makerId", c.maker().id());
        m.put("checkerId", c.checker() == null ? null : c.checker().id());
        m.put("decisionReason", c.decisionReason());
        return m;
    }

    /** Builds the right kind of change from the maker's input. */
    public ChangeRequest prepare(ChangeType type, String listCode, String itemCode, String regionCode,
                                 BilingualName name, String parentListCode, ItemData data, Long baseVersion,
                                 String reason, Actor maker) {
        return switch (type) {
            case CREATE_LIST -> ChangeRequest.createList(listCode, name, parentListCode, reason, maker, now());
            case CREATE_ITEM -> ChangeRequest.createItem(listCode, itemCode, regionCode, data, reason, maker, now());
            case UPDATE_ITEM -> {
                if (baseVersion == null) {
                    throw DomainException.validation("BASE_VERSION_REQUIRED",
                            "UPDATE_ITEM needs the baseVersion of the item being changed");
                }
                yield ChangeRequest.updateItem(listCode, itemCode, regionCode, data, baseVersion, reason, maker, now());
            }
        };
    }
}
