package gov.itms.crossborder.application;

import gov.itms.crossborder.application.CrossborderService.Message;
import gov.itms.crossborder.application.CrossborderService.Partner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers outbound messages to partner states (XB-001, XB-008): real-time partners message by message on every run,
 * daily-batch partners all due messages in one batch. Each attempt is claimed in its own transaction, sent outside any
 * transaction, and its outcome recorded in another, so a slow partner never holds database locks and a second
 * instance never sends the same attempt.
 */
public class ExchangeDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeDelivery.class);

    private final CrossborderService exchange;
    private final Ports.PartnerTransport transport;
    private final int batchSize;

    public ExchangeDelivery(CrossborderService exchange, Ports.PartnerTransport transport, int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("itms.crossborder.delivery-batch-size must be 1..1000");
        }
        this.exchange = exchange;
        this.transport = transport;
        this.batchSize = batchSize;
    }

    /** Every active partner in this mode; returns how many messages were accepted. */
    public int deliverAll(String mode) {
        int accepted = 0;
        for (Partner p : exchange.partners(mode)) {
            try {
                accepted += deliver(p, null, null);
            } catch (RuntimeException e) {
                LOG.warn("Delivery to partner {} stopped: {}", p.code(), e.getClass().getSimpleName());
            }
        }
        return accepted;
    }

    /** Delivers what is due for one partner, optionally only messages that occurred in [from, to). */
    public int deliver(String partner, Instant from, Instant to) {
        Partner p = exchange.partner(partner).orElseThrow(() -> CrossborderService.missing("PARTNER_NOT_FOUND",
                "partner state is not configured", "XB-008"));
        if (!"ACTIVE".equals(p.status())) {
            throw CrossborderService.conflict("PARTNER_INACTIVE", "partner state is suspended", "XB-008");
        }
        return deliver(p, from, to);
    }

    private int deliver(Partner p, Instant from, Instant to) {
        if (p.endpointUrl() == null) {
            return 0;
        }
        List<Message> due = exchange.due(p.code(), from, to, batchSize);
        List<Message> claimed = new ArrayList<>();
        for (Message m : due) {
            if (exchange.claim(m.id())) {
                claimed.add(m);
            }
        }
        if (claimed.isEmpty()) {
            return 0;
        }
        int accepted = 0;
        if ("DAILY_BATCH".equals(p.mode())) {
            List<Map<String, Object>> envelopes = claimed.stream().map(exchange::envelope).toList();
            Ports.PartnerTransport.Outcome outcome = transport.sendBatch(p, envelopes);
            for (Message m : claimed) {
                accepted += record(m, outcome);
            }
        } else {
            for (Message m : claimed) {
                accepted += record(m, transport.send(p, exchange.envelope(m)));
            }
        }
        return accepted;
    }

    private int record(Message m, Ports.PartnerTransport.Outcome outcome) {
        try {
            exchange.delivered(m.id(), outcome, null);
        } catch (RuntimeException e) {
            // The attempt stays claimed; the delivery is retried after its back-off.
            LOG.warn("Outcome of message {} not recorded: {}", m.id(), e.getClass().getName());
            return 0;
        }
        return outcome.accepted() ? 1 : 0;
    }
}
