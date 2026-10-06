package gov.itms.fees.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exemptions, discounts and levies in quotes (CC-043, CC-045). */
@Tag("CC-043")
class FeeAdjustmentTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");

    private static FeeVersion fee(String code, long amount) {
        FeeVersion v = FeeVersion.submit(UUID.randomUUID(), 1, new FeeVersion.Spec(code, "VL_RENEW", "رسم", code,
                null, "REV_" + code, "AUTHORITY", new FeeMethod.Fixed(amount), TODAY, null), "XTS", "new fee", MAKER,
                Channel.BACKOFFICE, NOW, TODAY);
        v.approve(CHECKER, Channel.BACKOFFICE, null, NOW, TODAY);
        return v;
    }

    private static FeeAdjustment approved(FeeAdjustment.Spec spec) {
        FeeAdjustment a = FeeAdjustment.submit(UUID.randomUUID(), spec, "new adjustment", MAKER, Channel.BACKOFFICE,
                NOW, TODAY);
        a.approve(CHECKER, Channel.BACKOFFICE, null, NOW, TODAY);
        return a;
    }

    private static FeeAdjustment.Spec spec(String code, FeeAdjustment.Kind kind, List<String> fees, String category,
                                           String campaign, String percent, Long amount) {
        boolean levy = kind == FeeAdjustment.Kind.LEVY;
        return new FeeAdjustment.Spec(code, kind, "VL_RENEW", "تعديل", code, fees, category, campaign,
                percent == null ? null : new BigDecimal(percent), amount, levy ? "VAT_PAYABLE" : null,
                levy ? "TAX_AUTHORITY" : null, TODAY, null);
    }

    private static Quote.Request request(String category, String campaign) {
        return new Quote.Request("VL_RENEW", "DXB", category, null, Map.of(), TODAY, campaign, null);
    }

    @Test
    @DisplayName("the most favourable exemption or discount applies per line; they do not stack")
    void bestDiscountPerLine() {
        List<FeeVersion> fees = List.of(fee("BASE", 10000), fee("CARD", 2000));
        List<FeeAdjustment> adjustments = List.of(
                approved(spec("SENIOR_25", FeeAdjustment.Kind.DISCOUNT, List.of(), "SENIOR", null, "25", null)),
                approved(spec("CAMPAIGN_3000", FeeAdjustment.Kind.DISCOUNT, List.of("BASE"), null, "SUMMER", null,
                        3000L)),
                approved(spec("POD_EXEMPT", FeeAdjustment.Kind.EXEMPTION, List.of("CARD"), "DETERMINATION", null,
                        null, null)));

        List<Quote.Line> senior = Quote.calculate(fees, adjustments, request("SENIOR", "SUMMER"));
        assertThat(senior.get(0).discount()).as("3000 beats 25% of 10000").isEqualTo(3000);
        assertThat(senior.get(0).adjustmentCode()).isEqualTo("CAMPAIGN_3000");
        assertThat(senior.get(0).amount()).isEqualTo(7000);
        assertThat(senior.get(1).discount()).as("25% of the card fee").isEqualTo(500);

        List<Quote.Line> pod = Quote.calculate(fees, adjustments, request("DETERMINATION", null));
        assertThat(pod.get(0).discount()).isZero();
        assertThat(pod.get(1).amount()).as("card fee waived").isZero();
        assertThat(pod.get(1).gross()).isEqualTo(2000);

        List<Quote.Line> plain = Quote.calculate(fees, adjustments, request("CITIZEN", null));
        assertThat(plain).allMatch(l -> l.discount() == 0 && l.adjustmentCode() == null);
    }

    @Test
    @Tag("CC-045")
    @DisplayName("a levy adds a line on the net amount of the fees it covers, paid to its own item and beneficiary")
    void levies() {
        List<FeeVersion> fees = List.of(fee("BASE", 10000), fee("CARD", 2000));
        List<FeeAdjustment> adjustments = List.of(
                approved(spec("VAT", FeeAdjustment.Kind.LEVY, List.of(), null, null, "5", null)),
                approved(spec("SENIOR_50", FeeAdjustment.Kind.DISCOUNT, List.of("BASE"), "SENIOR", null, "50",
                        null)));
        List<Quote.Line> lines = Quote.calculate(fees, adjustments, request("SENIOR", null));
        assertThat(lines).hasSize(3);
        Quote.Line vat = lines.get(2);
        assertThat(vat.kind()).isEqualTo(Quote.LineKind.LEVY);
        assertThat(vat.amount()).as("5% of (5000 + 2000)").isEqualTo(350);
        assertThat(vat.financialItem()).isEqualTo("VAT_PAYABLE");
        assertThat(vat.beneficiary()).isEqualTo("TAX_AUTHORITY");
        assertThat(Quote.calculate(List.of(), adjustments, request("SENIOR", null))).as("no fees, no levy")
                .isEmpty();
    }

    @Test
    @DisplayName("adjustment specs are checked; pending or out-of-date adjustments do not apply")
    void rules() {
        assertThatThrownBy(() -> spec("X", FeeAdjustment.Kind.DISCOUNT, List.of(), null, null, "10", null))
                .hasFieldOrPropertyWithValue("code", "ADJUSTMENT_TARGET_REQUIRED");
        assertThatThrownBy(() -> spec("X", FeeAdjustment.Kind.DISCOUNT, List.of(), "SENIOR", null, "10", 5L))
                .hasFieldOrPropertyWithValue("code", "DISCOUNT_VALUE_REQUIRED");
        assertThatThrownBy(() -> spec("X", FeeAdjustment.Kind.DISCOUNT, List.of(), "SENIOR", null, "150", null))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new FeeAdjustment.Spec("X", FeeAdjustment.Kind.LEVY, "VL_RENEW", "a", "b", null,
                null, null, new BigDecimal("5"), null, null, null, TODAY, null))
                .hasFieldOrPropertyWithValue("code", "LEVY_INVALID");
        FeeAdjustment pending = FeeAdjustment.submit(UUID.randomUUID(),
                spec("P", FeeAdjustment.Kind.EXEMPTION, List.of(), "SENIOR", null, null, null), "pending one", MAKER,
                Channel.BACKOFFICE, NOW, TODAY);
        assertThat(pending.appliesTo(request("SENIOR", null))).isFalse();
        assertThatThrownBy(() -> pending.approve(MAKER, Channel.BACKOFFICE, null, NOW, TODAY))
                .hasFieldOrPropertyWithValue("code", "SAME_MAKER_CHECKER");
        assertThat(FeeAdjustment.percentOf(333, new BigDecimal("12.5"))).as("41.625 rounds half up").isEqualTo(42);
    }
}
