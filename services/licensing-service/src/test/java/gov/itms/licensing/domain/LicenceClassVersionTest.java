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

@Tag("DL-020")
class LicenceClassVersionTest {

    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");

    static LicenceClassVersion.CategoryRule rule(String category, int minAge, Integer maxAge, int validity) {
        return new LicenceClassVersion.CategoryRule(category, minAge, maxAge, List.of("THEORY", "YARD", "ROAD"), 20,
                false, validity, false);
    }

    static LicenceClassVersion.Spec spec(LocalDate from, LicenceClassVersion.CategoryRule... rules) {
        return new LicenceClassVersion.Spec("LIGHT", "مركبة خفيفة", "Light vehicle", "سيارات", "Cars and light vans",
                true, List.of(rules), from, null);
    }

    private static LicenceClassVersion pending() {
        return LicenceClassVersion.submit(UUID.randomUUID(), 1, spec(TODAY, rule(null, 18, null, 120)), "new class",
                MAKER, Channel.BACKOFFICE, NOW, TODAY);
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
    @Tag("DL-062")
    @DisplayName("a category's own rule wins; other categories fall back to the rule without a category")
    void ruleForCategory() {
        LicenceClassVersion.Spec s = spec(TODAY, rule(null, 18, null, 60), rule("CITIZEN", 18, null, 120));
        assertThat(s.ruleFor("CITIZEN").orElseThrow().validityMonths()).isEqualTo(120);
        assertThat(s.ruleFor("RESIDENT").orElseThrow().validityMonths()).isEqualTo(60);
        assertThat(spec(TODAY, rule("CITIZEN", 18, null, 120)).ruleFor("RESIDENT")).isEmpty();
    }

    @Test
    @DisplayName("minimum and optional maximum age are both honoured")
    void ages() {
        LicenceClassVersion.CategoryRule r = rule(null, 21, 65, 12);
        assertThat(r.ageAllowed(20)).isFalse();
        assertThat(r.ageAllowed(21)).isTrue();
        assertThat(r.ageAllowed(65)).isTrue();
        assertThat(r.ageAllowed(66)).isFalse();
        assertThat(rule(null, 17, null, 12).ageAllowed(90)).isTrue();
    }

    @Test
    @DisplayName("malformed rules are refused with a stable code")
    void invalidRules() {
        assertThat(code(() -> rule(null, 21, 20, 12))).isEqualTo("CLASS_AGES_INVALID");
        assertThat(code(() -> rule(null, 5, null, 12))).isEqualTo("FIELD_INVALID");
        assertThat(code(() -> rule(null, 18, null, 0))).isEqualTo("FIELD_INVALID");
        assertThat(code(() -> rule("bad category", 18, null, 12))).isEqualTo("FIELD_INVALID");
        assertThat(code(() -> new LicenceClassVersion.CategoryRule(null, 18, null, List.of("ROAD", "ROAD"), 0, false,
                12, false))).isEqualTo("LIST_DUPLICATES");
        assertThat(code(() -> new LicenceClassVersion.CategoryRule(null, 18, null, List.of(), -1, false, 12, false)))
                .isEqualTo("FIELD_INVALID");
        assertThat(code(() -> spec(TODAY, rule(null, 18, null, 12), rule(null, 19, null, 12))))
                .isEqualTo("LIST_DUPLICATES");
        assertThat(code(() -> spec(TODAY))).isEqualTo("LIST_SIZE_INVALID");
        assertThat(code(() -> new LicenceClassVersion.Spec("light", "x", "x", "x", "x", false,
                List.of(rule(null, 18, null, 12)), TODAY, null))).isEqualTo("FIELD_INVALID");
        assertThat(code(() -> new LicenceClassVersion.Spec("LIGHT", "x", "x", "x", "x", false,
                List.of(rule(null, 18, null, 12)), TODAY, TODAY.minusDays(1)))).isEqualTo("CLASS_DATES_INVALID");
    }

    @Test
    @DisplayName("a version cannot start in the past")
    void notInThePast() {
        assertThat(code(() -> LicenceClassVersion.submit(UUID.randomUUID(), 1,
                spec(TODAY.minusDays(1), rule(null, 18, null, 12)), "back-dated", MAKER, Channel.BACKOFFICE, NOW,
                TODAY))).isEqualTo("CLASS_START_IN_PAST");
    }

    @Test
    @Tag("CC-002")
    @DisplayName("the maker never approves their own version; a decided version is final")
    void makerChecker() {
        LicenceClassVersion v = pending();
        assertThatThrownBy(() -> v.approve(MAKER, Channel.BACKOFFICE, null, NOW, TODAY))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("SAME_MAKER_CHECKER");
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.SEGREGATION_OF_DUTIES);
                });
        v.approve(CHECKER, Channel.BACKOFFICE, "checked", NOW, TODAY);
        assertThat(v.status()).isEqualTo(LicenceClassVersion.Status.APPROVED);
        assertThat(v.inForce(TODAY)).isTrue();
        assertThat(v.inForce(TODAY.minusDays(1))).isFalse();
        assertThat(code(() -> v.reject(CHECKER, Channel.BACKOFFICE, "too late", NOW)))
                .isEqualTo("CLASS_VERSION_DECIDED");
    }

    @Test
    @DisplayName("a rejection needs a reason; ending a version moves its end earlier only")
    void rejectAndEnd() {
        LicenceClassVersion r = pending();
        assertThat(code(() -> r.reject(CHECKER, Channel.BACKOFFICE, " ", NOW))).isEqualTo("REASON_INVALID");
        r.reject(CHECKER, Channel.BACKOFFICE, "ages not agreed", NOW);
        assertThat(r.status()).isEqualTo(LicenceClassVersion.Status.REJECTED);

        LicenceClassVersion a = pending();
        a.approve(CHECKER, Channel.BACKOFFICE, null, NOW, TODAY);
        a.endBefore(TODAY.plusDays(30));
        assertThat(a.effectiveTo()).isEqualTo(TODAY.plusDays(29));
        a.endBefore(TODAY.plusDays(60));
        assertThat(a.effectiveTo()).isEqualTo(TODAY.plusDays(29));
        assertThat(a.overlaps(TODAY.plusDays(29), null)).isTrue();
        assertThat(a.overlaps(TODAY.plusDays(30), null)).isFalse();
    }
}
