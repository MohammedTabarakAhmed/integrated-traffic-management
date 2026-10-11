package gov.itms.reporting.infrastructure;

import gov.itms.reporting.application.Ports;
import gov.itms.reporting.domain.Report;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** The warehouse in PostgreSQL ({@code fact_event}, V1). Instants are bound as timestamps, never as objects. */
class JdbcWarehouse implements Ports.Warehouse {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcWarehouse(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(Report.Fact f) {
        return jdbc.sql("""
                        insert into fact_event (event_id, type, subject, region, channel, occurred_at, data)
                        values (:id, :type, :subject, :region, :channel, :at, cast(:data as jsonb))
                        on conflict (event_id) do nothing""")
                .param("id", f.eventId())
                .param("type", f.type())
                .param("subject", f.subject())
                .param("region", f.region())
                .param("channel", f.channel())
                .param("at", Timestamp.from(f.occurredAt()))
                .param("data", json.writeValueAsString(f.data()))
                .update() == 1;
    }

    @Override
    public List<Report.Row> aggregate(List<String> types, String amountField, Instant from, Instant to,
                                      Report.Period period, String channel, String status) {
        return jdbc.sql("""
                        select (date_trunc(cast(:unit as text), occurred_at at time zone 'UTC'))::date as period,
                               region, type, count(*) as n,
                               sum(case when cast(:amount as text) is not null
                                         and data ->> cast(:amount as text) ~ '^-?[0-9]{1,18}$'
                                        then (data ->> cast(:amount as text))::bigint end) as amount
                        from fact_event
                        where type in (:types) and occurred_at >= :from and occurred_at < :to
                          and (cast(:channel as text) is null or channel = cast(:channel as text))
                          and (cast(:status as text) is null or data ->> 'status' = cast(:status as text))
                        group by 1, 2, 3
                        order by 1, 2 nulls first, 3""")
                .param("unit", period == Report.Period.MONTH ? "month" : "day")
                .param("amount", amountField)
                .param("types", types)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("channel", channel)
                .param("status", status)
                .query((rs, i) -> {
                    long amount = rs.getLong("amount");
                    return new Report.Row(rs.getObject("period", java.time.LocalDate.class), rs.getString("region"),
                            rs.getString("type"), rs.getLong("n"), rs.wasNull() ? null : amount);
                })
                .list();
    }
}
