package gov.itms.licensing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("DL-021")
class LicenceTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");
    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");
    private static final Actor OFFICER = new Actor("u-officer", "officer");

    private static Licence.LicenceClass cls(String code) {
        return new Licence.LicenceClass(code, UUID.randomUUID(), TODAY, Licence.ClassStatus.ACTIVE, false, null);
    }

    private static Licence licence(LocalDate probation, List<Licence.Condition> conditions) {
        return Licence.issue(UUID.randomUUID(), "LX000001", "TF1", "NORTH", "CITIZEN", TODAY, TODAY.plusYears(10),
                probation, null, List.of(cls("LIGHT"), cls("MOTORCYCLE")), conditions);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (DomainException e) {
            return e.code();
        }
        return null;
    }

    @Test
    @DisplayName("a licence holds several classes, each with its own issue date and status; codes are distinct")
    void classes() {
        Licence l = licence(null, List.of());
        assertThat(l.classes()).extracting(Licence.LicenceClass::classCode).containsExactly("LIGHT", "MOTORCYCLE");
        assertThat(l.status()).isEqualTo(Licence.Status.ACTIVE);
        assertThat(code(() -> Licence.issue(UUID.randomUUID(), "N", "TF", "R", "C", TODAY, TODAY.plusYears(1), null,
                null, List.of(cls("LIGHT"), cls("LIGHT")), List.of()))).isEqualTo("LICENCE_CLASS_DUPLICATE");
        assertThat(code(() -> Licence.issue(UUID.randomUUID(), "N", "TF", "R", "C", TODAY, TODAY.plusYears(1), null,
                null, List.of(), List.of()))).isEqualTo("LICENCE_CLASSES_REQUIRED");
        assertThat(code(() -> Licence.issue(UUID.randomUUID(), "N", "TF", "R", "C", TODAY, TODAY, null, null,
                List.of(cls("LIGHT")), List.of()))).isEqualTo("LICENCE_EXPIRY_INVALID");
    }

    @Test
    @Tag("DL-050")
    @DisplayName("probation runs to its end date; an active licence past expiry reads EXPIRED")
    void probationAndExpiry() {
        Licence l = licence(TODAY.plusYears(2), List.of());
        assertThat(l.probationaryOn(TODAY.plusYears(2))).isTrue();
        assertThat(l.probationaryOn(TODAY.plusYears(2).plusDays(1))).isFalse();
        assertThat(licence(null, List.of()).probationaryOn(TODAY)).isFalse();
        assertThat(l.statusOn(TODAY.plusYears(10))).isEqualTo("ACTIVE");
        assertThat(l.statusOn(TODAY.plusYears(10).plusDays(1))).isEqualTo("EXPIRED");
    }

    @Test
    @Tag("DL-023")
    @DisplayName("conditions are added once and removed when present; codes and notes are checked")
    void conditions() {
        Licence l = licence(null, List.of(new Licence.Condition("CORRECTIVE_LENSES", null, TODAY)));
        l.addCondition(new Licence.Condition("HEARING_AID", " aid ", TODAY));
        assertThat(l.conditions()).extracting(Licence.Condition::note).containsExactly(null, "aid");
        assertThat(code(() -> l.addCondition(new Licence.Condition("HEARING_AID", null, TODAY))))
                .isEqualTo("CONDITION_EXISTS");
        l.removeCondition("HEARING_AID");
        assertThat(code(() -> l.removeCondition("HEARING_AID"))).isEqualTo("CONDITION_NOT_FOUND");
        assertThat(code(() -> new Licence.Condition("lower", null, TODAY))).isEqualTo("FIELD_INVALID");
        assertThat(code(() -> new Licence.Condition("X", "  ", TODAY))).isEqualTo("FIELD_INVALID");
        assertThat(code(() -> licence(null, List.of(new Licence.Condition("X", null, TODAY),
                new Licence.Condition("X", null, TODAY))))).isEqualTo("CONDITION_DUPLICATE");
    }

    @Test
    @Tag("DL-058")
    @DisplayName("a cancelled licence never changes")
    void ended() {
        Licence l = Licence.restore(UUID.randomUUID(), "N", "TF", "R", "C", Licence.Status.CANCELLED, TODAY,
                TODAY.plusYears(1), null, null, null, List.of(cls("LIGHT")), List.of(), 3);
        assertThat(code(() -> l.addCondition(new Licence.Condition("X", null, TODAY)))).isEqualTo("LICENCE_ENDED");
    }

    @Test
    @Tag("DL-036")
    @DisplayName("a transfer is used or reversed once; reversal needs a reason; no pass date in the future")
    void transfers() {
        ExaminationTransfer.Spec spec = new ExaminationTransfer.Spec("EX-1", "TF1", "LIGHT",
                ExaminationTransfer.Gearbox.AUTOMATIC, TODAY, "NORTH");
        ExaminationTransfer t = ExaminationTransfer.receive(UUID.randomUUID(), spec, OFFICER, NOW, TODAY);
        assertThat(code(() -> t.reverse(OFFICER, "x", NOW))).isEqualTo("REASON_INVALID");
        t.reverse(OFFICER, "recorded in error", NOW);
        assertThat(t.status()).isEqualTo(ExaminationTransfer.Status.REVERSED);
        assertThat(code(() -> t.usedBy(UUID.randomUUID(), NOW))).isEqualTo("TRANSFER_NOT_OPEN");

        ExaminationTransfer u = ExaminationTransfer.receive(UUID.randomUUID(), spec, OFFICER, NOW, TODAY);
        u.usedBy(UUID.randomUUID(), NOW);
        assertThat(code(() -> u.reverse(OFFICER, "too late", NOW))).isEqualTo("TRANSFER_NOT_OPEN");

        assertThat(code(() -> ExaminationTransfer.receive(UUID.randomUUID(), new ExaminationTransfer.Spec("EX-2",
                "TF1", "LIGHT", ExaminationTransfer.Gearbox.MANUAL, TODAY.plusDays(1), "NORTH"), OFFICER, NOW,
                TODAY))).isEqualTo("PASSED_IN_FUTURE");
        assertThat(code(() -> new ExaminationTransfer.Spec("EX 1", "TF1", "LIGHT",
                ExaminationTransfer.Gearbox.MANUAL, TODAY, "NORTH"))).isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new LicenceRequest(UUID.randomUUID(), LicenceRequest.Kind.ISSUE,
                LicenceRequest.Status.COMPLETED, null, "TF", "R", null, null, 0, null, null, null, "u", null, NOW, NOW)
                .completed(UUID.randomUUID(), NOW)).isInstanceOf(DomainException.class);
        assertThat(LicenceRequest.idOf("LR-not-a-uuid")).isNull();
        assertThat(LicenceRequest.idOf("VR-" + UUID.randomUUID())).isNull();
    }
}
