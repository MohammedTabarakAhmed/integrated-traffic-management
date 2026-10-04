-- Slice 1.4: quarterly access reviews by managers (SEC-007 manager half, ADR-026).
-- Rules are enforced in the domain and again here (defence in depth). Campaigns and items are append-only evidence
-- with a single guarded transition each, so every past state can be reconstructed (DAT-002).

-- ---------------------------------------------------------------- campaigns

-- An access review over a data scope, opened by an administrator with a due date (Q-038). Never deleted; closed once.
-- iam_strip mirrors Java String.strip() (Character.isWhitespace), which the domain uses: btrim only removes
-- spaces, and [[:space:]] would also remove no-break spaces (U+00A0, U+2007, U+202F) that Java keeps. The text
-- checks below must accept exactly what the domain accepts, so one bad row never breaks rehydration (DAT-002).
create function iam_strip(value text) returns text language sql immutable strict parallel safe as $$
    select regexp_replace(value, '^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$', '', 'g')
$$;

create table access_review_campaign (
    id                  uuid          primary key,
    title               varchar(200)  not null,
    regions             varchar(40)[] not null,
    centres             varchar(40)[] not null,
    modules             varchar(40)[] not null,
    due_at              timestamptz   not null,
    reason              varchar(500)  not null,
    status              varchar(10)   not null,
    created_by          varchar(100)  not null,
    created_by_username varchar(200),
    created_at          timestamptz   not null,
    closed_by           varchar(100),
    closed_by_username  varchar(200),
    closed_at           timestamptz,
    closure_reason      varchar(500),
    constraint ck_campaign_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_campaign_title check (char_length(iam_strip(title)) >= 3),
    -- Same 3-character rule as Codes.reason: one bad row must not break every listing that rehydrates it.
    constraint ck_campaign_reason check (char_length(iam_strip(reason)) >= 3),
    constraint ck_campaign_closure_reason check (closure_reason is null or char_length(iam_strip(closure_reason)) >= 3),
    constraint ck_campaign_due check (due_at > created_at),
    constraint ck_campaign_closed check (
        (status = 'CLOSED') = (closed_at is not null and closed_by is not null and closure_reason is not null)),
    constraint ck_campaign_scopes check (
        cardinality(regions) between 1 and 100 and cardinality(centres) between 1 and 100
        and cardinality(modules) between 1 and 100)
);

create index ix_campaign_status on access_review_campaign (status, created_at);

-- Campaigns are append-only except a single OPEN -> CLOSED transition that sets only the closure fields.
create function access_review_campaign_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'access review campaigns cannot be deleted (DAT-002)';
    end if;
    if old.status <> 'OPEN' or new.status <> 'CLOSED'
       or (to_jsonb(new) - array['status', 'closed_by', 'closed_by_username', 'closed_at', 'closure_reason'])
          is distinct from
          (to_jsonb(old) - array['status', 'closed_by', 'closed_by_username', 'closed_at', 'closure_reason']) then
        raise exception 'access review campaigns can only be closed once; other changes are not allowed (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_access_review_campaign_guard
    before update or delete on access_review_campaign
    for each row execute function access_review_campaign_guard();

-- ---------------------------------------------------------------- items

-- One ACTIVE DIRECT role assignment under review, with a snapshot of the assignment taken when the review opened
-- (Q-039), and its outcome: KEPT / REVOKED by a reviewer, or NOT_REVIEWED / ENDED when the review closed (Q-037).
create table access_review_item (
    id                   uuid          primary key,
    campaign_id          uuid          not null references access_review_campaign (id),
    assignment_id        uuid          not null references role_assignment (id),
    subject_id           varchar(100)  not null,
    subject_username     varchar(200),
    role_code            varchar(60)   not null,
    regions              varchar(40)[] not null,
    centres              varchar(40)[] not null,
    modules              varchar(40)[] not null,
    assigned_by          varchar(100)  not null,
    assigned_by_username varchar(200),
    valid_from           timestamptz   not null,
    valid_to             timestamptz,
    status               varchar(12)   not null,
    decided_by           varchar(100),
    decided_by_username  varchar(200),
    decided_at           timestamptz,
    decision_reason      varchar(500),
    constraint uq_item_campaign_assignment unique (campaign_id, assignment_id),
    constraint ck_item_status check (status in ('PENDING', 'KEPT', 'REVOKED', 'NOT_REVIEWED', 'ENDED')),
    -- Only a reviewer's decision (KEPT or REVOKED) has a decider; a REVOKE always has a reason.
    constraint ck_item_decided check (
        (status in ('KEPT', 'REVOKED')) = (decided_by is not null and decided_at is not null)),
    constraint ck_item_revoke_reason check (status <> 'REVOKED' or decision_reason is not null),
    constraint ck_item_reason_needs_decision check (decision_reason is null or decided_by is not null),
    constraint ck_item_decision_reason check (decision_reason is null or char_length(iam_strip(decision_reason)) >= 3),
    -- Segregation of duties (CC-002, Q-040): nobody reviews their own access or an assignment they granted.
    constraint ck_item_no_self_review check (decided_by is null or decided_by <> subject_id),
    constraint ck_item_not_assigner check (decided_by is null or decided_by <> assigned_by),
    constraint ck_item_scopes check (
        cardinality(regions) between 1 and 100 and cardinality(centres) between 1 and 100
        and cardinality(modules) between 1 and 100)
);

