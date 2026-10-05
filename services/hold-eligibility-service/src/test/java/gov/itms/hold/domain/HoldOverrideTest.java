package gov.itms.hold.domain;

import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.hold.domain.EligibilityPolicy.Block;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-034")
class HoldOverrideTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final Actor OFFICER = new Actor("u-officer", "officer");
    private static final Actor SUPERVISOR = new Actor("u-super", "supervisor");
    private static final HoldOverride.Document RULING = new HoldOverride.Document("COURT_RULING", "CR/2026/1");
    private static final String REF = "VL_RENEW-R1-2026-0000001";

    private static Hold hold(List<String> services, LocalDate start, LocalDate end) {
        return Hold.place(UUID.randomUUID(), new Hold.Spec(new Subject(Subject.Type.VEHICLE, "VIN-1"), "UNPAID_FINES",
                "VIOLATIONS", "VIOLATIONS", null, "reason", start, end, services, List.of("HOLD_OFFICER")), OFFICER,
                Channel.BACKOFFICE, NOW, TODAY);
    }

    private static HoldOverride grant(Hold hold, String reference, String reason, Actor actor) {
        return HoldOverride.grant(UUID.randomUUID(), hold, reference, reason, RULING, actor, Channel.BACKOFFICE, NOW,
                TODAY);
    }

    private static String code(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e.kind() + " " + e.code();
        }
        return "none";
    }

    @Test
    @DisplayName("CC-034: an override names one transaction, its service, a reason and a supporting document")
    void grantRecordsTheOverride() {
        Hold hold = hold(List.of("VL_RENEW"), null, null);
        HoldOverride o = grant(hold, REF, "  court ruling  ", SUPERVISOR);
        assertThat(o.holdId()).isEqualTo(hold.id());
        assertThat(o.transactionReference()).isEqualTo(REF);
        assertThat(o.serviceCode()).isEqualTo("VL_RENEW");
        assertThat(o.reason()).isEqualTo("court ruling");
        assertThat(o.document()).isEqualTo(RULING);
        assertThat(o.actor()).isEqualTo(SUPERVISOR);
        assertThat(o.at()).isEqualTo(NOW);
        assertThat(hold.storedStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.version()).as("the hold does not change").isEqualTo(1);
        assertThat(grant(hold(List.of("*"), TODAY.plusDays(3), null), REF, "court ruling", SUPERVISOR).serviceCode())
                .as("a hold for every service, not yet started, may be overridden").isEqualTo("VL_RENEW");
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-034/CC-002: the hold must be Active and block the service; the placer cannot override it")
    void grantRules() {
        Hold hold = hold(List.of("VL_RENEW"), null, null);
        assertThat(code(() -> grant(hold, "DL_ISSUE-R1-2026-0000001", "court ruling", SUPERVISOR)))
                .isEqualTo("CONFLICT OVERRIDE_NOT_NEEDED");
        assertThat(code(() -> grant(hold, REF, "court ruling", new Actor("u-officer", "another name"))))
                .isEqualTo("SEGREGATION_OF_DUTIES OVERRIDE_BY_PLACER");
        assertThat(code(() -> grant(hold, REF, " x ", SUPERVISOR))).isEqualTo("VALIDATION REASON_REQUIRED");
        assertThat(code(() -> grant(hold, REF, null, SUPERVISOR))).isEqualTo("VALIDATION REASON_REQUIRED");
        for (String bad : new String[] {null, "VL_RENEW", "VL_RENEW-R1-2026-1", "vl_renew-R1-2026-0000001",
                "VL_RENEW-R1-26-0000001"}) {
            assertThat(code(() -> grant(hold, bad, "court ruling", SUPERVISOR))).as(String.valueOf(bad))
                    .isEqualTo("VALIDATION INVALID_TRANSACTION_REFERENCE");
        }

        Hold lifted = hold(List.of("*"), null, null);
        lifted.lift(OFFICER, Channel.BACKOFFICE, "paid", NOW, TODAY);
        assertThat(code(() -> grant(lifted, REF, "court ruling", SUPERVISOR)))
                .isEqualTo("CONFLICT OVERRIDE_HOLD_NOT_ACTIVE");
        Hold endsToday = hold(List.of("*"), TODAY.minusDays(5), TODAY);
        assertThat(code(() -> HoldOverride.grant(UUID.randomUUID(), endsToday, REF, "court ruling", RULING,
                SUPERVISOR, Channel.BACKOFFICE, NOW, TODAY.plusDays(1)))).isEqualTo("CONFLICT OVERRIDE_HOLD_NOT_ACTIVE");
    }

    @Test
    @DisplayName("CC-034: the supporting document needs a type code and a number")
    void documentRules() {
        assertThat(code(() -> new HoldOverride.Document(null, "CR-1"))).isEqualTo("VALIDATION INVALID_CODE");
        assertThat(code(() -> new HoldOverride.Document("court ruling", "CR-1"))).isEqualTo("VALIDATION INVALID_CODE");
        assertThat(code(() -> new HoldOverride.Document("COURT_RULING", null)))
                .isEqualTo("VALIDATION INVALID_DOCUMENT_NUMBER");
        assertThat(code(() -> new HoldOverride.Document("COURT_RULING", "CR 1")))
                .isEqualTo("VALIDATION INVALID_DOCUMENT_NUMBER");
        assertThat(code(() -> new HoldOverride.Document("COURT_RULING", "x".repeat(101))))
                .isEqualTo("VALIDATION INVALID_DOCUMENT_NUMBER");
    }

    @Test
    @Tag("CC-032")
    @DisplayName("CC-034/CC-032: an overridden hold does not block, and is listed as overridden only if it would")
    void evaluationSkipsOverriddenHolds() {
        EligibilityPolicy policy = new EligibilityPolicy(Map.of("UNPAID_FINES", "PAY_FINES"));
        Hold renew = hold(List.of("VL_RENEW"), null, null);
        Hold all = hold(List.of("*"), null, null);
        Hold other = hold(List.of("DL_ISSUE"), null, null);

        Eligibility result = Eligibility.evaluate("VL_RENEW", List.of(renew, all, other),
                Set.of(renew.id(), other.id()), policy, TODAY);
        assertThat(result.blockingHolds()).containsExactly(all.id());
        assertThat(result.overriddenHolds()).containsExactly(renew.id());
        assertThat(result.blocks()).containsExactly(new Block("UNPAID_FINES", "PAY_FINES"));

        Eligibility allOverridden = Eligibility.evaluate("VL_RENEW", List.of(renew, all),
                Set.of(renew.id(), all.id()), policy, TODAY);
        assertThat(allOverridden.eligible()).isTrue();
        assertThat(allOverridden.overriddenHolds()).containsExactly(renew.id(), all.id());
    }
}
