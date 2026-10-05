package gov.itms.hold.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-030")
class HoldDomainTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    static final Actor OFFICER = new Actor("u-officer", "officer");
    static final Subject VEHICLE = new Subject(Subject.Type.VEHICLE, "VIN-1");

    static Hold.Spec spec(LocalDate start, LocalDate end, List<String> blocked) {
        return new Hold.Spec(VEHICLE, "UNPAID_FINES", "VIOLATIONS", "VIOLATIONS", null, " unpaid fine ", start, end,
                blocked, List.of("HOLD_OFFICER"));
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (DomainException e) {
            return e.code();
        }
        return "none";
    }

    @Test
    void placedHoldRecordsEverythingTheBrdNames() {
        Hold hold = Hold.place(UUID.randomUUID(), spec(null, null, List.of("VL_RENEW")), OFFICER, Channel.BACKOFFICE,
                NOW, TODAY);
        assertThat(hold.startDate()).as("start defaults to today").isEqualTo(TODAY);
        assertThat(hold.spec().reason()).isEqualTo("unpaid fine");
        assertThat(hold.storedStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.version()).isEqualTo(1);
        assertThat(hold.placed()).isEqualTo(new Hold.Change(OFFICER, Channel.BACKOFFICE, NOW, null));
        assertThat(hold.inForce(TODAY)).isTrue();
        assertThat(hold.blocks("VL_RENEW", TODAY)).isTrue();
        assertThat(hold.blocks("DL_RENEW", TODAY)).isFalse();
    }

    @Test
    void startAndEndDatesDecideWhenTheHoldApplies() {
        Hold future = Hold.place(UUID.randomUUID(), spec(TODAY.plusDays(2), null, List.of("*")), OFFICER,
                Channel.BACKOFFICE, NOW, TODAY);
        assertThat(future.status(TODAY)).isEqualTo(HoldStatus.ACTIVE);
        assertThat(future.inForce(TODAY)).isFalse();
        assertThat(future.blocks("ANY_SERVICE", TODAY.plusDays(2))).isTrue();

        Hold ending = Hold.place(UUID.randomUUID(), spec(TODAY.minusDays(10), TODAY, List.of("*")), OFFICER,
                Channel.BACKOFFICE, NOW, TODAY);
        assertThat(ending.inForce(TODAY)).as("the end date is the last day it applies").isTrue();
        assertThat(ending.status(TODAY.plusDays(1))).isEqualTo(HoldStatus.EXPIRED);
        assertThat(ending.blocks("ANY_SERVICE", TODAY.plusDays(1))).isFalse();
        assertThat(code(() -> ending.lift(OFFICER, Channel.BACKOFFICE, "paid", NOW, TODAY.plusDays(1))))
                .isEqualTo("HOLD_NOT_ACTIVE");

        assertThat(code(() -> Hold.place(UUID.randomUUID(), spec(TODAY, TODAY.minusDays(1), List.of("*")), OFFICER,
                Channel.BACKOFFICE, NOW, TODAY))).isEqualTo("HOLD_DATES_INVALID");
        assertThat(code(() -> Hold.place(UUID.randomUUID(), spec(TODAY.minusDays(9), TODAY.minusDays(1),
                List.of("*")), OFFICER, Channel.BACKOFFICE, NOW, TODAY))).isEqualTo("HOLD_END_IN_PAST");
    }

    @Test
    void liftedOnceWithAReason() {
        Hold hold = Hold.place(UUID.randomUUID(), spec(null, null, List.of("*")), OFFICER, Channel.BACKOFFICE, NOW,
                TODAY);
        assertThat(code(() -> hold.lift(OFFICER, Channel.PORTAL, " x ", NOW, TODAY))).isEqualTo("REASON_REQUIRED");
        hold.lift(OFFICER, Channel.PORTAL, " fines paid ", NOW.plusSeconds(60), TODAY);
        assertThat(hold.storedStatus()).isEqualTo(HoldStatus.LIFTED);
        assertThat(hold.status(TODAY)).isEqualTo(HoldStatus.LIFTED);
        assertThat(hold.lifted()).isEqualTo(new Hold.Change(OFFICER, Channel.PORTAL, NOW.plusSeconds(60),
                "fines paid"));
        assertThat(hold.version()).isEqualTo(2);
        assertThat(hold.inForce(TODAY)).isFalse();
        assertThat(code(() -> hold.lift(OFFICER, Channel.PORTAL, "again", NOW, TODAY))).isEqualTo("HOLD_NOT_ACTIVE");
    }

    @Test
    void specValidatesCodesListsAndReason() {
        assertThat(code(() -> spec(null, null, List.of("*", "VL_RENEW")))).isEqualTo("SERVICES_ALL_MIXED");
        assertThat(code(() -> spec(null, null, List.of("VL_RENEW", "VL_RENEW")))).isEqualTo("LIST_DUPLICATES");
        assertThat(code(() -> spec(null, null, Arrays.asList("VL_RENEW", null)))).isEqualTo("LIST_DUPLICATES");
        assertThat(code(() -> spec(null, null, List.of()))).isEqualTo("LIST_SIZE_INVALID");
        assertThat(code(() -> spec(null, null, List.of("vl_renew")))).isEqualTo("INVALID_SERVICE_CODE");
        assertThat(code(() -> new Hold.Spec(VEHICLE, "unpaid", "VIOLATIONS", "VIOLATIONS", null, "reason", null, null,
                List.of("*"), List.of("HOLD_OFFICER")))).isEqualTo("INVALID_CODE");
        assertThat(code(() -> new Hold.Spec(VEHICLE, "UNPAID", "VIOLATIONS", "VIOLATIONS", "bad ref", "reason", null,
                null, List.of("*"), List.of("HOLD_OFFICER")))).isEqualTo("INVALID_SOURCE_REF");
        assertThat(code(() -> new Hold.Spec(VEHICLE, "UNPAID", "VIOLATIONS", "VIOLATIONS", "COURT/2026:17", "reason",
                null, null, List.of("*"), List.of("officer")))).isEqualTo("INVALID_ROLE_CODE");
        assertThat(code(() -> new Hold.Spec(VEHICLE, "UNPAID", "VIOLATIONS", "VIOLATIONS", null, "x".repeat(501),
                null, null, List.of("*"), List.of("HOLD_OFFICER")))).isEqualTo("REASON_TOO_LONG");
        assertThat(code(() -> new Subject(null, "VIN-1"))).isEqualTo("SUBJECT_TYPE_REQUIRED");
        assertThat(code(() -> new Subject(Subject.Type.PLATE, "abc"))).isEqualTo("BUSINESS_NUMBER_INVALID");
        assertThatThrownBy(() -> new Actor(" ", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
