package gov.itms.refdata.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.refdata.domain.ChangeRequest.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ChangeRequestTest {

    private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");

    private static ItemData data() {
        return new ItemData(new BilingualName("اختبار", "Test"), true, LocalDate.of(2026, 1, 1), null, null, Map.of(), 0);
    }

    private static ChangeRequest pending() {
        return ChangeRequest.createItem("HOLD_TYPE", "NEW_HOLD", null, data(), "new hold type", MAKER, T0);
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: a submitted change starts pending approval")
    void newChangeIsPending() {
        ChangeRequest c = pending();
        assertThat(c.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
        assertThat(c.checker()).isNull();
        assertThat(c.maker()).isEqualTo(MAKER);
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: a different user can approve a pending change")
    void checkerApproves() {
        ChangeRequest c = pending();
        c.approve(CHECKER, T0.plusSeconds(60));
        assertThat(c.status()).isEqualTo(ChangeStatus.APPROVED);
        assertThat(c.checker()).isEqualTo(CHECKER);
        assertThat(c.decidedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: the maker cannot approve their own change")
    void makerCannotApprove() {
        ChangeRequest c = pending();
        assertThatThrownBy(() -> c.approve(new Actor("u-maker", "same person"), T0))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.SEGREGATION_OF_DUTIES);
                    assertThat(e.requirementRefs()).contains("CC-002");
                });
        assertThat(c.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: the maker cannot reject their own change either")
    void makerCannotReject() {
        ChangeRequest c = pending();
        assertThatThrownBy(() -> c.reject(MAKER, "changed my mind", T0))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("SEGREGATION_OF_DUTIES"));
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: rejection records the checker and reason")
    void checkerRejectsWithReason() {
        ChangeRequest c = pending();
        c.reject(CHECKER, "  duplicate of COURT_ORDER  ", T0);
        assertThat(c.status()).isEqualTo(ChangeStatus.REJECTED);
        assertThat(c.decisionReason()).isEqualTo("duplicate of COURT_ORDER");
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: rejection without a reason is refused")
    void rejectionNeedsReason() {
        ChangeRequest c = pending();
        assertThatThrownBy(() -> c.reject(CHECKER, " ", T0))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REASON_REQUIRED"));
        assertThat(c.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: a decided change cannot be decided again")
    void decidedChangeIsFinal() {
        ChangeRequest c = pending();
        c.approve(CHECKER, T0);
        assertThatThrownBy(() -> c.reject(new Actor("u-other", "other"), "too late", T0))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("CHANGE_NOT_PENDING");
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT);
                });
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: every change needs a reason")
    void submissionNeedsReason() {
        assertThatThrownBy(() -> ChangeRequest.createItem("HOLD_TYPE", "X", null, data(), "", MAKER, T0))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REASON_REQUIRED"));
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: an update must state the version it is based on")
    void updateNeedsBaseVersion() {
        assertThatThrownBy(() -> new ChangeRequest(java.util.UUID.randomUUID(), ChangeType.UPDATE_ITEM, "HOLD_TYPE",
                "X", null, null, null, data(), null, "fix name", MAKER, T0, ChangeStatus.PENDING_APPROVAL, null,
                null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("BASE_VERSION_REQUIRED"));
    }

    @Test
    @Tag("CC-110")
    @DisplayName("CC-110: list and item changes must carry the right fields")
    void changeShapeIsChecked() {
        assertThatThrownBy(() -> new ChangeRequest(java.util.UUID.randomUUID(), ChangeType.CREATE_LIST, "NEW_LIST",
                "ITEM", null, new BilingualName("ق", "L"), null, null, null, "add", MAKER, T0,
                ChangeStatus.PENDING_APPROVAL, null, null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("CHANGE_SHAPE_INVALID"));

        ChangeRequest list = ChangeRequest.createList("NEW_LIST", new BilingualName("قائمة", "List"), null, "add list",
                MAKER, T0);
        assertThat(list.type()).isEqualTo(ChangeType.CREATE_LIST);
        assertThat(list.itemData()).isNull();
    }
}
