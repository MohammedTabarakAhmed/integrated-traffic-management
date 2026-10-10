package gov.itms.violation.infrastructure;

import gov.itms.violation.application.Ports;
import gov.itms.violation.application.Ports.CrossborderLinks.Direction;
import gov.itms.violation.application.Ports.CrossborderLinks.Link;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code violation_crossborder}: which violations crossed a border (XB-001, XB-002). */
class JdbcCrossborderLinks implements Ports.CrossborderLinks {

    private static final String SELECT = """
            select l.violation_id, v.number, l.partner_code, l.direction, l.external_id
            from violation_crossborder l join violation v on v.id = l.violation_id
            """;

    private final JdbcClient jdbc;

    JdbcCrossborderLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Link link, Instant at) {
        jdbc.sql("""
                insert into violation_crossborder (violation_id, partner_code, direction, external_id, recorded_at)
                values (:id, :partner, :direction, :external, :at)
                """).param("id", link.violationId()).param("partner", link.partner())
                .param("direction", link.direction().name()).param("external", link.externalId()).param("at", at)
                .update();
    }

    @Override
    public Optional<Link> byExternalId(String partner, String externalId) {
        return jdbc.sql(SELECT + " where l.partner_code = :partner and l.external_id = :external")
                .param("partner", partner).param("external", externalId).query(JdbcCrossborderLinks::map).optional();
    }

    @Override
    public Optional<Link> byNumber(String violationNumber) {
        return jdbc.sql(SELECT + " where v.number = :number").param("number", violationNumber)
                .query(JdbcCrossborderLinks::map).optional();
    }

    private static Link map(ResultSet rs, int row) throws SQLException {
        return new Link(rs.getObject("violation_id", UUID.class), rs.getString("number"),
                rs.getString("partner_code"), Direction.valueOf(rs.getString("direction")),
                rs.getString("external_id"));
    }
}
