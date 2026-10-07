package gov.itms.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-073")
class RecipientTest {

    private static final Template OPTIONAL = TemplateTest.template("VL_RENEWED", "VEHICLE", false);
    private static final Template LEGAL = TemplateTest.template("VF_NOTICE", "VEHICLE", true);

    @Test
    @DisplayName("CC-073: without preferences every reachable channel of the template is used")
    void noPreferences() {
        Recipient r = new Recipient("c-1", Language.EN, "+97150123456", "a.person@example.org", null, Map.of());
        assertThat(r.channelsFor(OPTIONAL)).containsExactlyInAnyOrder(DeliveryChannel.SMS, DeliveryChannel.EMAIL,
                DeliveryChannel.INBOX);
        assertThat(Recipient.unknown("c-2").channelsFor(OPTIONAL)).as("the inbox is always reachable")
                .containsExactly(DeliveryChannel.INBOX);
    }

    @Test
    @DisplayName("CC-073: preferences narrow optional notices; mandatory legal notices ignore them")
    void preferencesAndMandatory() {
        Recipient r = new Recipient("c-1", Language.NL, "+97150123456", "a.person@example.org", null,
                Map.of("VEHICLE", Set.of(DeliveryChannel.EMAIL)));
        assertThat(r.channelsFor(OPTIONAL)).containsExactly(DeliveryChannel.EMAIL);
        assertThat(r.channelsFor(LEGAL)).containsExactlyInAnyOrder(DeliveryChannel.SMS, DeliveryChannel.EMAIL,
                DeliveryChannel.INBOX);
        Recipient none = new Recipient("c-1", null, null, null, null, Map.of("VEHICLE", Set.of()));
        assertThat(none.channelsFor(OPTIONAL)).as("opted out of everything").isEmpty();
        assertThat(none.language()).as("national language by default").isEqualTo(Language.NL);
    }

    @Test
    @Tag("SEC-014")
    @DisplayName("SEC-014: contact points are masked for display")
    void masking() {
        assertThat(Recipient.mask(DeliveryChannel.SMS, "+97150123456")).isEqualTo("*********456");
        assertThat(Recipient.mask(DeliveryChannel.EMAIL, "a.person@example.org")).isEqualTo("a***@example.org");
        assertThat(Recipient.mask(DeliveryChannel.PUSH, "token-abc")).isEqualTo("device");
        assertThat(Recipient.mask(DeliveryChannel.SMS, null)).isNull();
    }

    @Test
    @DisplayName("CC-073: malformed contact points and categories are refused")
    void validation() {
        assertThatThrownBy(() -> new Recipient("c-1", null, "0501234", null, null, Map.of()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Recipient("c-1", null, null, "not-an-email", null, Map.of()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Recipient("c 1", null, null, null, null, Map.of()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Recipient("c-1", null, null, null, null,
                Map.of("vehicle", Set.of(DeliveryChannel.SMS))))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }
}
