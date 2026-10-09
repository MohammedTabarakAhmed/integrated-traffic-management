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

/** Fee versions, maker-checker and quotes (CC-040, CC-042, CC-046, CC-047). */
@Tag("CC-046")
class FeeVersionTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");

    private static FeeVersion.Spec spec(String fee, FeeVersion.Applicability where, long amount, LocalDate from) {
        return new FeeVersion.Spec(fee, "VL_RENEW", "رسم", "Fee " + fee, where, "REV_VL", "AUTHORITY",
                new FeeMethod.Fixed(amount), from, null);
    }

    private static FeeVersion approved(String fee, FeeVersion.Applicability where, long amount, LocalDate from) {
        FeeVersion v = FeeVersion.submit(UUID.randomUUID(), 1, spec(fee, where, amount, from), "XTS", "new fee",
                MAKER, Channel.BACKOFFICE, NOW, TODAY);
        v.approve(CHECKER, Channel.BACKOFFICE, null, NOW, TODAY);
        return v;
    }

    @Test
    @DisplayName("maker-checker: the maker cannot approve; decided once; never retroactive")
    void makerChecker() {
        FeeVersion v = FeeVersion.submit(UUID.randomUUID(), 1, spec("F1", null, 100, TODAY), "XTS", "new fee",
                MAKER, Channel.BACKOFFICE, NOW, TODAY);
        assertThat(v.status()).isEqualTo(FeeVersion.Status.PENDING);
        assertThat(v.inForce(TODAY)).isFalse();
        assertThatThrownBy(() -> v.approve(MAKER, Channel.BACKOFFICE, null, NOW, TODAY))
                .hasFieldOrPropertyWithValue("code", "SAME_MAKER_CHECKER");
        assertThatThrownBy(() -> v.approve(CHECKER, Channel.BACKOFFICE, null, NOW, TODAY.plusDays(1)))
                .as("its start date has passed").hasFieldOrPropertyWithValue("code", "FEE_START_IN_PAST");
        v.approve(CHECKER, Channel.BACKOFFICE, "checked", NOW, TODAY);
        assertThat(v.inForce(TODAY)).isTrue();
        assertThatThrownBy(() -> v.reject(CHECKER, Channel.BACKOFFICE, "late", NOW))
                .hasFieldOrPropertyWithValue("code", "FEE_VERSION_DECIDED");
        assertThatThrownBy(() -> FeeVersion.submit(UUID.randomUUID(), 2, spec("F1", null, 100, TODAY.minusDays(1)),
                "XTS", "back-dated", MAKER, Channel.BACKOFFICE, NOW, TODAY))
                .hasFieldOrPropertyWithValue("code", "FEE_START_IN_PAST");
    }

    @Test
    @DisplayName("ending a version before its successor, and overlap detection")
    void endingAndOverlap() {
        FeeVersion v = approved("F1", null, 100, TODAY);
        assertThat(v.overlaps(TODAY.plusDays(10), null)).isTrue();
        v.endBefore(TODAY.plusDays(10));
        assertThat(v.effectiveTo()).isEqualTo(TODAY.plusDays(9));
        assertThat(v.inForce(TODAY.plusDays(9))).isTrue();
        assertThat(v.inForce(TODAY.plusDays(10))).isFalse();
        assertThat(v.overlaps(TODAY.plusDays(10), null)).isFalse();
    }

    @Test
    @Tag("CC-047")
    @DisplayName("a quote takes the most specific version per fee, lists lines by fee code, and adds them up")
    void quoteLines() {
        FeeVersion general = approved("BASE", null, 10000, TODAY);
        FeeVersion dubai = approved("BASE", new FeeVersion.Applicability("DXB", null, null), 12000, TODAY);
        FeeVersion card = approved("CARD", null, 2500, TODAY);
        FeeVersion other = approved("OTHER", new FeeVersion.Applicability("AUH", null, null), 999, TODAY);
        Quote.Request inDubai = new Quote.Request("VL_RENEW", "DXB", "CITIZEN", null, Map.of(), TODAY);
        List<Quote.Line> lines = Quote.calculate(List.of(general, dubai, card, other), inDubai);
        assertThat(lines).extracting(Quote.Line::feeCode).containsExactly("BASE", "CARD");
        assertThat(lines.getFirst().amount()).isEqualTo(12000);
        assertThat(lines.getFirst().financialItem()).isEqualTo("REV_VL");
        assertThat(lines.getFirst().beneficiary()).isEqualTo("AUTHORITY");
        Quote.Request inSharjah = new Quote.Request("VL_RENEW", "SHJ", "CITIZEN", null, Map.of(), TODAY);
        assertThat(Quote.calculate(List.of(general, dubai, card), inSharjah).getFirst().amount()).isEqualTo(10000);

        FeeVersion senior = approved("BASE", new FeeVersion.Applicability(null, "SENIOR", null), 5000, TODAY);
        Quote.Request ambiguous = new Quote.Request("VL_RENEW", "DXB", "SENIOR", null, Map.of(), TODAY);
        assertThatThrownBy(() -> Quote.calculate(List.of(dubai, senior), ambiguous))
                .hasFieldOrPropertyWithValue("code", "FEE_AMBIGUOUS");
        assertThat(Quote.calculate(List.of(), inDubai)).as("zero-fee service").isEmpty();
    }

    @Test
    @DisplayName("quote requests check codes and quantities")
    void quoteRequest() {
        assertThatThrownBy(() -> new Quote.Request("VL_RENEW", "dxb", "C", null, Map.of(), TODAY))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new Quote.Request("VL_RENEW", "DXB", "C", null,
                Map.of("SEAT", new BigDecimal("-1")), TODAY)).hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }
}
