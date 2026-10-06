package gov.itms.trafficfile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Fuzzy name matching for probable duplicates (DL-009). */
@Tag("DL-009")
class NameSimilarityTest {

    @Test
    @DisplayName("Jaro-Winkler reference values")
    void jaroWinkler() {
        assertThat(NameSimilarity.jaroWinkler("martha", "marhta")).isCloseTo(0.961, within(0.001));
        assertThat(NameSimilarity.jaroWinkler("dwayne", "duane")).isCloseTo(0.84, within(0.001));
        assertThat(NameSimilarity.jaroWinkler("abc", "xyz")).isZero();
        assertThat(NameSimilarity.jaroWinkler("same", "same")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("normalising folds case, punctuation, diacritics and Arabic short vowels")
    void normalise() {
        assertThat(NameSimilarity.normalise("Al-Mansoori")).isEqualTo("al mansoori");
        assertThat(NameSimilarity.normalise("  José  ")).isEqualTo("jose");
        assertThat(NameSimilarity.normalise("مُحَمَّد")).isEqualTo("محمد");
        assertThat(NameSimilarity.normalise("مـحمد")).isEqualTo("محمد");
    }

    @Test
    @DisplayName("the better of the two languages counts; a missing language counts as no match")
    void bestLanguage() {
        PersonName a = new PersonName("أحمد", "المنصوري", "Ahmed", "Al-Mansoori");
        PersonName b = new PersonName(null, null, "Ahmad", "Al Mansouri");
        PersonName c = new PersonName("أحمد", "المنصوري", null, null);
        assertThat(NameSimilarity.of(a, b)).isGreaterThan(0.9);
        assertThat(NameSimilarity.of(a, c)).isEqualTo(1.0);
        assertThat(NameSimilarity.of(b, c)).isZero();
        assertThat(NameSimilarity.of(a, new PersonName(null, null, "Maria", "Santos"))).isLessThan(0.7);
    }
}
