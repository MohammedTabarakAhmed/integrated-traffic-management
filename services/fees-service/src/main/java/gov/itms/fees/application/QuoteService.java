package gov.itms.fees.application;

import gov.itms.fees.domain.DomainException;
import gov.itms.fees.domain.Quote;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quotes (CC-047; ADR-046): the itemised breakdown of a service's fees for a request, frozen and kept until it
 * expires (validity in configuration). Any signed-in user may ask for a quote: fees are public and the customer must
 * see them before paying. A quote is for today or a future date, never the past. Reading a quote back needs only its
 * id (a random UUID); payment and transaction services use it to charge exactly what was shown (CC-046).
 */
public class QuoteService {

    private final FeeRepository fees;
    private final AuditRecorder audit;
    private final Clock clock;
    private final Duration validity;
    private final String currency;

    public QuoteService(FeeRepository fees, AuditRecorder audit, Clock clock, Duration validity, String currency) {
        this.fees = fees;
        this.audit = audit;
        this.clock = clock;
        this.validity = validity;
        this.currency = currency;
    }

    @Transactional
    public Quote create(Quote.Request request, Caller caller) {
        FeeService.channelOf(caller);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        if (request.date().isBefore(today)) {
            throw DomainException.validation("QUOTE_DATE_IN_PAST", "a quote is for today or a later date",
                    List.of("CC-047"));
        }
        List<Quote.Line> lines = Quote.calculate(fees.approvedForService(request.serviceCode(), request.date()),
                request);
        long total = lines.stream().mapToLong(Quote.Line::amount).sum();
        Quote quote = new Quote(UUID.randomUUID(), request, lines, total, currency, now, now.plus(validity),
                caller.actor().id());
        fees.insertQuote(quote);
        audit.record("FEE_QUOTE_CREATED", "FeeQuote", quote.id().toString(), null,
                Map.of("serviceCode", request.serviceCode(), "total", total, "lines", lines.size()), null);
        return quote;
    }

    @Transactional(readOnly = true)
    public Quote get(UUID id) {
        return fees.findQuote(id).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "QUOTE_NOT_FOUND", "quote " + id + " does not exist", List.of("CC-047")));
    }

    public Instant now() {
        return clock.instant();
    }

    /** Today in the Authority's time zone (the clock's zone). */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }
}
