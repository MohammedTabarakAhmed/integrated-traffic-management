-- Database ownership for rotated logins (SEC-004, ADR-039). Run by tools/dev/up.sh as the database's bootstrap
-- superuser on every start; idempotent. In real environments the DBA applies the same once per service database.
--
-- itms_owner: a NOLOGIN, non-superuser role that owns the service's schema and objects. The secret store creates a
-- short-lived login per service that is a member of itms_owner; the pool runs SET ROLE itms_owner on connect, so
-- Flyway migrations create objects owned by itms_owner and no object ever belongs to a login that will expire.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'itms_owner') THEN
        CREATE ROLE itms_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    EXECUTE format('GRANT CONNECT, CREATE, TEMPORARY ON DATABASE %I TO itms_owner', current_database());
END
$$;

ALTER SCHEMA public OWNER TO itms_owner;

-- Objects created before this script (local databases from earlier slices) move to itms_owner. Sequences owned by a
-- column follow their table.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT format('ALTER %s %s OWNER TO itms_owner',
                      CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                                     WHEN 'S' THEN 'SEQUENCE' WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
                      c.oid::regclass) AS stmt
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
          AND pg_get_userbyid(c.relowner) <> 'itms_owner'
          AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_depend d
                                               WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                                                 AND d.deptype IN ('a', 'i')))
    LOOP
        EXECUTE r.stmt;
    END LOOP;
    FOR r IN
        SELECT format('ALTER %s %s OWNER TO itms_owner',
                      CASE p.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END,
                      p.oid::regprocedure) AS stmt
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND pg_get_userbyid(p.proowner) <> 'itms_owner'
    LOOP
        EXECUTE r.stmt;
    END LOOP;
    FOR r IN
        SELECT format('ALTER TYPE %s OWNER TO itms_owner', t.oid::regtype) AS stmt
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE n.nspname = 'public' AND t.typtype IN ('e', 'd', 'r', 'c')
          AND (t.typrelid = 0 OR (SELECT c.relkind FROM pg_class c WHERE c.oid = t.typrelid) = 'c')
          AND pg_get_userbyid(t.typowner) <> 'itms_owner'
    LOOP
        EXECUTE r.stmt;
    END LOOP;
END
$$;
