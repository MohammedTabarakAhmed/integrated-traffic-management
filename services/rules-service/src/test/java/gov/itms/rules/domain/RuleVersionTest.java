package gov.itms.rules.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-051")
class RuleVersionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");

    static RuleVersion.Spec spec(String region, LocalDate from) {
        return new RuleVersion.Spec("LICENCE_AGE_MINIMUM", "الحد الأدنى للعمر", "Minimum age", region,
                DecisionTableTest.minimumAge(DecisionTable.HitPolicy.FIRST), from, null);
    }

    @Test
    @DisplayName("CC-051, CC-002: the maker cannot approve their own rule version; another person can, once")
    void makerChecker() {
        RuleVersion v = RuleVersion.submit(UUID.randomUUID(), 1, spec(null, TODAY), "initial rule", MAKER,
                Channel.BACKOFFICE, NOW, TODAY);
        assertThatThrownBy(() -> v.approve(MAKER, Channel.BACKOFFICE, null, NOW, TODAY))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SAME_MAKER_CHECKER");
        v.approve(CHECKER, Channel.BACKOFFICE, "checked", NOW, TODAY);
        assertThat(v.status()).isEqualTo(RuleVersion.Status.APPROVED);
        assertThat(v.inForce(TODAY)).isTrue();
        assertThatThrownBy(() -> v.reject(CHECKER, Channel.BACKOFFICE, "too late", NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_VERSION_DECIDED");
    }

    @Test
    @DisplayName("CC-051: rules never change retroactively")
    void noPastStart() {
        assertThatThrownBy(() -> RuleVersion.submit(UUID.randomUUID(), 1, spec(null, TODAY.minusDays(1)), "late rule",
                MAKER, Channel.BACKOFFICE, NOW, TODAY))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_START_IN_PAST");
        RuleVersion v = RuleVersion.submit(UUID.randomUUID(), 1, spec("NORTH", TODAY), "regional rule", MAKER,
                Channel.BACKOFFICE, NOW, TODAY);
        assertThatThrownBy(() -> v.approve(CHECKER, Channel.BACKOFFICE, null, NOW, TODAY.plusDays(1)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_START_IN_PAST");
    }

    @Test
    @DisplayName("CC-051: a successor ends the previous version the day before")
    void successorEndsPrevious() {
        RuleVersion v = RuleVersion.submit(UUID.randomUUID(), 1, spec(null, TODAY), "initial rule", MAKER,
                Channel.BACKOFFICE, NOW, TODAY);
        v.approve(CHECKER, Channel.BACKOFFICE, null, NOW, TODAY);
        v.endBefore(TODAY.plusDays(30));
        assertThat(v.effectiveTo()).isEqualTo(TODAY.plusDays(29));
        assertThat(v.inForce(TODAY.plusDays(29))).isTrue();
        assertThat(v.inForce(TODAY.plusDays(30))).isFalse();
    }

    @Test
    @DisplayName("CC-050: codes, regions and reasons are checked")
    void fields() {
        assertThatThrownBy(() -> new RuleVersion.Spec("bad code", "x", "x", null,
                DecisionTableTest.minimumAge(DecisionTable.HitPolicy.FIRST), TODAY, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> RuleVersion.submit(UUID.randomUUID(), 1, spec(null, TODAY), "x", MAKER,
                Channel.BACKOFFICE, NOW, TODAY))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("REASON_INVALID");
    }
}
