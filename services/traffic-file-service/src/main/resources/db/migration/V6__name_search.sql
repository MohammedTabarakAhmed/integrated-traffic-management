-- traffic-file-service schema, part 6: name search (CC-101; ADR-063). People and companies are found by name: partial
-- matches in either language, transliteration between Arabic and Latin script, and phonetic matching of spelling
-- variants. The keys are generated columns, so every writer (API, register sync, merges, future migration loads) keeps
-- them current and existing files are indexed when this migration runs. The query side calls the same functions, so
-- the matching rules live only here.

-- Folding (both scripts): diacritics and Arabic short vowels, hamza seats, tatweel and dagger alif removed; alef forms,
-- alef maqsura, teh marbuta and Persian kaf/yeh unified; lower case; everything but letters and digits becomes one
-- space. "Al-Mansoori", "al mansoori" and "AL MANSOORI" fold alike; "أحمد" and "احمد" fold alike.
create function itms_name_fold(value text) returns text language sql immutable parallel safe strict as $$
    select btrim(regexp_replace(
        translate(
            regexp_replace(lower(normalize(value, NFKD)), '[̀-ًͯ-ٰٟـء]', '', 'g'),
            'ىکیٱ', 'يكيا'),
        '[^a-z0-9ء-غف-ية]+', ' ', 'g'))
$$;

-- Phonetic key of one folded word, the same for both scripts (transliteration): the Arabic definite article and Latin
-- "al"/"el" dropped; Latin digraphs and Arabic letters mapped onto one consonant alphabet (sh/ch/ش -> x, kh/خ -> k,
-- gh/غ -> g, th/ث -> t, dh/ذ -> d, q/ق/c -> k, ...); vowels, ain, and w/y after the first letter dropped (Arabic
-- writes no short vowels and uses و/ي as long vowels); doubled letters collapsed; a final h dropped (teh marbuta).
-- So "Mohammed", "Muhammad", "Mohamad" and "محمد" all give "mhmd"; "Abdul Rahman" and "عبدالرحمن" give "bdlrhmn".
create function itms_name_word_key(word text) returns text language sql immutable parallel safe strict as $$
    with stripped as (
        select case
                   when word in ('al', 'el') then ''
                   when length(word) > 4 and (word like 'ال%' or word like 'al%' or word like 'el%') then substr(word, 3)
                   else word
               end as w
    ), latin as (
        select translate(
                   replace(replace(replace(replace(replace(replace(replace(w,
                       'sh', 'x'), 'ch', 'x'), 'kh', 'k'), 'gh', 'g'), 'th', 't'), 'dh', 'd'), 'ph', 'f'),
                   'qcبتثجحخدذرزسشصضطظغفقكلمنهةويvاع',
                   'kkbttjhkddrzsxsdtzgfkklmnhhwyf') as w
        from stripped
    ), skeleton as (
        select case when w = '' then '' else
                   regexp_replace(left(w, 1), '[aeiou]', '') || regexp_replace(substr(w, 2), '[aeiouwy]', '', 'g')
               end as w
        from latin
    ), collapsed as (
        select regexp_replace(w, '(.)\1+', '\1', 'g') as w from skeleton
    )
    select case when length(w) > 1 then regexp_replace(w, 'h$', '') else w end from collapsed
$$;

-- Phonetic key of a whole name: the words' keys run together, so word breaks do not matter ("Abdul Rahman").
create function itms_name_key(value text) returns text language sql immutable parallel safe strict as $$
    select coalesce(string_agg(itms_name_word_key(w), '' order by n), '')
    from regexp_split_to_table(itms_name_fold(value), ' ') with ordinality as t(w, n)
    where w <> ''
$$;

-- A person's given and family name as one name (null when both are absent).
create function itms_name_full(given text, family text) returns text language sql immutable parallel safe as $$
    select case when given is null and family is null then null
                else btrim(coalesce(given, '') || ' ' || coalesce(family, '')) end
$$;

-- The names of a file joined by " | ", absent ones left out. concat_ws itself is only STABLE (it accepts any type);
-- over text it does not depend on settings, so this wrapper may be IMMUTABLE and used in generated columns.
create function itms_name_join(a text, b text, c text, d text) returns text language sql immutable parallel safe as $$
    select concat_ws(' | ', a, b, c, d)
$$;

-- Every name of the file, folded (name_text) and as phonetic keys (name_key), separated by " | " so that no match
-- crosses from one name into another: given + family name in each language, and a company's names. Both columns
-- leave out the same absent names, so their parts line up.
alter table traffic_file
    add column name_text text generated always as (
        itms_name_join(
            itms_name_fold(itms_name_full(given_nl, family_nl)),
            itms_name_fold(itms_name_full(given_en, family_en)),
            itms_name_fold(company_name_nl),
            itms_name_fold(company_name_en))) stored,
    add column name_key text generated always as (
        itms_name_join(
            itms_name_key(itms_name_full(given_nl, family_nl)),
            itms_name_key(itms_name_full(given_en, family_en)),
            itms_name_key(company_name_nl),
            itms_name_key(company_name_en))) stored;

-- Substring matching at volume (CC-103) wants trigram indexes. pg_trgm is a trusted extension; where this database
-- may not create it the indexes are left to the DBA (ADR-063) and the search still works, by scanning.
do $$
begin
    begin
        create extension if not exists pg_trgm;
    exception when insufficient_privilege or undefined_file then
        raise notice 'pg_trgm not available: name search runs without trigram indexes (ADR-063)';
    end;
    if exists (select 1 from pg_extension where extname = 'pg_trgm') then
        execute 'create index ix_tf_name_text on traffic_file using gin (name_text gin_trgm_ops)';
        execute 'create index ix_tf_name_key on traffic_file using gin (name_key gin_trgm_ops)';
    end if;
end
$$;
