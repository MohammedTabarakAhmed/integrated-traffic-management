package gov.itms.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FN-002")
class BasketTest {

    @Test
    @DisplayName("FN-002: a basket fixes the total of open items of one currency; the region defaults to the first item")
    void totalAndRegion() {
        Payable a = PayableTest.payable(Payable.ItemType.TRANSACTION, "TXN-1", "XTS", 15000);
        Payable b = PayableTest.payable(Payable.ItemType.VIOLATION, "VF-1", "XTS", 50000, 1000);
        Basket basket = Basket.of(UUID.randomUUID(), List.of(a, b), null, "c-1", PayableTest.NOW);
        assertThat(basket.totalMinor()).isEqualTo(66000);
        assertThat(basket.region()).isEqualTo("NORTH");
        assertThat(basket.payableIds()).containsExactly(a.id(), b.id());
        assertThat(Basket.of(UUID.randomUUID(), List.of(a), "SOUTH", "c-1", PayableTest.NOW).region())
                .isEqualTo("SOUTH");
        Basket paid = basket.paid();
        assertThat(paid.status()).isEqualTo(Basket.Status.PAID);
        assertThatThrownBy(paid::requireOpen).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("BASKET_PAID");
    }

    @Test
    @DisplayName("FN-002: mixed currencies, closed items, empty and duplicate baskets are refused")
    void refusals() {
        Payable xts = PayableTest.payable(Payable.ItemType.TRANSACTION, "TXN-1", "XTS", 100);
        Payable usd = PayableTest.payable(Payable.ItemType.TRANSACTION, "TXN-2", "USD", 100);
        assertThatThrownBy(() -> Basket.of(UUID.randomUUID(), List.of(xts, usd), null, "c-1", PayableTest.NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CURRENCY_MIXED");
        assertThatThrownBy(() -> Basket.of(UUID.randomUUID(), List.of(), null, "c-1", PayableTest.NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("BASKET_INVALID");
        assertThatThrownBy(() -> Basket.of(UUID.randomUUID(), List.of(xts, xts), null, "c-1", PayableTest.NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("BASKET_INVALID");
        Payable paid = PayableTest.payable(Payable.ItemType.TRANSACTION, "TXN-3", "XTS", 100);
        paid.paid();
        assertThatThrownBy(() -> Basket.of(UUID.randomUUID(), List.of(paid), null, "c-1", PayableTest.NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("PAYABLE_NOT_OPEN");
    }
}
