package gov.itms.iam.infrastructure;

import gov.itms.iam.application.AccessReviewRepository;
import gov.itms.iam.domain.AccessReviewCampaign;
import gov.itms.iam.domain.AccessReviewItem;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.AssignmentScopes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAccessReviewRepository implements AccessReviewRepository {

    private static final String SELECT_CAMPAIGN = """
            select id, title, regions, centres, modules, due_at, reason, status, created_by, created_by_username,
                   created_at, closed_by, closed_by_username, closed_at, closure_reason
              from access_review_campaign""";

    private static final String SELECT_ITEM = """
            select i.id, i.campaign_id, c.due_at as campaign_due_at, i.assignment_id, i.subject_id,
                   i.subject_username, i.role_code, i.regions, i.centres, i.modules, i.assigned_by,
                   i.assigned_by_username, i.valid_from, i.valid_to, i.status, i.decided_by, i.decided_by_username,
                   i.decided_at, i.decision_reason
              from access_review_item i
              join access_review_campaign c on c.id = i.campaign_id""";

    private final JdbcClient jdbc;

    JdbcAccessReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCampaign(AccessReviewCampaign c) {
        jdbc.sql("""
                        insert into access_review_campaign (id, title, regions, centres, modules, due_at, reason,
                            status, created_by, created_by_username, created_at)
                        values (:id, :title, string_to_array(:regions, ','), string_to_array(:centres, ','),
                            string_to_array(:modules, ','), :due, :reason, :status, :by, :byName, :at)""")
                .param("id", c.id())
                .param("title", c.title())
                .param("regions", SqlArrays.join(c.scopes().regions()))
                .param("centres", SqlArrays.join(c.scopes().centres()))
                .param("modules", SqlArrays.join(c.scopes().modules()))
                .param("due", Timestamp.from(c.dueAt()))
                .param("reason", c.reason())
                .param("status", c.status().name())
                .param("by", c.createdBy().id())
                .param("byName", c.createdBy().username())
                .param("at", Timestamp.from(c.createdAt()))
                .update();
    }

    @Override
    public Optional<AccessReviewCampaign> findCampaign(UUID id) {
        return jdbc.sql(SELECT_CAMPAIGN + " where id = :id").param("id", id)
                .query((rs, n) -> mapCampaign(rs)).optional();
    }

    @Override
    public Optional<AccessReviewCampaign> findCampaignForShare(UUID id) {
        return jdbc.sql(SELECT_CAMPAIGN + " where id = :id for share").param("id", id)
                .query((rs, n) -> mapCampaign(rs)).optional();
    }

    @Override
    public Optional<AccessReviewCampaign> findCampaignForUpdate(UUID id) {
        return jdbc.sql(SELECT_CAMPAIGN + " where id = :id for update").param("id", id)
                .query((rs, n) -> mapCampaign(rs)).optional();
    }

    @Override
    public List<AccessReviewCampaign> searchCampaigns(AccessReviewCampaign.Status status) {
        return jdbc.sql(SELECT_CAMPAIGN + " " + """
                         where (cast(:status as varchar) is null or status = :status)
                         order by created_at desc, id""")
                .param("status", status == null ? null : status.name())
                .query((rs, n) -> mapCampaign(rs)).list();
    }

    @Override
    public void saveClosure(AccessReviewCampaign c) {
        jdbc.sql("""
                        update access_review_campaign
                           set status = :status, closed_by = :by, closed_by_username = :byName, closed_at = :at,
                               closure_reason = :reason
                         where id = :id and status = 'OPEN'""")
                .param("status", c.status().name())
                .param("by", c.closedBy().id())
                .param("byName", c.closedBy().username())
                .param("at", Timestamp.from(c.closedAt()))
                .param("reason", c.closureReason())
                .param("id", c.id())
                .update();
    }

    /**
     * One statement per item. {@code on conflict ... do nothing} on the partial unique index makes overlapping and
     * concurrent reviews skip an assignment already PENDING elsewhere instead of failing; the {@code where exists}
     * skips an assignment revoked since it was read.
     */
    @Override
    public int insertItemsSkippingPending(List<AccessReviewItem> items) {
        int inserted = 0;
        for (AccessReviewItem i : items) {
            inserted += jdbc.sql("""
                            insert into access_review_item (id, campaign_id, assignment_id, subject_id,
                                subject_username, role_code, regions, centres, modules, assigned_by,
                                assigned_by_username, valid_from, valid_to, status)
                            select cast(:id as uuid), cast(:campaign as uuid), cast(:assignment as uuid),
                                   cast(:subject as varchar), cast(:subjectName as varchar), cast(:role as varchar),
                                   string_to_array(:regions, ','), string_to_array(:centres, ','),
                                   string_to_array(:modules, ','), cast(:by as varchar), cast(:byName as varchar),
                                   cast(:from as timestamptz), cast(:to as timestamptz), cast(:status as varchar)
                             where exists (select 1 from role_assignment a
                                            where a.id = :assignment and a.status = 'ACTIVE')
                            on conflict (assignment_id) where status = 'PENDING' do nothing""")
                    .param("id", i.id())
                    .param("campaign", i.campaignId())
                    .param("assignment", i.assignmentId())
                    .param("subject", i.subjectId())
                    .param("subjectName", i.subjectUsername())
                    .param("role", i.roleCode())
                    .param("regions", SqlArrays.join(i.scopes().regions()))
                    .param("centres", SqlArrays.join(i.scopes().centres()))
                    .param("modules", SqlArrays.join(i.scopes().modules()))
                    .param("by", i.assignedBy().id())
                    .param("byName", i.assignedBy().username())
                    .param("from", Timestamp.from(i.validFrom()))
                    .param("to", i.validTo() == null ? null : Timestamp.from(i.validTo()))
                    .param("status", i.status().name())
                    .update();
        }
        return inserted;
    }

    @Override
    public Optional<AccessReviewItem> findItem(UUID id) {
        return jdbc.sql(SELECT_ITEM + " where i.id = :id").param("id", id)
                .query((rs, n) -> mapItem(rs)).optional();
    }

    @Override
    public Optional<AccessReviewItem> findItemForUpdate(UUID id) {
        return jdbc.sql(SELECT_ITEM + " where i.id = :id for update of i").param("id", id)
                .query((rs, n) -> mapItem(rs)).optional();
    }

    @Override
    public List<AccessReviewItem> searchItems(UUID campaignId, AccessReviewItem.Status status, String subjectId) {
        return jdbc.sql(SELECT_ITEM + " " + """
                         where (cast(:campaign as uuid) is null or i.campaign_id = :campaign)
                           and (cast(:status as varchar) is null or i.status = :status)
                           and (cast(:subject as varchar) is null or i.subject_id = :subject)
                         order by c.created_at desc, i.campaign_id, i.subject_id, i.role_code, i.id""")
                .param("campaign", campaignId)
                .param("status", status == null ? null : status.name())
                .param("subject", subjectId)
                .query((rs, n) -> mapItem(rs)).list();
    }

    @Override
    public void saveItemDecision(AccessReviewItem i) {
        jdbc.sql("""
                        update access_review_item
                           set status = :status, decided_by = :by, decided_by_username = :byName, decided_at = :at,
                               decision_reason = :reason
                         where id = :id and status = 'PENDING'""")
                .param("status", i.status().name())
                .param("by", i.decidedBy() == null ? null : i.decidedBy().id())
                .param("byName", i.decidedBy() == null ? null : i.decidedBy().username())
                .param("at", i.decidedAt() == null ? null : Timestamp.from(i.decidedAt()))
                .param("reason", i.decisionReason())
                .param("id", i.id())
                .update();
    }

    @Override
    public Map<AccessReviewItem.Status, Integer> counts(UUID campaignId) {
        Map<AccessReviewItem.Status, Integer> counts = new EnumMap<>(AccessReviewItem.Status.class);
        jdbc.sql("select status, count(*) as n from access_review_item where campaign_id = :id group by status")
                .param("id", campaignId)
                .query((rs, n) -> Map.entry(AccessReviewItem.Status.valueOf(rs.getString("status")), rs.getInt("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    @Override
    public Map<UUID, Instant> lastKeptAt(Collection<UUID> assignmentIds) {
        Map<UUID, Instant> kept = new HashMap<>();
        jdbc.sql("""
                        select assignment_id, max(decided_at) as kept_at
                          from access_review_item
                         where status = 'KEPT' and assignment_id = any(cast(string_to_array(:ids, ',') as uuid[]))
                         group by assignment_id""")
                .param("ids", joinIds(assignmentIds))
                .query((rs, n) -> Map.entry(rs.getObject("assignment_id", UUID.class),
                        rs.getTimestamp("kept_at").toInstant()))
                .list()
                .forEach(e -> kept.put(e.getKey(), e.getValue()));
        return kept;
    }

    @Override
    public Map<UUID, UUID> pendingItemIds(Collection<UUID> assignmentIds) {
        Map<UUID, UUID> pending = new HashMap<>();
        jdbc.sql("""
                        select assignment_id, id
                          from access_review_item
                         where status = 'PENDING' and assignment_id = any(cast(string_to_array(:ids, ',') as uuid[]))""")
                .param("ids", joinIds(assignmentIds))
                .query((rs, n) -> Map.entry(rs.getObject("assignment_id", UUID.class), rs.getObject("id", UUID.class)))
                .list()
                .forEach(e -> pending.put(e.getKey(), e.getValue()));
        return pending;
    }

    private static String joinIds(Collection<UUID> ids) {
        return SqlArrays.join(ids.stream().map(UUID::toString).toList());
    }

    private static AccessReviewCampaign mapCampaign(ResultSet rs) throws SQLException {
        String closedBy = rs.getString("closed_by");
        return new AccessReviewCampaign(rs.getObject("id", UUID.class), rs.getString("title"),
                AssignmentScopes.of(SqlArrays.read(rs, "regions"), SqlArrays.read(rs, "centres"),
                        SqlArrays.read(rs, "modules")),
                instant(rs, "due_at"), rs.getString("reason"),
                new Actor(rs.getString("created_by"), rs.getString("created_by_username")),
                instant(rs, "created_at"), AccessReviewCampaign.Status.valueOf(rs.getString("status")),
                closedBy == null ? null : new Actor(closedBy, rs.getString("closed_by_username")),
                instant(rs, "closed_at"), rs.getString("closure_reason"));
    }

    private static AccessReviewItem mapItem(ResultSet rs) throws SQLException {
        String decidedBy = rs.getString("decided_by");
        return new AccessReviewItem(rs.getObject("id", UUID.class), rs.getObject("campaign_id", UUID.class),
                instant(rs, "campaign_due_at"), rs.getObject("assignment_id", UUID.class),
                rs.getString("subject_id"), rs.getString("subject_username"), rs.getString("role_code"),
                AssignmentScopes.of(SqlArrays.read(rs, "regions"), SqlArrays.read(rs, "centres"),
                        SqlArrays.read(rs, "modules")),
                new Actor(rs.getString("assigned_by"), rs.getString("assigned_by_username")),
                instant(rs, "valid_from"), instant(rs, "valid_to"),
                AccessReviewItem.Status.valueOf(rs.getString("status")),
                decidedBy == null ? null : new Actor(decidedBy, rs.getString("decided_by_username")),
                instant(rs, "decided_at"), rs.getString("decision_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
