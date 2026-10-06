package gov.itms.workflow.infrastructure;

import gov.itms.workflow.application.WorkflowRepository;
import gov.itms.workflow.domain.Actor;
import gov.itms.workflow.domain.WorkItem;
import gov.itms.workflow.domain.WorkRecords;
import gov.itms.workflow.domain.WorkflowDefinition;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Workflows and work items in PostgreSQL; the database guards versions, closed items and append-only tables (V1). */
class JdbcWorkflowRepository implements WorkflowRepository {

    private static final String DEFINITION_SELECT = """
            select code, version_no, service_code, name_nl, name_en, first_step, steps::text as steps, published_at,
                published_by, published_by_username, publish_reason
            from workflow_definition""";
    private static final String ITEM_SELECT = """
            select id, reference, service_code, workflow_code, definition_version, region, centre, customer_id,
                step_code, queue_role, escalation_role, status, outcome, assignee, created_at, step_started_at, due_at,
                paused_at, escalated_at, closed_at, version
            from work_item""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcWorkflowRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- definitions

    @Override
    public void lockDefinition(String code) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('workflow:' || :c))").param("c", code).query().listOfRows();
    }

    @Override
    public int nextDefinitionVersion(String code) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from workflow_definition where code = :c")
                .param("c", code).query(Integer.class).single();
    }

    @Override
    public void insertDefinition(WorkflowDefinition d) {
        jdbc.sql("""
                        insert into workflow_definition (code, version_no, service_code, name_nl, name_en, first_step,
                            steps, published_at, published_by, published_by_username, publish_reason)
                        values (:code, :no, :service, :nameNl, :nameEn, :first, cast(:steps as jsonb), :at, :by,
                            :byName, :reason)""")
                .param("code", d.code())
                .param("no", d.versionNo())
                .param("service", d.serviceCode())
                .param("nameNl", d.nameNl())
                .param("nameEn", d.nameEn())
                .param("first", d.firstStep())
                .param("steps", writeSteps(d.steps()))
                .param("at", Timestamp.from(d.publishedAt()))
                .param("by", d.publishedBy().id())
                .param("byName", d.publishedBy().username())
                .param("reason", d.reason())
                .update();
    }

    @Override
    public Optional<WorkflowDefinition> findDefinition(String code, int versionNo) {
        return jdbc.sql(DEFINITION_SELECT + " where code = :c and version_no = :v").param("c", code)
                .param("v", versionNo).query(this::mapDefinition).optional();
    }

    @Override
    public Optional<WorkflowDefinition> latestDefinition(String code) {
        return jdbc.sql(DEFINITION_SELECT + " where code = :c order by version_no desc limit 1").param("c", code)
                .query(this::mapDefinition).optional();
    }

    @Override
    public List<WorkflowDefinition> latestDefinitions(String serviceCode) {
        return jdbc.sql(DEFINITION_SELECT + """
                         d where version_no = (select max(version_no) from workflow_definition x where x.code = d.code)
                         and (cast(:s as varchar) is null or service_code = :s) order by code""")
                .param("s", serviceCode).query(this::mapDefinition).list();
    }

    // ---- items

    @Override
    public void insertItem(WorkItem i, String workflowCode) {
        jdbc.sql("""
                        insert into work_item (id, reference, service_code, workflow_code, definition_version, region,
                            centre, customer_id, step_code, queue_role, escalation_role, status, outcome, assignee,
                            created_at, step_started_at, due_at, paused_at, escalated_at, closed_at, version)
                        values (:id, :reference, :service, :workflow, :definitionVersion, :region, :centre, :customer,
                            :step, :queueRole, :escalationRole, :status, :outcome, :assignee, :createdAt,
                            :stepStartedAt, :dueAt, :pausedAt, :escalatedAt, :closedAt, 0)""")
                .param("id", i.id())
                .param("reference", i.start().reference())
                .param("service", i.start().serviceCode())
                .param("workflow", workflowCode)
                .param("definitionVersion", i.definitionVersion())
                .param("region", i.start().region())
                .param("centre", i.start().centre())
                .param("customer", i.start().customerId())
                .param("step", i.stepCode())
                .param("queueRole", i.queueRole())
                .param("escalationRole", i.escalationRole())
                .param("status", i.status().name())
                .param("outcome", i.outcome())
                .param("assignee", i.assignee())
                .param("createdAt", Timestamp.from(i.createdAt()))
                .param("stepStartedAt", Timestamp.from(i.stepStartedAt()))
                .param("dueAt", Timestamp.from(i.dueAt()))
                .param("pausedAt", ts(i.pausedAt()))
                .param("escalatedAt", ts(i.escalatedAt()))
                .param("closedAt", ts(i.closedAt()))
                .update();
    }

    @Override
    public Optional<WorkItem> findItem(UUID id) {
        return jdbc.sql(ITEM_SELECT + " where id = :id").param("id", id).query(this::mapItem).optional();
    }

    @Override
    public Optional<WorkItem> findItemForUpdate(UUID id) {
        return jdbc.sql(ITEM_SELECT + " where id = :id for update").param("id", id).query(this::mapItem).optional();
    }

    @Override
    public void updateItem(WorkItem i) {
        int updated = jdbc.sql("""
                        update work_item set step_code = :step, queue_role = :queueRole,
                            escalation_role = :escalationRole, status = :status, outcome = :outcome,
                            assignee = :assignee, step_started_at = :stepStartedAt, due_at = :dueAt,
                            paused_at = :pausedAt, escalated_at = :escalatedAt, closed_at = :closedAt,
                            version = version + 1
                        where id = :id and version = :version""")
                .param("step", i.stepCode())
                .param("queueRole", i.queueRole())
                .param("escalationRole", i.escalationRole())
                .param("status", i.status().name())
                .param("outcome", i.outcome())
                .param("assignee", i.assignee())
                .param("stepStartedAt", Timestamp.from(i.stepStartedAt()))
                .param("dueAt", Timestamp.from(i.dueAt()))
                .param("pausedAt", ts(i.pausedAt()))
                .param("escalatedAt", ts(i.escalatedAt()))
                .param("closedAt", ts(i.closedAt()))
                .param("id", i.id())
                .param("version", i.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("work item " + i.id() + " changed concurrently");
        }
        // keep the in-memory version in step for a second save in the same transaction
        i.saved();
    }

    @Override
    public boolean activeItemExists(String reference, String workflowCode) {
        return jdbc.sql("""
                        select exists (select 1 from work_item where reference = :r and workflow_code = :w
                            and status in ('OPEN', 'RETURNED'))""")
                .param("r", reference).param("w", workflowCode).query(Boolean.class).single();
    }

    @Override
    public String workflowCodeOf(UUID itemId) {
        return jdbc.sql("select workflow_code from work_item where id = :id").param("id", itemId)
                .query(String.class).single();
    }

    @Override
    public List<WorkItem> assignedTo(String userId, ListFilter f) {
        return list(" where assignee = :who", Map.of("who", userId), f);
    }

    @Override
    public List<WorkItem> inQueues(Collection<String> roles, ListFilter f) {
        if (roles.isEmpty()) {
            return List.of();
        }
        return list(" where queue_role = any(string_to_array(:roles, ','))", Map.of("roles", String.join(",", roles)),
                f);
    }

    @Override
    public List<WorkItem> escalatedTo(Collection<String> roles, ListFilter f) {
        if (roles.isEmpty()) {
            return List.of();
        }
        return list(" where escalated_at is not null and escalation_role = any(string_to_array(:roles, ','))",
                Map.of("roles", String.join(",", roles)), f);
    }

    private List<WorkItem> list(String where, Map<String, Object> params, ListFilter f) {
        StringBuilder sql = new StringBuilder(ITEM_SELECT).append(where)
                .append(" and status = any(string_to_array(:statuses, ','))");
        if (f.serviceCode() != null) {
            sql.append(" and service_code = :service");
        }
        if (Boolean.TRUE.equals(f.overdue())) {
            sql.append(" and status = 'OPEN' and due_at < :now");
        } else if (Boolean.FALSE.equals(f.overdue())) {
            sql.append(" and not (status = 'OPEN' and due_at < :now)");
        }
        if (Boolean.TRUE.equals(f.escalated())) {
            sql.append(" and escalated_at is not null");
        } else if (Boolean.FALSE.equals(f.escalated())) {
            sql.append(" and escalated_at is null");
        }
        sql.append(" order by due_at, id limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("statuses", String.join(",", f.statuses().stream().map(Enum::name).toList()))
                .param("limit", f.limit());
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        if (f.serviceCode() != null) {
            spec = spec.param("service", f.serviceCode());
        }
        if (f.overdue() != null) {
            spec = spec.param("now", Timestamp.from(f.now()));
        }
        return spec.query(this::mapItem).list();
    }

    @Override
    public List<WorkItem> overdueForEscalation(Instant now, int limit) {
        return jdbc.sql(ITEM_SELECT + """
                         where status = 'OPEN' and escalated_at is null and due_at < :now
                         order by due_at limit :limit for update skip locked""")
                .param("now", Timestamp.from(now)).param("limit", limit).query(this::mapItem).list();
    }

    // ---- timeline and notes

    @Override
    public void insertHistory(WorkRecords.HistoryEntry e) {
        jdbc.sql("""
                        insert into work_item_history (item_id, action, step_code, actor_id, at, detail)
                        values (:item, :action, :step, :actor, :at, cast(:detail as jsonb))""")
                .param("item", e.itemId())
                .param("action", e.action())
                .param("step", e.stepCode())
                .param("actor", e.actorId())
                .param("at", Timestamp.from(e.at()))
                .param("detail", json.writeValueAsString(e.detail()))
                .update();
    }

    @Override
    public List<WorkRecords.HistoryEntry> history(UUID itemId) {
        return jdbc.sql("""
                        select item_id, action, step_code, actor_id, at, detail::text as detail
                        from work_item_history where item_id = :id order by id""")
                .param("id", itemId)
                .query((rs, n) -> new WorkRecords.HistoryEntry(rs.getObject("item_id", UUID.class),
                        rs.getString("action"), rs.getString("step_code"), rs.getString("actor_id"),
                        rs.getTimestamp("at").toInstant(), objectMap(json.readTree(rs.getString("detail")))))
                .list();
    }

    @Override
    public void insertNote(WorkRecords.Note n) {
        jdbc.sql("""
                        insert into work_item_note (id, item_id, text, attachment_ids, author_id, author_username, at)
                        values (:id, :item, :text, string_to_array(:attachments, ','), :author, :authorName, :at)""")
                .param("id", n.id())
                .param("item", n.itemId())
                .param("text", n.text())
                .param("attachments", String.join(",", n.attachmentIds()))
                .param("author", n.author().id())
                .param("authorName", n.author().username())
                .param("at", Timestamp.from(n.at()))
                .update();
    }

    @Override
    public List<WorkRecords.Note> notes(UUID itemId) {
        return jdbc.sql("""
                        select id, item_id, text, attachment_ids, author_id, author_username, at
                        from work_item_note where item_id = :id order by at, id""")
                .param("id", itemId)
                .query((rs, n) -> new WorkRecords.Note(rs.getObject("id", UUID.class),
                        rs.getObject("item_id", UUID.class), rs.getString("text"), strings(rs.getArray("attachment_ids")),
                        new Actor(rs.getString("author_id"), rs.getString("author_username")),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    // ---- queue members

    @Override
    public void upsertMember(WorkRecords.QueueMember m) {
        jdbc.sql("""
                        insert into queue_member (user_id, username, role, regions, centres, available, updated_at)
                        values (:user, :name, :role, string_to_array(:regions, ','), string_to_array(:centres, ','),
                            :available, now())
                        on conflict (user_id, role) do update set username = excluded.username,
                            regions = excluded.regions, centres = excluded.centres, available = excluded.available,
                            updated_at = now()""")
                .param("user", m.userId())
                .param("name", m.username())
                .param("role", m.role())
                .param("regions", String.join(",", m.regions()))
                .param("centres", String.join(",", m.centres()))
                .param("available", m.available())
                .update();
    }

    @Override
    public List<WorkRecords.QueueMember> members(String role) {
        return jdbc.sql(MEMBER_SELECT + " where m.role = :role order by m.user_id").param("role", role)
                .query(this::mapMember).list();
    }

    @Override
    public List<WorkRecords.QueueMember> membershipsOf(String userId) {
        return jdbc.sql(MEMBER_SELECT + " where m.user_id = :user order by m.role").param("user", userId)
                .query(this::mapMember).list();
    }

    @Override
    public void markAssigned(String userId, String role, Instant at) {
        jdbc.sql("update queue_member set last_assigned_at = :at where user_id = :user and role = :role")
                .param("at", Timestamp.from(at)).param("user", userId).param("role", role).update();
    }

    private static final String MEMBER_SELECT = """
            select m.user_id, m.username, m.role, m.regions, m.centres, m.available, m.last_assigned_at,
                (select count(*) from work_item w where w.assignee = m.user_id and w.queue_role = m.role
                    and w.status = 'OPEN') as open_items
            from queue_member m""";

    // ---- mapping

    private WorkflowDefinition mapDefinition(ResultSet rs, int row) throws SQLException {
        return new WorkflowDefinition(rs.getString("code"), rs.getInt("version_no"), rs.getString("service_code"),
                rs.getString("name_nl"), rs.getString("name_en"), rs.getString("first_step"),
                readSteps(rs.getString("steps")),
                new Actor(rs.getString("published_by"), rs.getString("published_by_username")),
                rs.getTimestamp("published_at").toInstant(), rs.getString("publish_reason"));
    }

    private WorkItem mapItem(ResultSet rs, int row) throws SQLException {
        WorkItem.Start start = new WorkItem.Start(rs.getString("reference"), rs.getString("service_code"),
                rs.getString("region"), rs.getString("centre"), rs.getString("customer_id"),
                rs.getString("workflow_code"));
        return WorkItem.restore(rs.getObject("id", UUID.class), start, rs.getInt("definition_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("step_code"), rs.getString("queue_role"),
                rs.getString("escalation_role"), WorkItem.Status.valueOf(rs.getString("status")),
                rs.getString("outcome"), rs.getString("assignee"), rs.getTimestamp("step_started_at").toInstant(),
                rs.getTimestamp("due_at").toInstant(), instant(rs.getTimestamp("paused_at")),
                instant(rs.getTimestamp("escalated_at")), instant(rs.getTimestamp("closed_at")), rs.getLong("version"));
    }

    private WorkRecords.QueueMember mapMember(ResultSet rs, int row) throws SQLException {
        return new WorkRecords.QueueMember(rs.getString("user_id"), rs.getString("username"), rs.getString("role"),
                Set.copyOf(strings(rs.getArray("regions"))), Set.copyOf(strings(rs.getArray("centres"))),
                rs.getBoolean("available"), instant(rs.getTimestamp("last_assigned_at")), rs.getInt("open_items"));
    }

    private String writeSteps(List<WorkflowDefinition.Step> steps) {
        ArrayNode a = json.createArrayNode();
        for (WorkflowDefinition.Step s : steps) {
            ObjectNode n = a.addObject();
            n.put("code", s.code());
            n.put("kind", s.kind().name());
            n.put("nameNl", s.nameNl());
            n.put("nameEn", s.nameEn());
            n.put("role", s.role());
            n.put("assignment", s.assignment().name());
            n.put("sla", s.sla().toString());
            n.put("escalationRole", s.escalationRole());
            ObjectNode o = n.putObject("outcomes");
            s.outcomes().forEach(o::put);
        }
        return json.writeValueAsString(a);
    }

    private List<WorkflowDefinition.Step> readSteps(String text) {
        List<WorkflowDefinition.Step> steps = new ArrayList<>();
        for (JsonNode n : json.readTree(text)) {
            Map<String, String> outcomes = new LinkedHashMap<>();
            n.get("outcomes").properties().forEach(e -> outcomes.put(e.getKey(), e.getValue().asString()));
            steps.add(new WorkflowDefinition.Step(n.get("code").asString(),
                    WorkflowDefinition.StepKind.valueOf(n.get("kind").asString()), n.get("nameNl").asString(),
                    n.get("nameEn").asString(), n.get("role").asString(),
                    WorkflowDefinition.Assignment.valueOf(n.get("assignment").asString()),
                    Duration.parse(n.get("sla").asString()), n.get("escalationRole").asString(), outcomes));
        }
        return steps;
    }

    private static Map<String, Object> objectMap(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        n.properties().forEach(e -> {
            JsonNode v = e.getValue();
            Object value;
            if (v.isNull()) {
                value = null;
            } else if (v.isNumber()) {
                value = v.numberValue();
            } else if (v.isBoolean()) {
                value = v.booleanValue();
            } else if (v.isArray()) {
                List<Object> list = new ArrayList<>();
                v.forEach(x -> list.add(x.isNumber() ? x.numberValue() : x.asString()));
                value = list;
            } else {
                value = v.asString();
            }
            m.put(e.getKey(), value);
        });
        return m;
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).filter(s -> !s.isEmpty()).toList();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
