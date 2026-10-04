package gov.itms.refdata.infrastructure;

import gov.itms.refdata.application.ReferenceItemRepository;
import gov.itms.refdata.domain.BilingualName;
import gov.itms.refdata.domain.ItemData;
import gov.itms.refdata.domain.ReferenceItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class JdbcReferenceItemRepository implements ReferenceItemRepository {

    private static final TypeReference<Map<String, String>> CODES = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, list_code, code, region_code, name_nl, name_en, active, effective_from, effective_to, parent_code,
            external_codes::text as external_codes, sort_order, version""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcReferenceItemRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ReferenceItem> find(String listCode, String code, String regionCode) {
        return jdbc.sql("select " + COLUMNS + """
                         from reference_item
                        where list_code = :list and code = :code and region_code is not distinct from :region""")
                .param("list", listCode).param("code", code).param("region", regionCode)
                .query((rs, n) -> item(rs, "id")).optional();
    }

    @Override
    public List<ReferenceItem> findByList(String listCode, String regionCode) {
        return jdbc.sql("select " + COLUMNS + """
                         from reference_item
                        where list_code = :list and (region_code is null or region_code = :region)""")
                .param("list", listCode).param("region", regionCode)
                .query((rs, n) -> item(rs, "id")).list();
    }

    @Override
    public void insert(ReferenceItem item, String actorId, UUID changeId, Instant at) {
        ItemData d = item.data();
        jdbc.sql("""
                        insert into reference_item (id, list_code, code, region_code, name_nl, name_en, active,
                            effective_from, effective_to, parent_code, external_codes, sort_order, version,
                            last_change_id, created_at, created_by, updated_at, updated_by)
                        values (:id, :list, :code, :region, :nl, :en, :active, :from, :to, :parent,
                            cast(:codes as jsonb), :sort, :version, :change, :at, :by, :at, :by)""")
                .param("id", item.id())
                .param("list", item.listCode())
                .param("code", item.code())
                .param("region", item.regionCode())
                .param("nl", d.name().nl())
                .param("en", d.name().en())
                .param("active", d.active())
                .param("from", d.effectiveFrom())
                .param("to", d.effectiveTo())
                .param("parent", d.parentCode())
                .param("codes", json.writeValueAsString(d.externalCodes()))
                .param("sort", d.sortOrder())
                .param("version", item.version())
                .param("change", changeId)
                .param("at", Timestamp.from(at))
                .param("by", actorId)
                .update();
    }

    @Override
    public boolean update(ReferenceItem item, long expectedVersion, String actorId, UUID changeId, Instant at) {
        ItemData d = item.data();
        int rows = jdbc.sql("""
                        update reference_item
                           set name_nl = :nl, name_en = :en, active = :active, effective_from = :from,
                               effective_to = :to, parent_code = :parent, external_codes = cast(:codes as jsonb),
                               sort_order = :sort, version = :version, last_change_id = :change,
                               updated_at = :at, updated_by = :by
                         where id = :id and version = :expected""")
                .param("nl", d.name().nl())
                .param("en", d.name().en())
                .param("active", d.active())
                .param("from", d.effectiveFrom())
                .param("to", d.effectiveTo())
                .param("parent", d.parentCode())
                .param("codes", json.writeValueAsString(d.externalCodes()))
                .param("sort", d.sortOrder())
                .param("version", item.version())
                .param("change", changeId)
                .param("at", Timestamp.from(at))
                .param("by", actorId)
                .param("id", item.id())
                .param("expected", expectedVersion)
                .update();
        return rows == 1;
    }

    @Override
    public List<ItemVersion> history(String listCode, String code, String regionCode) {
        return jdbc.sql("""
                        select item_id, list_code, code, region_code, name_nl, name_en, active, effective_from,
                               effective_to, parent_code, external_codes::text as external_codes, sort_order, version,
                               valid_from, valid_to, changed_by, change_id
                          from reference_item_history
                         where list_code = :list and code = :code and region_code is not distinct from :region
                         order by version""")
                .param("list", listCode).param("code", code).param("region", regionCode)
                .query((rs, n) -> {
                    Timestamp validTo = rs.getTimestamp("valid_to");
                    return new ItemVersion(item(rs, "item_id"), rs.getTimestamp("valid_from").toInstant(),
                            validTo == null ? null : validTo.toInstant(), rs.getString("changed_by"),
                            rs.getObject("change_id", UUID.class));
                })
                .list();
    }

    private ReferenceItem item(ResultSet rs, String idColumn) throws SQLException {
        ItemData data = new ItemData(
                new BilingualName(rs.getString("name_nl"), rs.getString("name_en")),
                rs.getBoolean("active"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                rs.getString("parent_code"),
                json.readValue(rs.getString("external_codes"), CODES),
                rs.getInt("sort_order"));
        return new ReferenceItem(rs.getObject(idColumn, UUID.class), rs.getString("list_code"), rs.getString("code"),
                rs.getString("region_code"), data, rs.getLong("version"));
    }
}
