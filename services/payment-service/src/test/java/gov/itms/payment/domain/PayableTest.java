package gov.itms.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FN-002")
public class PayableTest {

    public static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    public static Payable payable(Payable.ItemType type, String ref, String currency, long... amounts) {
        List<Payable.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            lines.add(new Payable.Line("REV_" + i, "AUTHORITY", null, amounts[i]));
        }
        return Payable.register(UUID.randomUUID(), type, ref, "TF-1", "NORTH", currency, lines, "svc", NOW);
    }

    @Test
    @Tag("FN-003")
    @DisplayName("FN-002, FN-003: a payable is itemised by financial item and beneficiary; its total is the sum")
    void itemisedTotal() {
        Payable p = payable(Payable.ItemType.TRANSACTION, "TXN-1", "XTS", 15000, 2500, 0);
        assertThat(p.total()).isEqualTo(17500);
        assertThat(p.lines()).extracting(Payable.Line::financialItem).containsExactly("REV_0", "REV_1", "REV_2");
        assertThat(p.status()).isEqualTo(Payable.Status.OPEN);
    }

    @Test
    @Tag("FN-011")
    @DisplayName("FN-002, FN-011: OPEN → PAID → OPEN again only by receipt cancellation; cancel only while OPEN")
    void transitions() {
        Payable p = payable(Payable.ItemType.VIOLATION, "VF-1", "XTS", 50000);
        p.paid();
        assertThat(p.status()).isEqualTo(Payable.Status.PAID);
        assertThatThrownBy(p::paid).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("PAYABLE_NOT_OPEN");
        assertThatThrownBy(p::cancel).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("PAYABLE_NOT_OPEN");
        p.reopen();
        assertThat(p.status()).isEqualTo(Payable.Status.OPEN);
        assertThatThrownBy(p::reopen).isInstanceOf(IllegalStateException.class);
        p.cancel();
        assertThat(p.status()).isEqualTo(Payable.Status.CANCELLED);
    }

    @Test
    @DisplayName("FN-002: zero totals, negative lines, bad references, currencies and regions are refused")
    void validation() {
        assertThatThrownBy(() -> payable(Payable.ItemType.GENERAL, "G-1", "XTS", 0))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("AMOUNT_INVALID");
        assertThatThrownBy(() -> new Payable.Line("REV", "AUTHORITY", null, -1))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> payable(Payable.ItemType.GENERAL, "g-1", "XTS", 10))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> payable(Payable.ItemType.GENERAL, "G-1", "xts", 10))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> Payable.register(UUID.randomUUID(), Payable.ItemType.GENERAL, "G-1", null, "north",
                "XTS", List.of(new Payable.Line("REV", "AUTHORITY", null, 1)), "svc", NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> Payable.register(UUID.randomUUID(), Payable.ItemType.GENERAL, "G-1", null, "NORTH",
                "XTS", List.of(), "svc", NOW)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
    }
}
