-- traffic-file-service schema, part 5: probable duplicates and merges (DL-009; ADR-044).

-- Candidates for a person's probable duplicates: active individuals with the same birth date and nationality.
create index ix_tf_duplicate_candidates on traffic_file (birth_date, nationality)
    where kind = 'INDIVIDUAL' and status = 'ACTIVE';

-- One row per merge: which file was merged into which, by whom, why. Written once; never changed or deleted.
create table file_merge (
    id                     uuid          primary key,
    merged_file_id         uuid          not null unique references traffic_file (id),
    merged_file_number     varchar(20)   not null,
    surviving_file_id      uuid          not null references traffic_file (id),
    surviving_file_number  varchar(20)   not null,
    reason                 varchar(500)  not null,
    merged_at              timestamptz   not null,
    merged_by              varchar(100)  not null,
    merged_channel         varchar(20)   not null,
    constraint ck_merge_files check (merged_file_id <> surviving_file_id),
    constraint ck_merge_reason check (char_length(btrim(reason)) between 3 and 500)
);

create function file_merge_guard() returns trigger language plpgsql as $$
begin
    raise exception 'merge records are append-only (DL-009, CC-122)';
end
$$;

create trigger trg_file_merge_guard before update or delete on file_merge
    for each row execute function file_merge_guard();

-- A merged file stays merged: the status MERGED and its surviving file never change again.
create function traffic_file_merge_guard() returns trigger language plpgsql as $$
begin
    if old.status = 'MERGED' then
        raise exception 'a merged traffic file never changes (DL-009)';
    end if;
    return new;
end
$$;

create trigger trg_traffic_file_merge_guard before update on traffic_file
    for each row execute function traffic_file_merge_guard();
