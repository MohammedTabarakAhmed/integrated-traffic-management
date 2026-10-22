package gov.itms.violation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VF-047")
class PreservationOrderTest {

    private static Instant at(long offsetMinutes) {
        return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60 * offsetMinutes);
    }

    private static PreservationOrder open(String reference, String source) {
        return new PreservationOrder(UUID.randomUUID(), UUID.randomUUID(), "V-2026-000000001", reference, source,
                at(4), null, "court order received", "u-supervisor", at(5));
    }

    private static PreservationOrder closed(Instant start, Instant end, String reference) {
        return new PreservationOrder(UUID.randomUUID(), UUID.randomUUID(), "V-2026-000000001", reference,
                "Municipality", start, end, null, "u-supervisor", at(5));
    }

    @Test
    void anOrderKeepsItsReferenceAndWindow() {
        PreservationOrder running = open("PO-2026-0007", "Administrative Court");
        assertThat(running.reference()).isEqualTo("PO-2026-0007");
        assertThat(running.endAt()).isNull();
        assertThat(running.note()).isEqualTo("court order received");        // an order with an end date keeps it
        assertThat(closed(at(4), at(10), "PO-2026-0008").endAt()).isEqualTo(at(10));
    }

    @Test
    void orderDetailsAreChecked() {
        // each order needs its own reference
        assertThatThrownBy(() -> open("no reference", null)).isInstanceOf(DomainException.class)
                .extracting("code", "kind").containsExactly("ORDER_INVALID", DomainException.Kind.VALIDATION);
        assertThatThrownBy(() -> open("PO 0007 has spaces", "Administrative Court"))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ORDER_INVALID");
        assertThatThrownBy(() -> open("PO" + "9".repeat(60), "Administrative Court"))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ORDER_INVALID");
        // the issuing authority is recorded
        assertThatThrownBy(() -> open("PO-2026-0009", "  ")).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("ORDER_INVALID");
        // an order ends after it starts
        assertThatThrownBy(() -> closed(at(10), at(4), "PO-2026-0011")).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("ORDER_INVALID");
        // notes are bounded
        assertThatThrownBy(() -> new PreservationOrder(UUID.randomUUID(), UUID.randomUUID(), "V-2026-000000001",
                "PO-2026-0010", "Administrative Court", at(4), null, "x".repeat(501), "u-supervisor", at(5)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ORDER_INVALID");
    }
}
