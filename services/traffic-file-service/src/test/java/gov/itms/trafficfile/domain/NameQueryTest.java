package gov.itms.trafficfile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-101")
class NameQueryTest {

    @Test
    @DisplayName("letters of either script, spaces and . ' - are a name; blanks around are dropped")
    void accepted() {
        assertThat(new NameQuery("  Al-Mansoori ", null, 20).text()).isEqualTo("Al-Mansoori");
        assertThat(new NameQuery("مُحَمَّد", TrafficFile.Kind.INDIVIDUAL, 1).kind())
                .isEqualTo(TrafficFile.Kind.INDIVIDUAL);
        assertThat(new NameQuery("O'Neil J.", null, 5).text()).isEqualTo("O'Neil J.");
    }

    @Test
    @DisplayName("digits, wildcards, one letter or too long are refused")
    void refused() {
        for (String text : new String[] {null, "", " a ", "12345", "Ali%", "a_b", "--", "x".repeat(101)}) {
            assertThatThrownBy(() -> new NameQuery(text, null, 20)).as(String.valueOf(text))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.code()).isEqualTo("NAME_QUERY_INVALID"));
        }
        assertThatThrownBy(() -> new NameQuery("Ali", null, 0)).isInstanceOf(DomainException.class);
    }
}
