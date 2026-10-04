package gov.itms.refdata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.refdata.domain.BilingualName;
import gov.itms.refdata.domain.ChangeRequest;
import gov.itms.refdata.domain.ChangeRequest.Actor;
import gov.itms.refdata.domain.ChangeStatus;
import gov.itms.refdata.domain.ChangeType;
import gov.itms.refdata.domain.DomainException;
import gov.itms.refdata.domain.ItemData;
import gov.itms.refdata.domain.ReferenceItem;
import gov.itms.refdata.domain.ReferenceList;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ReferenceDataServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");

    private InMemoryRepositories.Lists lists;
    private InMemoryRepositories.Items items;
    private InMemoryRepositories.Changes changes;
    private InMemoryRepositories.Audit audit;
    private ReferenceDataService service;

    @BeforeEach
    void setUp() {
        lists = new InMemoryRepositories.Lists();
        items = new InMemoryRepositories.Items();
        changes = new InMemoryRepositories.Changes();
        audit = new InMemoryRepositories.Audit();
        service = new ReferenceDataService(lists, items, changes, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        lists.insert(new ReferenceList("HOLD_TYPE", new BilingualName("أنواع الحجوزات", "Hold types"), null),
                "seed", null, NOW);
    }

    private static ItemData data(String en, LocalDate from, LocalDate to, boolean active, int sort) {
        return new ItemData(new BilingualName("ع " + en, en), active, from, to, null, Map.of(), sort);
    }

    private ReferenceItem seedItem(String code, String region, ItemData d) {
        ReferenceItem item = new ReferenceItem(UUID.randomUUID(), "HOLD_TYPE", code, region, d, 1);
        items.insert(item, "seed", null, NOW);
        return item;
    }

    private ChangeRequest submitAndApprove(ChangeRequest change) {
        service.submit(change);
        return service.approve(change.id(), CHECKER);
    }

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    @Test
    @Tag("CC-110")
    @Tag("CC-114")
    @DisplayName("CC-110/CC-114: an item is created only after a second person approves it")
    void createItemThroughMakerChecker() {
        ChangeRequest change = service.prepare(ChangeType.CREATE_ITEM, "HOLD_TYPE", "NEW_HOLD", null, null, null,
                new ItemData(new BilingualName("حجز جديد", "New hold"), true, TODAY, null, null,
                        Map.of("POLICE", "NH-01"), 5), null, "requested by directorate", MAKER);
        service.submit(change);

        assertThat(items.find("HOLD_TYPE", "NEW_HOLD", null)).isEmpty();
        assertThat(service.changes(ChangeStatus.PENDING_APPROVAL)).extracting(ChangeRequest::id).containsExactly(change.id());

        service.approve(change.id(), CHECKER);

        ReferenceItem created = service.item("HOLD_TYPE", "NEW_HOLD", null);
        assertThat(created.data().name().en()).isEqualTo("New hold");
        assertThat(created.data().externalCodes()).containsEntry("POLICE", "NH-01");
        assertThat(created.version()).isEqualTo(1);
        assertThat(service.change(change.id()).status()).isEqualTo(ChangeStatus.APPROVED);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: the maker's own approval is refused and nothing is applied")
    void makerCannotApproveOwnChange() {
        ChangeRequest change = ChangeRequest.createItem("HOLD_TYPE", "X_HOLD", null, data("X", TODAY, null, true, 0),
                "add", MAKER, NOW);
        service.submit(change);

        assertThatThrownBy(() -> service.approve(change.id(), new Actor("u-maker", "maker")))
                .satisfies(code("SEGREGATION_OF_DUTIES"));
        assertThat(items.find("HOLD_TYPE", "X_HOLD", null)).isEmpty();
    }

    @Test
    @Tag("CC-120")
    @DisplayName("CC-120: submit, approve and apply are audited with old and new values")
    void changesAreAudited() {
        ReferenceItem original = seedItem("COURT_ORDER", null, data("Court order", TODAY, null, true, 0));
        ChangeRequest update = ChangeRequest.updateItem("HOLD_TYPE", "COURT_ORDER", null,
                data("Court order (any court)", TODAY, null, true, 0), 1, "clarify wording", MAKER, NOW);

        submitAndApprove(update);

        assertThat(audit.actions()).containsExactly("REFDATA_CHANGE_SUBMITTED", "REFDATA_ITEM_UPDATED",
                "REFDATA_CHANGE_APPROVED");
        InMemoryRepositories.AuditEntry itemAudit = audit.entries.get(1);
        assertThat(itemAudit.entityId()).isEqualTo("HOLD_TYPE/COURT_ORDER");
        assertThat(itemAudit.oldValue()).isEqualTo(original);
        assertThat(((ReferenceItem) itemAudit.newValue()).data().name().en()).isEqualTo("Court order (any court)");
        assertThat(itemAudit.reason()).isEqualTo("clarify wording");
    }

    @Test
    @Tag("DAT-002")
    @Tag("CC-121")
    @DisplayName("DAT-002/CC-121: every version is kept with the period it was current")
    void historyKeepsEveryVersion() {
        seedItem("IMPOUND", null, data("Impound", TODAY, null, true, 0));
        submitAndApprove(ChangeRequest.updateItem("HOLD_TYPE", "IMPOUND", null,
                data("Vehicle impound", TODAY, null, true, 0), 1, "rename", MAKER, NOW));

        var versions = service.history("HOLD_TYPE", "IMPOUND", null);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).item().data().name().en()).isEqualTo("Impound");
        assertThat(versions.get(0).validTo()).isNotNull();
        assertThat(versions.get(1).item().data().name().en()).isEqualTo("Vehicle impound");
        assertThat(versions.get(1).item().version()).isEqualTo(2);
        assertThat(versions.get(1).validTo()).isNull();
        assertThat(versions.get(1).changedBy()).isEqualTo(CHECKER.id());
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: a change prepared on an outdated version is refused at approval")
    void staleUpdateRefused() {
        seedItem("MORTGAGE", null, data("Mortgage", TODAY, null, true, 0));
        ChangeRequest first = ChangeRequest.updateItem("HOLD_TYPE", "MORTGAGE", null,
                data("Mortgage (bank)", TODAY, null, true, 0), 1, "first", MAKER, NOW);
        submitAndApprove(first);

        ChangeRequest stale = ChangeRequest.updateItem("HOLD_TYPE", "MORTGAGE", null,
                data("Mortgage hold", TODAY, null, true, 0), 1, "second", MAKER, NOW);
        assertThatThrownBy(() -> service.submit(stale)).satisfies(code("STALE_CHANGE"));
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: only one pending change per entry at a time")
    void onePendingChangePerEntry() {
        service.submit(ChangeRequest.createItem("HOLD_TYPE", "DUP", null, data("A", TODAY, null, true, 0), "first reason",
                MAKER, NOW));
        assertThatThrownBy(() -> service.submit(ChangeRequest.createItem("HOLD_TYPE", "DUP", null,
                data("B", TODAY, null, true, 0), "second reason", new Actor("u-2", "other"), NOW)))
                .satisfies(code("CHANGE_ALREADY_PENDING"));
    }

    @Test
    @Tag("CC-110")
    @DisplayName("CC-110: duplicate items, missing lists and missing items are refused")
    void invalidTargets() {
        seedItem("DECEASED", null, data("Deceased", TODAY, null, true, 0));
        assertThatThrownBy(() -> service.submit(ChangeRequest.createItem("HOLD_TYPE", "DECEASED", null,
                data("Deceased", TODAY, null, true, 0), "dup", MAKER, NOW))).satisfies(code("ITEM_EXISTS"));
        assertThatThrownBy(() -> service.submit(ChangeRequest.createItem("NO_LIST", "X", null,
                data("X", TODAY, null, true, 0), "test reason", MAKER, NOW))).satisfies(code("LIST_NOT_FOUND"));
        assertThatThrownBy(() -> service.submit(ChangeRequest.updateItem("HOLD_TYPE", "MISSING", null,
                data("X", TODAY, null, true, 0), 1, "test reason", MAKER, NOW))).satisfies(code("ITEM_NOT_FOUND"));
        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), CHECKER)).satisfies(code("CHANGE_NOT_FOUND"));
        assertThatThrownBy(() -> service.item("HOLD_TYPE", "MISSING", null)).satisfies(code("ITEM_NOT_FOUND"));
        assertThatThrownBy(() -> service.history("HOLD_TYPE", "MISSING", null)).satisfies(code("ITEM_NOT_FOUND"));
    }

    @Test
    @Tag("CC-110")
    @DisplayName("CC-110: new lists are created through maker-checker, with optional parent hierarchy")
    void createListAndHierarchy() {
        submitAndApprove(ChangeRequest.createList("COUNTRY", new BilingualName("الدول", "Countries"), null, "new",
                MAKER, NOW));
        submitAndApprove(ChangeRequest.createList("CITY_TEST", new BilingualName("مدن", "Cities"), "COUNTRY", "new",
                MAKER, NOW));
        assertThat(service.lists()).extracting(ReferenceList::code).containsExactly("CITY_TEST", "COUNTRY", "HOLD_TYPE");
        assertThat(audit.actions()).contains("REFDATA_LIST_CREATED");

        submitAndApprove(ChangeRequest.createItem("COUNTRY", "XA", null, data("Country A", TODAY, null, true, 0),
                "seed", MAKER, NOW));

        ItemData noParent = data("City 1", TODAY, null, true, 0);
        assertThatThrownBy(() -> service.submit(ChangeRequest.createItem("CITY_TEST", "C1", null, noParent, "test reason",
                MAKER, NOW))).satisfies(code("PARENT_REQUIRED"));

        ItemData badParent = new ItemData(new BilingualName("م", "City 1"), true, TODAY, null, "ZZ", Map.of(), 0);
        assertThatThrownBy(() -> service.submit(ChangeRequest.createItem("CITY_TEST", "C1", null, badParent, "test reason",
                MAKER, NOW))).satisfies(code("PARENT_NOT_FOUND"));

        ItemData goodParent = new ItemData(new BilingualName("م", "City 1"), true, TODAY, null, "XA", Map.of(), 0);
        submitAndApprove(ChangeRequest.createItem("CITY_TEST", "C1", null, goodParent, "test reason", MAKER, NOW));
        assertThat(service.item("CITY_TEST", "C1", null).data().parentCode()).isEqualTo("XA");

        ItemData parentOnFlatList = new ItemData(new BilingualName("م", "Y"), true, TODAY, null, "XA", Map.of(), 0);
        assertThatThrownBy(() -> service.submit(ChangeRequest.createItem("HOLD_TYPE", "Y", null, parentOnFlatList,
                "test reason", MAKER, NOW))).satisfies(code("PARENT_NOT_ALLOWED"));

        assertThatThrownBy(() -> service.submit(ChangeRequest.createList("COUNTRY",
                new BilingualName("ق", "Again"), null, "dup", MAKER, NOW))).satisfies(code("LIST_EXISTS"));
    }

    @Test
    @Tag("CC-112")
    @DisplayName("CC-112: a regional variant overrides the national item in that region only")
    void regionalVariantOverridesNational() {
        seedItem("UNPAID_FINES", null, data("Unpaid fines", TODAY, null, true, 1));
        seedItem("UNPAID_FINES", "R2", data("Unpaid fines (region 2 wording)", TODAY, null, true, 1));

        assertThat(service.items("HOLD_TYPE", TODAY, null, false))
                .singleElement().satisfies(i -> assertThat(i.regionCode()).isNull());
        assertThat(service.items("HOLD_TYPE", TODAY, "R2", false))
                .singleElement().satisfies(i -> assertThat(i.regionCode()).isEqualTo("R2"));
        assertThat(service.items("HOLD_TYPE", TODAY, "R3", false))
                .singleElement().satisfies(i -> assertThat(i.regionCode()).isNull());
        assertThat(service.item("HOLD_TYPE", "UNPAID_FINES", "R2").regionCode()).isEqualTo("R2");
        assertThat(service.item("HOLD_TYPE", "UNPAID_FINES", "R3").regionCode()).isNull();
    }

    @Test
    @Tag("CC-110")
    @DisplayName("CC-110: queries return items in force on the date, active only unless asked, in sort order")
    void effectiveAndActiveFiltering() {
        seedItem("B_FUTURE", null, data("Future", TODAY.plusDays(10), null, true, 2));
        seedItem("A_ENDED", null, data("Ended", TODAY.minusDays(30), TODAY.minusDays(1), true, 1));
        seedItem("C_INACTIVE", null, data("Inactive", TODAY.minusDays(30), null, false, 3));
        seedItem("D_CURRENT", null, data("Current", TODAY.minusDays(30), null, true, 0));

        assertThat(service.items("HOLD_TYPE", null, null, false)).extracting(ReferenceItem::code)
                .containsExactly("D_CURRENT");
        assertThat(service.items("HOLD_TYPE", TODAY, null, true)).extracting(ReferenceItem::code)
                .containsExactly("D_CURRENT", "C_INACTIVE");
        assertThat(service.items("HOLD_TYPE", TODAY.plusDays(10), null, false)).extracting(ReferenceItem::code)
                .containsExactly("D_CURRENT", "B_FUTURE");
        assertThat(service.items("HOLD_TYPE", TODAY.minusDays(5), null, false)).extracting(ReferenceItem::code)
                .containsExactly("D_CURRENT", "A_ENDED");
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: a rejected change is not applied and is audited with its reason")
    void rejectedChangeNotApplied() {
        ChangeRequest change = ChangeRequest.createItem("HOLD_TYPE", "REJECT_ME", null,
                data("X", TODAY, null, true, 0), "add", MAKER, NOW);
        service.submit(change);
        service.reject(change.id(), CHECKER, "not in the law");

        assertThat(items.find("HOLD_TYPE", "REJECT_ME", null)).isEmpty();
        assertThat(service.change(change.id()).status()).isEqualTo(ChangeStatus.REJECTED);
        assertThat(audit.entries.getLast().action()).isEqualTo("REFDATA_CHANGE_REJECTED");
        assertThat(audit.entries.getLast().reason()).isEqualTo("not in the law");
        assertThat(service.changes(null)).hasSize(1);
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: update without base version is refused when prepared")
    void prepareUpdateNeedsBaseVersion() {
        assertThatThrownBy(() -> service.prepare(ChangeType.UPDATE_ITEM, "HOLD_TYPE", "X", null, null, null,
                data("X", TODAY, null, true, 0), null, "test reason", MAKER)).satisfies(code("BASE_VERSION_REQUIRED"));
        ChangeRequest list = service.prepare(ChangeType.CREATE_LIST, "NEW_L", null, null,
                new BilingualName("ق", "L"), null, null, null, "test reason", MAKER);
        assertThat(list.type()).isEqualTo(ChangeType.CREATE_LIST);
    }
}
