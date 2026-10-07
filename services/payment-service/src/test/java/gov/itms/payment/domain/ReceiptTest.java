package gov.itms.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FN-010")
class ReceiptTest {

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    private static final Instant ISSUED = Instant.parse("2026-10-06T18:00:00Z"); // 22:00 in Dubai

    private static Payable item(Payable.ItemType type) {
        return PayableTest.payable(type, type.name().substring(0, 3) + "-1", "XTS", 100);
    }

    private static Receipt receipt() {
        return new Receipt(UUID.randomUUID(), "R-NORTH-PORTAL-2026-00000001", UUID.randomUUID(),
                Receipt.Type.SERVICE, "NORTH", "PORTAL", Payment.Method.GATEWAY_CARD, "XTS", 100, List.of(), "c-1",
                ISSUED, LocalDate.ofInstant(ISSUED, DUBAI), Receipt.Status.ISSUED, null, null, null);
    }

    @Test
    @DisplayName("FN-010: the receipt type follows what was paid; anything mixed is a service receipt")
    void type() {
        assertThat(Receipt.typeOf(List.of(item(Payable.ItemType.VIOLATION), item(Payable.ItemType.VIOLATION))))
                .isEqualTo(Receipt.Type.VIOLATION);
        assertThat(Receipt.typeOf(List.of(item(Payable.ItemType.DEPOSIT)))).isEqualTo(Receipt.Type.DEPOSIT);
        assertThat(Receipt.typeOf(List.of(item(Payable.ItemType.GENERAL)))).isEqualTo(Receipt.Type.GENERAL);
        assertThat(Receipt.typeOf(List.of(item(Payable.ItemType.TRANSACTION)))).isEqualTo(Receipt.Type.SERVICE);
        assertThat(Receipt.typeOf(List.of(item(Payable.ItemType.VIOLATION), item(Payable.ItemType.TRANSACTION))))
                .isEqualTo(Receipt.Type.SERVICE);
    }

    @Test
    @Tag("FN-022")
    @DisplayName("FN-022: numbers are region-channel-year-sequence, zero-padded to eight digits")
    void number() {
        assertThat(Receipt.number("NORTH", "PORTAL", 2026, 1)).isEqualTo("R-NORTH-PORTAL-2026-00000001");
        assertThat(Receipt.number("SOUTH", "BACKOFFICE", 2027, 12_345_678))
                .isEqualTo("R-SOUTH-BACKOFFICE-2027-12345678");
    }

    @Test
    @Tag("FN-011")
    @DisplayName("FN-011: cancelled on its business day only (in the business zone), once, with who and why")
    void sameDayCancel() {
        Receipt r = receipt();
        assertThatThrownBy(() -> receipt().cancel("u-super", "late", ISSUED.plus(Duration.ofHours(2)), DUBAI))
                .as("00:00 in Dubai is the next business day").isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("RECEIPT_NOT_SAME_DAY");
        r.cancel("u-super", "wrong item", ISSUED.plus(Duration.ofMinutes(90)), DUBAI);
        assertThat(r.status()).isEqualTo(Receipt.Status.CANCELLED);
        assertThat(r.cancelledBy()).isEqualTo("u-super");
        assertThat(r.cancellationReason()).isEqualTo("wrong item");
        assertThatThrownBy(() -> r.cancel("u-super", "again", ISSUED, DUBAI)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("RECEIPT_CANCELLED");
    }
}