-- One pending review per assignment: overlapping or concurrent reviews never review the same assignment twice.
create unique index uq_item_pending on access_review_item (assignment_id) where status = 'PENDING';
-- Overdue listing: when was each assignment last kept.
create index ix_item_kept on access_review_item (assignment_id, decided_at) where status = 'KEPT';
create index ix_item_campaign on access_review_item (campaign_id, status);

-- Items are added only to an OPEN campaign, only for an ACTIVE DIRECT assignment, and the snapshot must match it.
create function access_review_item_insert_check() returns trigger language plpgsql as $$
begin
    if new.status <> 'PENDING' then
        raise exception 'a new access review item must be PENDING (SEC-007)';
    end if;
    if not exists (select 1 from access_review_campaign c where c.id = new.campaign_id and c.status = 'OPEN') then
        raise exception 'access review items can only be added to an open review (SEC-007)';
    end if;
    if not exists (select 1 from role_assignment a
                    where a.id = new.assignment_id and a.status = 'ACTIVE' and a.source = 'DIRECT'
                      and a.subject_id = new.subject_id and a.role_code = new.role_code
                      and a.assigned_by = new.assigned_by
                      and a.regions @> new.regions and a.regions <@ new.regions
                      and a.centres @> new.centres and a.centres <@ new.centres
                      and a.modules @> new.modules and a.modules <@ new.modules) then
        raise exception 'only an ACTIVE DIRECT role assignment can be reviewed, with its own snapshot (SEC-007)';
    end if;
    return new;
end
$$;

create trigger trg_access_review_item_insert_check
    before insert on access_review_item
    for each row execute function access_review_item_insert_check();

-- Items are append-only except a single PENDING -> outcome transition that sets only the decision fields.
create function access_review_item_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'access review items cannot be deleted (DAT-002)';
    end if;
    if old.status <> 'PENDING' or new.status = 'PENDING'
       or (to_jsonb(new) - array['status', 'decided_by', 'decided_by_username', 'decided_at', 'decision_reason'])
          is distinct from
          (to_jsonb(old) - array['status', 'decided_by', 'decided_by_username', 'decided_at', 'decision_reason']) then
        raise exception 'access review items can only be decided or closed once; other changes are not allowed (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_access_review_item_guard
    before update or delete on access_review_item
    for each row execute function access_review_item_guard();

-- A reviewer decides only while the review is OPEN; a REVOKED item needs its assignment revoked first (the service
-- revokes the assignment, then saves the item, in one transaction). The closure outcomes NOT_REVIEWED and ENDED are
-- only set by closing the review: the service marks the campaign CLOSED first, then the pending items (Q-037).
create function access_review_item_decision_check() returns trigger language plpgsql as $$
begin
    if new.status in ('KEPT', 'REVOKED')
       and not exists (select 1 from access_review_campaign c where c.id = new.campaign_id and c.status = 'OPEN') then
        raise exception 'access review items can only be decided while the review is open (SEC-007)';
    end if;
    -- (old.status = 'PENDING': any other transition is refused by access_review_item_guard with its own message.)
    if old.status = 'PENDING' and new.status in ('NOT_REVIEWED', 'ENDED')
       and not exists (select 1 from access_review_campaign c where c.id = new.campaign_id and c.status = 'CLOSED') then
        raise exception 'access review items become NOT_REVIEWED or ENDED only when the review is closed (SEC-007)';
    end if;
    if new.status = 'REVOKED'
       and not exists (select 1 from role_assignment a where a.id = new.assignment_id and a.status = 'REVOKED') then
        raise exception 'a REVOKED review item needs its role assignment to be revoked first (SEC-007)';
    end if;
    return new;
end
$$;

create trigger trg_access_review_item_decision_check
    before update on access_review_item
    for each row execute function access_review_item_decision_check();

-- ---------------------------------------------------------------- catalogue and roles

insert into permission (code, service, description_nl, description_en) values
    ('iam.review.manage', 'iam-service', 'فتح مراجعات الصلاحيات وإغلاقها',       'Open and close access reviews'),
    ('iam.review.read',   'iam-service', 'عرض مراجعات الصلاحيات',                'Read access reviews'),
    ('iam.review.decide', 'iam-service', 'البت في عناصر مراجعة الصلاحيات (إبقاء أو إلغاء)',
                                         'Decide access review items (keep or revoke)');

-- Administrators open and close reviews but do not decide them: those who grant access do not certify it (ADR-026).
update role
   set permissions = permissions || array['iam.review.manage', 'iam.review.read']::varchar[],
       version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
 where code = 'IT_ADMINISTRATOR';

-- The managers who review access (Q-036): assigned by IT administrators, scoped to their regions, centres, modules.
insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('ACCESS_REVIEWER', 'مراجع الصلاحيات (مدير)', 'Access reviewer (manager)',
     'Reviews access rights every quarter and keeps or revokes them within its scopes (SEC-007).', true,
     array['iam.review.read', 'iam.review.decide'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');
