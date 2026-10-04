package gov.itms.transaction.infrastructure;

import gov.itms.transaction.application.AvailabilityRuleRepository;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.AvailabilityRule;
import gov.itms.transaction.domain.AvailabilityRuleSpec;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.Codes;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAvailabilityRuleRepository implements AvailabilityRuleRepository {

    private static final String SELECT = """
            select id, service_code, region, channel, enabled, effective_from, effective_to, change_id, ended_by_change
              from availability_rule""";

    private final JdbcClient jdbc;

    JdbcAvailabilityRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AvailabilityRule> findByService(String serviceCode) {
        return jdbc.sql(SELECT + " where service_code = :code order by effective_from, region, channel, id")
                .param("code", serviceCode).query((rs, n) -> map(rs)).list();
    }

    @Override
    public List<AvailabilityRule> findAll() {
        return jdbc.sql(SELECT + " order by service_code, effective_from, region, channel, id")
                .query((rs, n) -> map(rs)).list();
    }

    @Override
    public Optional<AvailabilityRule> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public void insert(AvailabilityRule r, Actor createdBy, Instant now) {
        AvailabilityRuleSpec s = r.spec();
        jdbc.sql("""
                        insert into availability_rule (id, service_code, region, channel, enabled, effective_from,
                            effective_to, change_id, created_at, created_by)
                        values (:id, :code, :region, :channel, :enabled, :from, :to, :change, :at, :by)""")
                .param("id", r.id())
                .param("code", r.serviceCode())
                .param("region", s.region())
                .param("channel", s.allChannels() ? Codes.ALL : s.channel().name())
                .param("enabled", s.enabled())
                .param("from", Date.valueOf(s.effectiveFrom()))
                .param("to", s.effectiveTo() == null ? null : Date.valueOf(s.effectiveTo()))
                .param("change", r.changeId())
                .param("at", Timestamp.from(now))
                .param("by", createdBy.id())
                .update();
    }

    @Override
    public void saveEnd(AvailabilityRule r) {
        jdbc.sql("update availability_rule set effective_to = :to, ended_by_change = :change where id = :id")
                .param("to", Date.valueOf(r.spec().effectiveTo()))
                .param("change", r.endedByChange())
                .param("id", r.id())
                .update();
    }

    private static AvailabilityRule map(ResultSet rs) throws SQLException {
        String channel = rs.getString("channel");
        Date to = rs.getDate("effective_to");
        AvailabilityRuleSpec spec = new AvailabilityRuleSpec(rs.getString("region"),
                Codes.ALL.equals(channel) ? null : Channel.valueOf(channel), rs.getBoolean("enabled"),
                rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate());
        return new AvailabilityRule(rs.getObject("id", UUID.class), rs.getString("service_code"), spec,
                rs.getObject("change_id", UUID.class), rs.getObject("ended_by_change", UUID.class));
    }
}
