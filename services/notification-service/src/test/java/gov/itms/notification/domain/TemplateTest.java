package gov.itms.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-070")
public class TemplateTest {

    public static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    public static final Actor ADMIN = new Actor("u-admin", "admin");

    /** SMS, email and inbox content with a {{plate}} placeholder. */
    public static Template template(String code, String category, boolean mandatory) {
        return new Template(code, 1, category, mandatory, Map.of(
                DeliveryChannel.SMS, new Template.Content(null, null, "رقم {{plate}}", "Plate {{plate}} renewed"),
                DeliveryChannel.EMAIL, new Template.Content("تجديد", "Renewal", "رقم {{plate}}",
                        "Your plate {{plate}} was renewed"),
                DeliveryChannel.INBOX, new Template.Content("تجديد", "Renewal", "رقم {{plate}}",
                        "Plate {{plate}} renewed")), ADMIN, NOW);
    }

    @Test
    @DisplayName("CC-070: rendering fills placeholders in the recipient's language; subjects only where they exist")
    void rendersBilingual() {
        Template t = template("VL_RENEWED", "VEHICLE", false);
        Template.Rendered en = t.render(DeliveryChannel.EMAIL, Language.EN, Map.of("plate", "A 123"));
        assertThat(en.subject()).isEqualTo("Renewal");
        assertThat(en.body()).isEqualTo("Your plate A 123 was renewed");
        Template.Rendered nl = t.render(DeliveryChannel.SMS, Language.NL, Map.of("plate", "A 123"));
        assertThat(nl.subject()).isNull();
        assertThat(nl.body()).isEqualTo("رقم A 123");
        assertThat(t.render(DeliveryChannel.SMS, Language.EN, Map.of("plate", "$1 \\x")).body())
                .as("values are inserted literally").isEqualTo("Plate $1 \\x renewed");
    }

    @Test
    @DisplayName("CC-070: a placeholder without data is refused; a channel without content cannot be rendered")
    void missingData() {
        Template t = template("VL_RENEWED", "VEHICLE", false);
        assertThatThrownBy(() -> t.render(DeliveryChannel.SMS, Language.EN, Map.of()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEMPLATE_DATA_MISSING");
        assertThatThrownBy(() -> t.render(DeliveryChannel.PUSH, Language.EN, Map.of("plate", "A")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CC-070: email and inbox need subjects, SMS and push have none; bodies have channel limits")
    void channelRules() {
        assertThatThrownBy(() -> new Template("T_ONE", 1, "VEHICLE", false, Map.of(DeliveryChannel.EMAIL,
                new Template.Content(null, null, "نص", "text")), ADMIN, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEMPLATE_INVALID");
        assertThatThrownBy(() -> new Template("T_ONE", 1, "VEHICLE", false, Map.of(DeliveryChannel.SMS,
                new Template.Content("s", "s", "نص", "text")), ADMIN, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEMPLATE_INVALID");
        assertThatThrownBy(() -> new Template("T_ONE", 1, "VEHICLE", false, Map.of(DeliveryChannel.SMS,
                new Template.Content(null, null, "x".repeat(1001), "text")), ADMIN, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEMPLATE_INVALID");
        assertThatThrownBy(() -> new Template("T_ONE", 1, "VEHICLE", false, Map.of(DeliveryChannel.PUSH,
                new Template.Content(null, null, "نص", "x".repeat(501))), ADMIN, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEMPLATE_INVALID");
        assertThatThrownBy(() -> new Template("bad", 1, "VEHICLE", false, Map.of(DeliveryChannel.SMS,
                new Template.Content(null, null, "نص", "text")), ADMIN, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Template("T_ONE", 1, "VEHICLE", false, Map.of(), ADMIN, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }
}
