package gov.itms.appointment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CH-031: the domain rules of a queue ticket before any HTTP. */
@Tag("CH-031")
class QueueTicketTest {

    private static final Instant T0 = Instant.parse("2026-10-22T08:00:00Z");

    private static QueueTicket issued() {
        return QueueTicket.issue(UUID.randomUUID(), "C_DXB", "REGISTER_VEHICLE", "Q-C_DXB-251022-1", "u-1", T0);
    }

    @Test
    @DisplayName("CH-031: the ticket's own times track the waiting and the service")
    void times() {
        QueueTicket t = issued();
        assertThat(t.status()).isEqualTo(QueueTicket.Status.ISSUED);
        assertThat(t.waiting()).isNull();

        t.call("counter 3", T0.plusSeconds(213));
        assertThat(t.status()).isEqualTo(QueueTicket.Status.CALLED);
        assertThat(t.waiting().toSeconds()).isEqualTo(213);
        assertThat(t.service()).isNull();

        t.serve(T0.plusSeconds(213 + 470));
        assertThat(t.status()).isEqualTo(QueueTicket.Status.SERVED);
        assertThat(t.service().toSeconds()).isEqualTo(470);
    }

    @Test
    @DisplayName("CH-031: a ticket is called once, served from the counter, and closed tickets never change")
    void closedStaysClosed() {
        QueueTicket t = issued();
        assertThatThrownBy(() -> t.serve(T0)).isInstanceOf(DomainException.class)
                .hasMessageContaining("ISSUED");
        t.call("A", T0);
        assertThatThrownBy(() -> t.call("B", T0)).isInstanceOf(DomainException.class).hasMessageContaining("CALLED");
        t.serve(T0.plusSeconds(120));
        assertThatThrownBy(() -> t.serve(T0.plusSeconds(180))).isInstanceOf(DomainException.class).hasMessageContaining("SERVED");
        assertThatThrownBy(() -> t.leave("changed my mind", T0.plusSeconds(240))).isInstanceOf(DomainException.class)
                .hasMessageContaining("SERVED");
        assertThatThrownBy(() -> t.call("C", T0.plusSeconds(300))).isInstanceOf(DomainException.class).hasMessageContaining("SERVED");
    }

    @Test
    @DisplayName("CH-031: a departure closes without a service time; a reason is required")
    void departure() {
        QueueTicket t = issued();
        assertThatThrownBy(() -> t.leave("", T0)).isInstanceOf(DomainException.class);
        t.leave("queue too long", T0.plusSeconds(540));
        assertThat(t.status()).isEqualTo(QueueTicket.Status.LEFT);
        assertThat(t.waiting().toMinutes()).isEqualTo(9);
        assertThat(t.service()).isNull();
        assertThat(t.leftReason()).isEqualTo("queue too long");
    }

    @Test
    @DisplayName("CH-031: shape checks — centre, optional service, ticket number, counter, actor")
    void shapes() {
        assertThatThrownBy(() -> QueueTicket.issue(UUID.randomUUID(), "bad", "S", "Q-X-251022-1", "u", T0))
                .isInstanceOf(DomainException.class).hasMessageContaining("centre code");
        assertThatThrownBy(() -> issued().call(null, T0)).isInstanceOf(DomainException.class)
                .hasMessageContaining("counter");
        assertThatThrownBy(() -> QueueTicket.issue(UUID.randomUUID(), "C_1", "SSERV", "q-x", "u", T0))
                .isInstanceOf(DomainException.class).hasMessageContaining("ticket number");
        assertThatThrownBy(() -> QueueTicket.issue(UUID.randomUUID(), "C_1", "SSERV", "Q-X-1", "", T0))
                .isInstanceOf(DomainException.class).hasMessageContaining("issuedBy");
        assertThat(issued().serviceCode()).isEqualTo("REGISTER_VEHICLE");
    }
}
