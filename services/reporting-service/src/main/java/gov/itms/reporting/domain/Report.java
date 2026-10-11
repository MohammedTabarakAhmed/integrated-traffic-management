package gov.itms.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Warehouse facts, report definitions, report requests and their rows (RPT-001, RPT-020). */
public final class Report {

    private Report() {
    }

    /** How rows are grouped in time. */
    public enum Period { DAY, MONTH }

    /**
     * One domain event as kept in the warehouse (RPT-020). {@code data} holds the event's scalar fields with personal
     * identifiers already pseudonymised (RPT-022).
     */
    public record Fact(String eventId, String type, String subject, String region, String channel, Instant occurredAt,
                       Map<String, String> data) {
        public Fact {
            data = Map.copyOf(data);
        }
    }

    /**
     * A report: which event types it counts and, optionally, which field it sums (an amount in minor units). Titles
     * are bilingual (RPT-002).
     */
    public record Definition(String code, String titleNl, String titleEn, List<String> eventTypes, String amountField) {
        public Definition {
            if (code == null || !code.matches("^[A-Z][A-Z0-9_]{1,39}$") || titleNl == null || titleEn == null
                    || eventTypes == null || eventTypes.isEmpty()) {
                throw new IllegalArgumentException("a report needs a code, both titles and at least one event type");
            }
            eventTypes = List.copyOf(eventTypes);
        }
    }

    /**
     * A request: the report, a date range of at most {@code maxDays}, the grouping and optional filters (RPT-001:
     * region, channel and status; dates are inclusive).
     */
    public record Request(String code, LocalDate from, LocalDate to, Period period, Set<String> regions,
                          String channel, String status) {
        public Request {
            if (from == null || to == null || to.isBefore(from)) {
                throw new DomainException(DomainException.Kind.VALIDATION, "PERIOD_INVALID",
                        "give a from date on or before the to date", List.of("RPT-001"));
            }
            period = period == null ? Period.DAY : period;
            regions = regions == null ? Set.of() : Set.copyOf(regions);
        }
    }

    /** One aggregated row: the period start, the region, the event type, how many and the amount summed. */
    public record Row(LocalDate period, String region, String eventType, long count, Long amountMinor) {
    }

    /** A report's answer, with the regions left out because the caller's scope does not reach them (RPT-004). */
    public record Result(Definition definition, Request request, List<Row> rows, Instant printedAt) {
        public Result {
            rows = List.copyOf(rows);
        }
    }
}
