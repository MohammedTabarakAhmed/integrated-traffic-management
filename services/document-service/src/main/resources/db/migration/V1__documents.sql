-- document-service schema, part 1: document metadata and links, certificate templates and issued certificates
-- (CC-080..CC-085; ADR-051). Content lives in the object store (ADR-005); the database keeps its SHA-256. Owned
-- exclusively by this service (ADR-016).

create table document (
    id                  uuid          primary key,
    type                varchar(40)   not null,
    owner_type          varchar(40)   not null,
    owner_id            varchar(100)  not null,
    title               varchar(200),
    classification      varchar(12)   not null,
    expiry_date         date,
    status              varchar(16)   not null,
    media_type          varchar(100),
    size_bytes          bigint,
    sha256              char(64),
    object_key          varchar(200),
    scan_result         varchar(200),
    created_by          varchar(100)  not null,
    created_by_username varchar(200),
    created_at          timestamptz   not null,
    available_at        timestamptz,
    constraint ck_document_classification check (classification in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET')),
    constraint ck_document_status check (status in ('PENDING_UPLOAD', 'AVAILABLE', 'QUARANTINED')),
    constraint ck_document_available check ((status = 'AVAILABLE') = (object_key is not null and sha256 is not null
        and available_at is not null)),
    constraint ck_document_quarantine check (status <> 'QUARANTINED' or object_key is null)
);

create index ix_document_owner on document (owner_type, owner_id, created_at desc);

-- Content is written once (CC-080): only PENDING_UPLOAD may move to AVAILABLE or QUARANTINED; the declared metadata
-- never changes; documents are never deleted by the service (retention, section 18, is the archiving job's).
create function document_guard() returns trigger language plpgsql as $$
declare
    content text[] := array['status', 'media_type', 'size_bytes', 'sha256', 'object_key', 'scan_result',
                            'available_at'];
begin
    if tg_op = 'DELETE' then
        raise exception 'documents are never deleted by the service (CC-080)';
    end if;
    if (to_jsonb(new) - content) is distinct from (to_jsonb(old) - content) then
        raise exception 'a document''s declared metadata never changes (CC-080)';
    end if;
    if old.status <> 'PENDING_UPLOAD' then
        raise exception 'a document''s content is written once (CC-080)';
    end if;
    return new;
end
$$;

create trigger trg_document_guard before update or delete on document
    for each row execute function document_guard();

create table document_link (
    document_id        uuid          not null references document (id),
    record_type        varchar(40)   not null,
    record_id          varchar(100)  not null,
    linked_by          varchar(100)  not null,
    linked_by_username varchar(200),
    linked_at          timestamptz   not null,
    constraint pk_document_link primary key (document_id, record_type, record_id)
);

create index ix_document_link_record on document_link (record_type, record_id);

create table certificate_template (
    code                  varchar(40)    not null,
    version_no            integer        not null,
    title_nl              varchar(200)   not null,
    title_en              varchar(200)   not null,
    body_nl               varchar(20000) not null,
    body_en               varchar(20000) not null,
    validity_days         integer,
    published_by          varchar(100)   not null,
    published_by_username varchar(200),
    published_at          timestamptz    not null,
    constraint pk_certificate_template primary key (code, version_no),
    constraint ck_certificate_validity check (validity_days is null or validity_days between 1 and 3650)
);

create sequence certificate_seq;

create table certificate (
    number              varchar(40)   primary key,
    verification_code   varchar(10)   not null,
    template_code       varchar(40)   not null,
    template_version    integer       not null,
    holder_name_nl      varchar(200)  not null,
    holder_name_en      varchar(200)  not null,
    subject_type        varchar(40)   not null,
    subject_id          varchar(100)  not null,
    data                jsonb         not null,
    reference           varchar(60),
    issued_on           date          not null,
    valid_until         date,
    status              varchar(8)    not null,
    revoked_at          timestamptz,
    revocation_reason   varchar(500),
    document_id         uuid          not null references document (id),
    issued_by           varchar(100)  not null,
    issued_by_username  varchar(200),
    issued_at           timestamptz   not null,
    constraint fk_certificate_template foreign key (template_code, template_version)
        references certificate_template (code, version_no),
    constraint ck_certificate_status check (status in ('ISSUED', 'REVOKED')),
    constraint ck_certificate_revoked check ((status = 'REVOKED') = (revoked_at is not null))
);

create index ix_certificate_subject on certificate (subject_type, subject_id);

-- Tamper protection for legally significant records (SEC, CC-083): templates never change; a certificate only moves
-- from ISSUED to REVOKED, once.
create function certificate_template_guard() returns trigger language plpgsql as $$
begin
    raise exception 'certificate template versions never change (CC-082)';
end
$$;

create trigger trg_certificate_template_guard before update or delete on certificate_template
    for each row execute function certificate_template_guard();

create function certificate_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'certificates are never deleted (CC-083)';
    end if;
    if old.status <> 'ISSUED' or new.status <> 'REVOKED'
       or (to_jsonb(new) - array['status', 'revoked_at', 'revocation_reason'])
          is distinct from (to_jsonb(old) - array['status', 'revoked_at', 'revocation_reason']) then
        raise exception 'a certificate only changes by being revoked, once (CC-083)';
    end if;
    return new;
end
$$;

create trigger trg_certificate_guard before update or delete on certificate
    for each row execute function certificate_guard();
