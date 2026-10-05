package gov.itms.hold.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.hold.domain.EligibilityPolicy.Block;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-032")
class EligibilityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final Subject VEHICLE = new Subject(Subject.Type.VEHICLE, "VIN-1");
    private static final EligibilityPolicy POLICY = new EligibilityPolicy(Map.of("UNPAID_FINES", "PAY_FINES",
            "EXPIRED_INSURANCE", "RENEW_INSURANCE"));
    private static final Actor OFFICER = new Actor("u-officer", "officer");

    private static Hold hold(String type, List<String> services, LocalDate start, LocalDate end) {
        return Hold.place(UUID.randomUUID(), new Hold.Spec(VEHICLE, type, "VIOLATIONS", "VIOLATIONS", null, "reason",
                start, end, services, List.of("HOLD_OFFICER")), OFFICER, Channel.BACKOFFICE, NOW, TODAY);
    }

    private static String code(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e.code();
        }
        return "none";
    }

    @Test
    @DisplayName("CC-032/Q-053: a disclosed type shows its code and next step; any other type is masked")
    void policyMasksUndisclosedTypes() {
        assertThat(POLICY.blockFor("UNPAID_FINES")).isEqualTo(new Block("UNPAID_FINES", "PAY_FINES"));
        assertThat(POLICY.blockFor("POLICE_CIRCULAR")).isEqualTo(new Block("RESTRICTED", "CONTACT_AUTHORITY"));
        assertThat(POLICY.blockFor("NEW_TYPE")).isEqualTo(new Block("RESTRICTED", "CONTACT_AUTHORITY"));
        assertThat(new EligibilityPolicy(null).blockFor("UNPAID_FINES").reasonCode()).isEqualTo("RESTRICTED");

        Map<String, String> withNull = new HashMap<>();
        withNull.put("UNPAID_FINES", null);
        for (Map<String, String> bad : List.of(Map.of("unpaid", "PAY"), Map.of("RESTRICTED", "PAY"),
                Map.of("UNPAID_FINES", "pay fines"), withNull)) {
            assertThatThrownBy(() -> new EligibilityPolicy(bad)).as(bad.toString())
                    .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        }
    }

    @Test
    @DisplayName("CC-032/CC-030: only holds in force that block the service count; reasons are not repeated")
    void onlyHoldsInForceForTheServiceBlock() {
        Hold fines1 = hold("UNPAID_FINES", List.of("*"), null, null);
        Hold fines2 = hold("UNPAID_FINES", List.of("VL_RENEW"), null, null);
        Hold circular = hold("POLICE_CIRCULAR", List.of("VL_RENEW", "VL_TRANSFER"), null, null);
        Hold court = hold("COURT_ORDER", List.of("*"), null, null);
        Hold otherService = hold("EXPIRED_INSURANCE", List.of("DL_RENEW"), null, null);
        Hold future = hold("EXPIRED_INSURANCE", List.of("*"), TODAY.plusDays(1), null);
        Hold endsToday = hold("EXPIRED_INSURANCE", List.of("*"), TODAY.minusDays(3), TODAY);
        Hold lifted = hold("MORTGAGE", List.of("*"), null, null);
        lifted.lift(OFFICER, Channel.BACKOFFICE, "released", NOW, TODAY);

        Eligibility result = Eligibility.evaluate("VL_RENEW",
                List.of(fines1, fines2, circular, court, otherService, future, endsToday, lifted), POLICY, TODAY);
        assertThat(result.eligible()).isFalse();
        assertThat(result.checkedOn()).isEqualTo(TODAY);
        assertThat(result.blocks()).containsExactly(new Block("UNPAID_FINES", "PAY_FINES"),
                new Block("RESTRICTED", "CONTACT_AUTHORITY"), new Block("EXPIRED_INSURANCE", "RENEW_INSURANCE"));
        assertThat(result.blockingHolds()).containsExactly(fines1.id(), fines2.id(), circular.id(), court.id(),
                endsToday.id());

        assertThat(Eligibility.evaluate("VL_RENEW", List.of(endsToday), POLICY, TODAY.plusDays(1)).eligible())
                .as("the end date is the last day the hold applies").isTrue();
        assertThat(Eligibility.evaluate("VL_RENEW", List.of(future), POLICY, TODAY.plusDays(1)).eligible())
                .as("a future hold applies from its start date").isFalse();
        assertThat(Eligibility.evaluate("DL_ISSUE", List.of(fines2, circular, otherService, lifted), POLICY, TODAY)
                .eligible()).isTrue();
        assertThat(Eligibility.evaluate("VL_RENEW", List.of(), POLICY, TODAY).blocks()).isEmpty();
    }

    @Test
    @DisplayName("CC-032: a check names a valid service code and 1-5 distinct subjects")
    void requestValidation() {
        assertThat(Eligibility.request("VL_RENEW", List.of(VEHICLE))).containsExactly(VEHICLE);
        assertThat(code(() -> Eligibility.request("*", List.of(VEHICLE)))).isEqualTo("INVALID_SERVICE_CODE");
        assertThat(code(() -> Eligibility.request(null, List.of(VEHICLE)))).isEqualTo("INVALID_SERVICE_CODE");
        assertThat(code(() -> Eligibility.request("VL_RENEW", List.of()))).isEqualTo("LIST_SIZE_INVALID");
        assertThat(code(() -> Eligibility.request("VL_RENEW", null))).isEqualTo("LIST_SIZE_INVALID");
        List<Subject> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new Subject(Subject.Type.VEHICLE, "VIN-" + i));
        }
        assertThat(code(() -> Eligibility.request("VL_RENEW", six))).isEqualTo("LIST_SIZE_INVALID");
        assertThat(code(() -> Eligibility.request("VL_RENEW", List.of(VEHICLE, VEHICLE)))).isEqualTo("LIST_DUPLICATES");
        assertThat(code(() -> Eligibility.request("VL_RENEW", Collections.singletonList(null))))
                .isEqualTo("LIST_DUPLICATES");
    }
}
