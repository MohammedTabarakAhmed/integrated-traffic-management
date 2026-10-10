-- licensing-service schema, part 6: black-point suspensions mirrored from violation-service (DL-058, VF-032, UAT-1)
-- and no-objection certificates (DL-059).

create table licence_suspension (
    id           uuid          primary key,
    licence_id   uuid          not null references licence (id),
    source       varchar(20)   not null,
    source_ref   varchar(100)  not null,
    ordinal      integer       not null,
    from_date    date          not null,
    until_date   date          not null,
    received_at  timestamptz   not null,
    constraint uq_licence_suspension unique (licence_id, source, source_ref),
    constraint ck_licence_suspension_dates check (until_date >= from_date)
);

create index ix_licence_suspension_licence on licence_suspension (licence_id, from_date);
create index ix_licence_suspended_until on licence (suspended_until) where status = 'SUSPENDED';

create function licence_suspension_guard() returns trigger language plpgsql as $$
begin
    raise exception 'a licence suspension record never changes (DL-058)';
end
$$;

create trigger trg_licence_suspension_guard before update or delete on licence_suspension
    for each row execute function licence_suspension_guard();

create sequence licence_certificate_seq;

create table licence_certificate (
    id                 uuid          primary key,
    licence_id         uuid          not null references licence (id),
    number             varchar(30)   not null unique,
    purpose            varchar(200)  not null,
    addressee          varchar(200)  not null,
    verification_code  varchar(20)   not null,
    issued_on          date          not null,
    valid_until        date          not null,
    request_id         uuid          references licence_request (id),
    issued_at          timestamptz   not null,
    constraint ck_licence_certificate_dates check (valid_until >= issued_on)
);

create index ix_licence_certificate_licence on licence_certificate (licence_id, issued_at desc);

create function licence_certificate_guard() returns trigger language plpgsql as $$
begin
    raise exception 'a no-objection certificate never changes (DL-059)';
end
$$;

create trigger trg_licence_certificate_guard before update or delete on licence_certificate
    for each row execute function licence_certificate_guard();
