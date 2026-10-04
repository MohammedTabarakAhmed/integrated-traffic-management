package gov.itms.transaction.infrastructure;

import gov.itms.transaction.application.ServiceDefinitionRepository;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.BilingualName;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.ServiceDefinition;
import gov.itms.transaction.domain.ServiceSpec;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcServiceDefinitionRepository implements ServiceDefinitionRepository {

    private static final String SPEC_COLUMNS = """
            code, name_nl, name_en, module, customer_types, channels, required_documents, fee_code, rule_set_code,
            workflow_code, sla, regions, any_region, allows_concurrent_open, version""";
    private static final String SELECT = "select " + SPEC_COLUMNS
            + ", last_change_id, updated_at, updated_by from service_definition";

    private final JdbcClient jdbc;

    JdbcServiceDefinitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ServiceDefinition> findAll() {
        return jdbc.sql(SELECT + " order by code").query((rs, n) -> map(rs)).list();
    }

    @Override
    public Optional<ServiceDefinition> find(String code) {
        return jdbc.sql(SELECT + " where code = :code").param("code", code).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<ServiceDefinition> findForUpdate(String code) {
        return jdbc.sql(SELECT + " where code = :code for update").param("code", code).query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public void insert(ServiceDefinition d) {
        bind(jdbc.sql("""
                        insert into service_definition (code, name_nl, name_en, module, customer_types, channels,
                            required_documents, fee_code, rule_set_code, workflow_code, sla, regions, any_region,
                            allows_concurrent_open, version, last_change_id, created_at, created_by, updated_at,
                            updated_by)
                        values (:code, :nameNl, :nameEn, :module, string_to_array(:customerTypes, ','),
                            string_to_array(:channels, ','), string_to_array(:documents, ','), :fee, :ruleSet,
                            :workflow, :sla, string_to_array(:regions, ','), :anyRegion, :concurrent, :version,
                            :change, :at, :by, :at, :by)"""), d)
                .update();
    }

    @Override
    public void update(ServiceDefinition d) {
        int updated = bind(jdbc.sql("""
                        update service_definition
                           set name_nl = :nameNl, name_en = :nameEn, module = :module,
                               customer_types = string_to_array(:customerTypes, ','),
                               channels = string_to_array(:channels, ','),
                               required_documents = string_to_array(:documents, ','), fee_code = :fee,
                               rule_set_code = :ruleSet, workflow_code = :workflow, sla = :sla,
                               regions = string_to_array(:regions, ','), any_region = :anyRegion,
                               allows_concurrent_open = :concurrent, version = :version, last_change_id = :change,
                               updated_at = :at, updated_by = :by
                         where code = :code and version = :version - 1"""), d)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("service " + d.code() + " was not at version " + (d.version() - 1));
        }
    }

    @Override
    public List<ServiceVersion> history(String code) {
        return jdbc.sql("select " + SPEC_COLUMNS + """
                        , valid_from, valid_to, changed_by, change_id
                          from service_definition_history
                         where code = :code
                         order by version""")
                .param("code", code)
                .query((rs, n) -> {
                    Timestamp validTo = rs.getTimestamp("valid_to");
                    return new ServiceVersion(spec(rs), rs.getLong("version"), rs.getTimestamp("valid_from").toInstant(),
                            validTo == null ? null : validTo.toInstant(), rs.getString("changed_by"),
                            rs.getObject("change_id", UUID.class));
                })
                .list();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, ServiceDefinition d) {
        ServiceSpec s = d.spec();
        return sql.param("code", s.code())
                .param("nameNl", s.name().nl())
                .param("nameEn", s.name().en())
                .param("module", s.module())
                .param("customerTypes", String.join(",", s.customerTypes()))
                .param("channels", String.join(",", s.channels().stream().map(Enum::name).toList()))
                .param("documents", String.join(",", s.requiredDocuments()))
                .param("fee", s.feeCode())
                .param("ruleSet", s.ruleSetCode())
                .param("workflow", s.workflowCode())
                .param("sla", s.sla())
                .param("regions", String.join(",", s.regions()))
                .param("anyRegion", s.anyRegion())
                .param("concurrent", s.allowsConcurrentOpen())
                .param("version", d.version())
                .param("change", d.lastChangeId())
                .param("at", Timestamp.from(d.updatedAt()))
                .param("by", d.updatedBy().id());
    }

    private static ServiceDefinition map(ResultSet rs) throws SQLException {
        return new ServiceDefinition(spec(rs), rs.getLong("version"), rs.getObject("last_change_id", UUID.class),
                rs.getTimestamp("updated_at").toInstant(), new Actor(rs.getString("updated_by"), null));
    }

    private static ServiceSpec spec(ResultSet rs) throws SQLException {
        return new ServiceSpec(rs.getString("code"), new BilingualName(rs.getString("name_nl"), rs.getString("name_en")),
                rs.getString("module"), strings(rs.getArray("customer_types")),
                strings(rs.getArray("channels")).stream().map(Channel::valueOf).toList(),
                strings(rs.getArray("required_documents")), rs.getString("fee_code"), rs.getString("rule_set_code"),
                rs.getString("workflow_code"), rs.getString("sla"), strings(rs.getArray("regions")),
                rs.getBoolean("any_region"), rs.getBoolean("allows_concurrent_open"));
    }

    static List<String> strings(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
