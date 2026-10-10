-- licensing-service schema, part 3: licence number ranges per region and the numbers handed out (DL-061; format per
-- region, Q-022).

create table licence_number_range (
    id                    uuid           primary key,
    region                varchar(40)    not null,
    prefix                varchar(5)     not null,
    width                 integer        not null,
    first_number          bigint         not null,
    last_number           bigint         not null,
    next_number           bigint         not null,
    status                varchar(10)    not null,
    submitted_at          timestamptz    not null,
    submitted_by          varchar(100)   not null,
    submitted_by_username varchar(200),
    submitted_channel     varchar(20)    not null,
    submit_reason         varchar(500)   not null,
    decided_at            timestamptz,
    decided_by            varchar(100),
    decided_by_username   varchar(200),
    decided_channel       varchar(20),
    decision_reason       varchar(500),
    constraint ck_number_range_region check (region ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    constraint ck_number_range_prefix check (prefix ~ '^[A-Z0-9]{0,5}$'),
    constraint ck_number_range_numbers check (first_number >= 1 and last_number >= first_number
        and next_number between first_number and last_number + 1),
    constraint ck_number_range_width check (width between length(last_number::text) and 12),
    constraint ck_number_range_status check (status in ('PENDING', 'ACTIVE', 'REJECTED')),
    constraint ck_number_range_decided check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_number_range_maker_checker check (status <> 'ACTIVE' or decided_by <> submitted_by)
);

create index ix_number_range_region on licence_number_range (region, status);
create index ix_number_range_prefix on licence_number_range (prefix, status);

-- Decided once; only an active range moves its next number, and only forward; never deleted.
create function licence_number_range_guard() returns trigger language plpgsql as $$
declare
    mutable text[] := array['status', 'decided_at', 'decided_by', 'decided_by_username', 'decided_channel',
                            'decision_reason', 'next_number'];
begin
    if tg_op = 'DELETE' then
        raise exception 'licence number ranges are never deleted (DL-061)';
    end if;
    if (to_jsonb(new) - mutable) is distinct from (to_jsonb(old) - mutable) then
        raise exception 'a licence number range never changes after it is submitted (DL-061)';
    end if;
    if old.status = 'PENDING' then
        if new.status = 'PENDING' or new.next_number <> old.next_number then
            raise exception 'a pending licence number range is only approved or rejected (DL-061)';
        end if;
    elsif old.status = 'ACTIVE' then
        if (to_jsonb(new) - 'next_number') is distinct from (to_jsonb(old) - 'next_number')
           or new.next_number < old.next_number then
            raise exception 'an active licence number range only hands out numbers forward (DL-061)';
        end if;
    else
        raise exception 'a rejected licence number range never changes (DL-061)';
    end if;
    return new;
end
$$;

create trigger trg_licence_number_range_guard before update or delete on licence_number_range
    for each row execute function licence_number_range_guard();

-- Every number handed out, once; kept for the used-numbers report.
create table licence_number (
    licence_number varchar(20)   primary key,
    range_id       uuid          not null references licence_number_range (id),
    region         varchar(40)   not null,
    number         bigint        not null,
    allocated_at   timestamptz   not null,
    allocated_to   varchar(100)  not null,
    constraint uq_licence_number_range unique (range_id, number)
);

create function licence_number_guard() returns trigger language plpgsql as $$
begin
    raise exception 'a licence number once handed out never changes (DL-061)';
end
$$;

create trigger trg_licence_number_guard before update or delete on licence_number
    for each row execute function licence_number_guard();
