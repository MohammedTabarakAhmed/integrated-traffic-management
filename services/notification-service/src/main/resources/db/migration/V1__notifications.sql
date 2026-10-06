-- notification-service schema, part 1: templates, recipients, notifications with delivery status, event subscriptions
-- and expiry watches (CC-070..CC-074; ADR-050). Owned exclusively by this service (ADR-016).

create table notification_template (
    code                  varchar(60)   not null,
    version_no            integer       not null,
    category              varchar(60)   not null,
    mandatory             boolean       not null,
    contents              jsonb         not null,
    published_at          timestamptz   not null,
    published_by          varchar(100)  not null,
    published_by_username varchar(200),
    constraint pk_notification_template primary key (code, version_no),
    constraint ck_template_codes check (code ~ '^[A-Z][A-Z0-9_]{1,59}$' and category ~ '^[A-Z][A-Z0-9_]{1,59}$')
);

create function notification_template_guard() returns trigger language plpgsql as $$
begin
    raise exception 'template versions never change and are never deleted (CC-070)';
end
$$;

create trigger trg_notification_template_guard before update or delete on notification_template
    for each row execute function notification_template_guard();

-- Contact points are personal data (SEC-014): masked in every API response and audit record.
create table recipient (
    id          varchar(100)  primary key,
    language    varchar(2)    not null,
    phone       varchar(16),
    email       varchar(254),
    push_token  varchar(500),
    preferences jsonb         not null,
    updated_at  timestamptz   not null,
    constraint ck_recipient_language check (language in ('NL', 'EN'))
);

create table notification (
    id                  uuid          primary key,
    recipient_id        varchar(100)  not null,
    template_code       varchar(60)   not null,
    template_version    integer       not null,
    category            varchar(60)   not null,
    mandatory           boolean       not null,
    channel             varchar(10)   not null,
    destination         varchar(500)  not null,
    language            varchar(2)    not null,
    subject             varchar(200),
    body                varchar(10000) not null,
    reference           varchar(60),
    source_event_id     varchar(100),
    created_at          timestamptz   not null,
    status              varchar(10)   not null,
    attempts            integer       not null,
    next_attempt_at     timestamptz,
    provider_message_id varchar(200),
    last_error          varchar(500),
    sent_at             timestamptz,
    delivered_at        timestamptz,
    read_at             timestamptz,
    constraint fk_notification_template foreign key (template_code, template_version)
        references notification_template (code, version_no),
    constraint ck_notification_channel check (channel in ('SMS', 'EMAIL', 'PUSH', 'INBOX')),
    constraint ck_notification_status check (status in ('QUEUED', 'SENT', 'DELIVERED', 'FAILED')),
    constraint ck_notification_queued check ((status = 'QUEUED') = (next_attempt_at is not null)),
    constraint ck_notification_read check (read_at is null or channel = 'INBOX')
);

create index ix_notification_due on notification (next_attempt_at) where status = 'QUEUED';
create index ix_notification_recipient on notification (recipient_id, created_at desc);
create index ix_notification_inbox on notification (recipient_id, created_at desc) where channel = 'INBOX';
create unique index uq_notification_provider on notification (provider_message_id)
    where provider_message_id is not null;

-- Delivery status only moves forward and the content never changes (CC-074); notifications are never deleted here
-- (retention, section 18, is applied by the archiving job).
create function notification_guard() returns trigger language plpgsql as $$
declare
    mutable text[] := array['status', 'attempts', 'next_attempt_at', 'provider_message_id', 'last_error', 'sent_at',
                            'delivered_at', 'read_at'];
begin
    if tg_op = 'DELETE' then
        raise exception 'notifications are never deleted by the service (CC-074)';
    end if;
    if (to_jsonb(new) - mutable) is distinct from (to_jsonb(old) - mutable) then
        raise exception 'a notification''s content never changes (CC-074)';
    end if;
    if old.status in ('DELIVERED', 'FAILED') and (new.status <> old.status
       or (to_jsonb(new) - 'read_at') is distinct from (to_jsonb(old) - 'read_at')) then
        raise exception 'a delivered or failed notification never changes (CC-074)';
    end if;
    return new;
end
$$;

create trigger trg_notification_guard before update or delete on notification
    for each row execute function notification_guard();

create table event_subscription (
    event_type      varchar(100)  not null,
    template_code   varchar(60)   not null,
    recipient_field varchar(40)   not null,
    reference_field varchar(40),
    active          boolean       not null,
    updated_at      timestamptz   not null,
    constraint pk_event_subscription primary key (event_type, template_code)
);

create table expiry_watch (
    subject_type varchar(20)   not null,
    subject_id   varchar(60)   not null,
    recipient_id varchar(100)  not null,
    expiry_date  date          not null,
    reference    varchar(60),
    updated_at   timestamptz   not null,
    constraint pk_expiry_watch primary key (subject_type, subject_id),
    constraint ck_expiry_subject check (subject_type in ('LICENCE', 'REGISTRATION', 'PERMIT', 'INSURANCE'))
);

create index ix_expiry_watch_date on expiry_watch (expiry_date);

-- One row per reminder sent, so a reminder goes out once per expiry date and offset (CC-072).
create table expiry_reminder_sent (
    subject_type varchar(20)  not null,
    subject_id   varchar(60)  not null,
    expiry_date  date         not null,
    offset_days  integer      not null,
    sent_at      timestamptz  not null,
    constraint pk_expiry_reminder_sent primary key (subject_type, subject_id, expiry_date, offset_days)
);
