package gov.itms.notification.infrastructure;

import gov.itms.notification.application.NotificationRepository;
import gov.itms.notification.domain.Actor;
import gov.itms.notification.domain.DeliveryChannel;
import gov.itms.notification.domain.Language;
import gov.itms.notification.domain.Notification;
import gov.itms.notification.domain.Recipient;
import gov.itms.notification.domain.Subscriptions;
import gov.itms.notification.domain.Template;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Notifications in PostgreSQL; the database guards template versions and delivery status (V1). */
class JdbcNotificationRepository implements NotificationRepository {

    private static final String TEMPLATE_SELECT = """
            select code, version_no, category, mandatory, contents::text as contents, published_at, published_by,
                published_by_username
            from notification_template""";
    private static final String NOTIFICATION_SELECT = """
            select id, recipient_id, template_code, template_version, category, mandatory, channel, destination,
                language, subject, body, reference, source_event_id, created_at, status, attempts, next_attempt_at,
                provider_message_id, last_error, sent_at, delivered_at, read_at
            from notification""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcNotificationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- templates

    @Override
    public void lockTemplate(String code) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('template:' || :c))").param("c", code).query().listOfRows();
    }

    @Override
    public int nextTemplateVersion(String code) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from notification_template where code = :c")
                .param("c", code).query(Integer.class).single();
    }

    @Override
    public void insertTemplate(Template t) {
        ObjectNode contents = json.createObjectNode();
        t.contents().forEach((channel, c) -> {
            ObjectNode n = contents.putObject(channel.name());
            n.put("subjectNl", c.subjectNl());
            n.put("subjectEn", c.subjectEn());
            n.put("bodyNl", c.bodyNl());
            n.put("bodyEn", c.bodyEn());
        });
        jdbc.sql("""
                        insert into notification_template (code, version_no, category, mandatory, contents,
                            published_at, published_by, published_by_username)
                        values (:code, :no, :category, :mandatory, cast(:contents as jsonb), :at, :by, :byName)""")
                .param("code", t.code())
                .param("no", t.versionNo())
                .param("category", t.category())
                .param("mandatory", t.mandatory())
                .param("contents", json.writeValueAsString(contents))
                .param("at", Timestamp.from(t.publishedAt()))
                .param("by", t.publishedBy().id())
                .param("byName", t.publishedBy().username())
                .update();
    }

    @Override
    public Optional<Template> latestTemplate(String code) {
        return jdbc.sql(TEMPLATE_SELECT + " where code = :c order by version_no desc limit 1").param("c", code)
                .query(this::mapTemplate).optional();
    }

    @Override
    public Optional<Template> findTemplate(String code, int versionNo) {
        return jdbc.sql(TEMPLATE_SELECT + " where code = :c and version_no = :v").param("c", code)
                .param("v", versionNo).query(this::mapTemplate).optional();
    }

    @Override
    public List<Template> latestTemplates() {
        return jdbc.sql(TEMPLATE_SELECT + " " + """
                         t where version_no = (select max(version_no) from notification_template x
                             where x.code = t.code) order by code""")
                .query(this::mapTemplate).list();
    }

    // ---- recipients

    @Override
    public Optional<Recipient> findRecipient(String id) {
        return jdbc.sql("""
                        select id, language, phone, email, push_token, preferences::text as preferences
                        from recipient where id = :id""")
                .param("id", id).query(this::mapRecipient).optional();
    }

    @Override
    public void saveRecipient(Recipient r) {
        ObjectNode prefs = json.createObjectNode();
        r.preferences().forEach((category, channels) -> {
            var a = prefs.putArray(category);
            channels.stream().map(Enum::name).sorted().forEach(a::add);
        });
        jdbc.sql("""
                        insert into recipient (id, language, phone, email, push_token, preferences, updated_at)
                        values (:id, :language, :phone, :email, :push, cast(:prefs as jsonb), now())
                        on conflict (id) do update set language = excluded.language, phone = excluded.phone,
                            email = excluded.email, push_token = excluded.push_token,
                            preferences = excluded.preferences, updated_at = now()""")
                .param("id", r.id())
                .param("language", r.language().name())
                .param("phone", r.phone())
                .param("email", r.email())
                .param("push", r.pushToken())
                .param("prefs", json.writeValueAsString(prefs))
                .update();
    }

    // ---- notifications

    @Override
    public void insertNotification(Notification n) {
        jdbc.sql("""
                        insert into notification (id, recipient_id, template_code, template_version, category,
                            mandatory, channel, destination, language, subject, body, reference, source_event_id,
                            created_at, status, attempts, next_attempt_at, provider_message_id, last_error, sent_at,
                            delivered_at, read_at)
                        values (:id, :recipient, :template, :templateVersion, :category, :mandatory, :channel,
                            :destination, :language, :subject, :body, :reference, :eventId, :createdAt, :status,
                            :attempts, :next, :provider, :error, :sentAt, :deliveredAt, :readAt)""")
                .param("id", n.id())
                .param("recipient", n.recipientId())
                .param("template", n.templateCode())
                .param("templateVersion", n.templateVersion())
                .param("category", n.category())
                .param("mandatory", n.mandatory())
                .param("channel", n.channel().name())
                .param("destination", n.destination())
                .param("language", n.language().name())
                .param("subject", n.subject())
                .param("body", n.body())
                .param("reference", n.reference())
                .param("eventId", n.sourceEventId())
                .param("createdAt", Timestamp.from(n.createdAt()))
                .param("status", n.status().name())
                .param("attempts", n.attempts())
                .param("next", ts(n.nextAttemptAt()))
                .param("provider", n.providerMessageId())
                .param("error", n.lastError())
                .param("sentAt", ts(n.sentAt()))
                .param("deliveredAt", ts(n.deliveredAt()))
                .param("readAt", ts(n.readAt()))
                .update();
    }

    @Override
    public Optional<Notification> findNotification(UUID id) {
        return jdbc.sql(NOTIFICATION_SELECT + " where id = :id").param("id", id).query(this::mapNotification)
                .optional();
    }

    @Override
    public Optional<Notification> findByProviderId(String providerMessageId) {
        return jdbc.sql(NOTIFICATION_SELECT + " where provider_message_id = :p for update").param("p", providerMessageId)
                .query(this::mapNotification).optional();
    }

    @Override
    public void updateNotification(Notification n) {
        jdbc.sql("""
                        update notification set status = :status, attempts = :attempts, next_attempt_at = :next,
                            provider_message_id = :provider, last_error = :error, sent_at = :sentAt,
                            delivered_at = :deliveredAt, read_at = :readAt
                        where id = :id""")
                .param("status", n.status().name())
                .param("attempts", n.attempts())
                .param("next", ts(n.nextAttemptAt()))
                .param("provider", n.providerMessageId())
                .param("error", n.lastError())
                .param("sentAt", ts(n.sentAt()))
                .param("deliveredAt", ts(n.deliveredAt()))
                .param("readAt", ts(n.readAt()))
                .param("id", n.id())
                .update();
    }

    @Override
    public List<Notification> byRecipient(String recipientId, String reference, int limit) {
        return jdbc.sql(NOTIFICATION_SELECT + """
                          where recipient_id = :r and (cast(:ref as varchar) is null or reference = :ref)
                         order by created_at desc, id limit :limit""")
                .param("r", recipientId).param("ref", reference).param("limit", limit)
                .query(this::mapNotification).list();
    }

    @Override
    public List<Notification> inbox(String recipientId, boolean unreadOnly, int limit) {
        return jdbc.sql(NOTIFICATION_SELECT + """
                          where recipient_id = :r and channel = 'INBOX' and (not :unread or read_at is null)
                         order by created_at desc, id limit :limit""")
                .param("r", recipientId).param("unread", unreadOnly).param("limit", limit)
                .query(this::mapNotification).list();
    }

    @Override
    public List<Notification> dueForSending(Instant now, int limit) {
        return jdbc.sql(NOTIFICATION_SELECT + """
                          where status = 'QUEUED' and next_attempt_at <= :now
                         order by next_attempt_at limit :limit for update skip locked""")
                .param("now", Timestamp.from(now)).param("limit", limit).query(this::mapNotification).list();
    }

    // ---- subscriptions and expiry watches

    @Override
    public void saveSubscription(Subscriptions.EventSubscription s) {
        jdbc.sql("""
                        insert into event_subscription (event_type, template_code, recipient_field, reference_field,
                            active, updated_at)
                        values (:type, :template, :recipient, :reference, :active, now())
                        on conflict (event_type, template_code) do update set
                            recipient_field = excluded.recipient_field, reference_field = excluded.reference_field,
                            active = excluded.active, updated_at = now()""")
                .param("type", s.eventType())
                .param("template", s.templateCode())
                .param("recipient", s.recipientField())
                .param("reference", s.referenceField())
                .param("active", s.active())
                .update();
    }

    @Override
    public List<Subscriptions.EventSubscription> subscriptions(String eventType) {
        return jdbc.sql(SUBSCRIPTION_SELECT + " where event_type = :t order by template_code").param("t", eventType)
                .query(JdbcNotificationRepository::mapSubscription).list();
    }

    @Override
    public List<Subscriptions.EventSubscription> allSubscriptions() {
        return jdbc.sql(SUBSCRIPTION_SELECT + " order by event_type, template_code")
                .query(JdbcNotificationRepository::mapSubscription).list();
    }

    private static final String SUBSCRIPTION_SELECT = """
            select event_type, template_code, recipient_field, reference_field, active from event_subscription""";

    @Override
    public void saveExpiryWatch(Subscriptions.ExpiryWatch w) {
        jdbc.sql("""
                        insert into expiry_watch (subject_type, subject_id, recipient_id, expiry_date, reference,
                            updated_at)
                        values (:type, :id, :recipient, :expiry, :reference, now())
                        on conflict (subject_type, subject_id) do update set recipient_id = excluded.recipient_id,
                            expiry_date = excluded.expiry_date, reference = excluded.reference, updated_at = now()""")
                .param("type", w.subjectType().name())
                .param("id", w.subjectId())
                .param("recipient", w.recipientId())
                .param("expiry", Date.valueOf(w.expiryDate()))
                .param("reference", w.reference())
                .update();
    }

    @Override
    public List<DueReminder> remindersDue(LocalDate today, List<Integer> offsets, int limit) {
        if (offsets.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select w.subject_type, w.subject_id, w.recipient_id, w.expiry_date, w.reference,
                            (w.expiry_date - cast(:today as date)) as offset_days
                        from expiry_watch w
                        where (w.expiry_date - cast(:today as date)) = any(string_to_array(:offsets, ',')::integer[])
                          and not exists (select 1 from expiry_reminder_sent s where s.subject_type = w.subject_type
                              and s.subject_id = w.subject_id and s.expiry_date = w.expiry_date
                              and s.offset_days = (w.expiry_date - cast(:today as date)))
                        order by w.expiry_date limit :limit""")
                .param("today", Date.valueOf(today))
                .param("offsets", offsets.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .param("limit", limit)
                .query((rs, n) -> new DueReminder(new Subscriptions.ExpiryWatch(
                        Subscriptions.SubjectType.valueOf(rs.getString("subject_type")), rs.getString("subject_id"),
                        rs.getString("recipient_id"), rs.getDate("expiry_date").toLocalDate(),
                        rs.getString("reference")), rs.getInt("offset_days")))
                .list();
    }

    @Override
    public boolean markReminderSent(Subscriptions.SubjectType type, String subjectId, LocalDate expiryDate,
                                    int offsetDays) {
        return jdbc.sql("""
                        insert into expiry_reminder_sent (subject_type, subject_id, expiry_date, offset_days, sent_at)
                        values (:type, :id, :expiry, :offset, now()) on conflict do nothing""")
                .param("type", type.name()).param("id", subjectId).param("expiry", Date.valueOf(expiryDate))
                .param("offset", offsetDays).update() == 1;
    }

    // ---- mapping

    private Template mapTemplate(ResultSet rs, int row) throws SQLException {
        Map<DeliveryChannel, Template.Content> contents = new EnumMap<>(DeliveryChannel.class);
        json.readTree(rs.getString("contents")).properties().forEach(e -> {
            JsonNode c = e.getValue();
            contents.put(DeliveryChannel.valueOf(e.getKey()), new Template.Content(text(c, "subjectNl"),
                    text(c, "subjectEn"), text(c, "bodyNl"), text(c, "bodyEn")));
        });
        return new Template(rs.getString("code"), rs.getInt("version_no"), rs.getString("category"),
                rs.getBoolean("mandatory"), contents,
                new Actor(rs.getString("published_by"), rs.getString("published_by_username")),
                rs.getTimestamp("published_at").toInstant());
    }

    private Recipient mapRecipient(ResultSet rs, int row) throws SQLException {
        Map<String, Set<DeliveryChannel>> prefs = new LinkedHashMap<>();
        json.readTree(rs.getString("preferences")).properties().forEach(e -> {
            Set<DeliveryChannel> channels = EnumSet.noneOf(DeliveryChannel.class);
            e.getValue().forEach(c -> channels.add(DeliveryChannel.valueOf(c.asString())));
            prefs.put(e.getKey(), channels);
        });
        return new Recipient(rs.getString("id"), Language.valueOf(rs.getString("language")), rs.getString("phone"),
                rs.getString("email"), rs.getString("push_token"), prefs);
    }

    private Notification mapNotification(ResultSet rs, int row) throws SQLException {
        return Notification.restore(rs.getObject("id", UUID.class), rs.getString("recipient_id"),
                rs.getString("template_code"), rs.getInt("template_version"), rs.getString("category"),
                rs.getBoolean("mandatory"), DeliveryChannel.valueOf(rs.getString("channel")),
                rs.getString("destination"), Language.valueOf(rs.getString("language")), rs.getString("subject"),
                rs.getString("body"), rs.getString("reference"), rs.getString("source_event_id"),
                rs.getTimestamp("created_at").toInstant(), Notification.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")),
                rs.getString("provider_message_id"), rs.getString("last_error"), instant(rs.getTimestamp("sent_at")),
                instant(rs.getTimestamp("delivered_at")), instant(rs.getTimestamp("read_at")));
    }

    private static Subscriptions.EventSubscription mapSubscription(ResultSet rs, int row) throws SQLException {
        return new Subscriptions.EventSubscription(rs.getString("event_type"), rs.getString("template_code"),
                rs.getString("recipient_field"), rs.getString("reference_field"), rs.getBoolean("active"));
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
