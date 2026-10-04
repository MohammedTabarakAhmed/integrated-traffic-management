package gov.itms.refdata.infrastructure;

import gov.itms.refdata.application.ChangeRequestRepository;
import gov.itms.refdata.domain.BilingualName;
import gov.itms.refdata.domain.ChangeRequest;
import gov.itms.refdata.domain.ChangeRequest.Actor;
import gov.itms.refdata.domain.ChangeStatus;
import gov.itms.refdata.domain.ChangeType;
import gov.itms.refdata.domain.ItemData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class JdbcChangeRequestRepository implements ChangeRequestRepository {

    private static final TypeReference<Map<String, String>> CODES = new TypeReference<>() {
    };
    private static final String SELECT = """
            select id, change_type, status, list_code, item_code, region_code, name_nl, name_en, parent_list_code,
                   active, effective_from, effective_to, parent_code, external_codes::text as external_codes,
                   sort_order, base_version, reason, maker_id, maker_username, submitted_at, checker_id,
                   checker_username, decided_at, decision_reason
              from reference_change""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcChangeRequestRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(ChangeRequest c) {
        ItemData d = c.itemData();
        BilingualName name = d != null ? d.name() : c.listName();
        jdbc.sql("""
                        insert into reference_change (id, change_type, status, list_code, item_code, region_code,
                            name_nl, name_en, parent_list_code, active, effective_from, effective_to, parent_code,
                            external_codes, sort_order, base_version, reason, maker_id, maker_username, submitted_at)
                        values (:id, :type, :status, :list, :item, :region, :nl, :en, :parentList, :active, :from,
                            :to, :parent, cast(:codes as jsonb), :sort, :base, :reason, :maker, :makerName, :at)""")
                .param("id", c.id())
                .param("type", c.type().name())
                .param("status", c.status().name())
                .param("list", c.listCode())
                .param("item", c.itemCode())
                .param("region", c.regionCode())
                .param("nl", name.nl())
                .param("en", name.en())
                .param("parentList", c.parentListCode())
                .param("active", d == null ? null : d.active())
                .param("from", d == null ? null : d.effectiveFrom())
                .param("to", d == null ? null : d.effectiveTo())
                .param("parent", d == null ? null : d.parentCode())
                .param("codes", d == null ? null : json.writeValueAsString(d.externalCodes()))
                .param("sort", d == null ? null : d.sortOrder())
                .param("base", c.baseVersion())
                .param("reason", c.reason())
                .param("maker", c.maker().id())
                .param("makerName", c.maker().username())
                .param("at", Timestamp.from(c.submittedAt()))
                .update();
    }

    @Override
    public Optional<ChangeRequest> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<ChangeRequest> findForDecision(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public void saveDecision(ChangeRequest c) {
        jdbc.sql("""
                        update reference_change
                           set status = :status, checker_id = :checker, checker_username = :checkerName,
                               decided_at = :at, decision_reason = :reason
                         where id = :id and status = 'PENDING_APPROVAL'""")
                .param("status", c.status().name())
                .param("checker", c.checker().id())
                .param("checkerName", c.checker().username())
                .param("at", Timestamp.from(c.decidedAt()))
                .param("reason", c.decisionReason())
                .param("id", c.id())
                .update();
    }

    @Override
    public List<ChangeRequest> findByStatus(ChangeStatus status) {
        String sql = status == null ? SELECT + " order by submitted_at desc"
                : SELECT + " where status = :status order by submitted_at desc";
        var spec = jdbc.sql(sql);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query((rs, n) -> map(rs)).list();
    }

    @Override
    public boolean pendingExistsFor(String listCode, String itemCode, String regionCode) {
        return jdbc.sql("""
                        select count(*) from reference_change
                         where status = 'PENDING_APPROVAL' and list_code = :list
                           and item_code is not distinct from :item and region_code is not distinct from :region""")
                .param("list", listCode).param("item", itemCode).param("region", regionCode)
                .query(Long.class).single() > 0;
    }

    private ChangeRequest map(ResultSet rs) throws SQLException {
        ChangeType type = ChangeType.valueOf(rs.getString("change_type"));
        BilingualName name = new BilingualName(rs.getString("name_nl"), rs.getString("name_en"));
        ItemData data = null;
        if (type != ChangeType.CREATE_LIST) {
            data = new ItemData(name, rs.getBoolean("active"), rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class), rs.getString("parent_code"),
                    json.readValue(rs.getString("external_codes"), CODES), rs.getInt("sort_order"));
        }
        Long baseVersion = rs.getObject("base_version", Long.class);
        String checkerId = rs.getString("checker_id");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ChangeRequest(
                rs.getObject("id", UUID.class), type, rs.getString("list_code"), rs.getString("item_code"),
                rs.getString("region_code"), type == ChangeType.CREATE_LIST ? name : null,
                rs.getString("parent_list_code"), data, baseVersion, rs.getString("reason"),
                new Actor(rs.getString("maker_id"), rs.getString("maker_username")),
                rs.getTimestamp("submitted_at").toInstant(), ChangeStatus.valueOf(rs.getString("status")),
                checkerId == null ? null : new Actor(checkerId, rs.getString("checker_username")),
                decidedAt == null ? null : decidedAt.toInstant(), rs.getString("decision_reason"));
    }
}
