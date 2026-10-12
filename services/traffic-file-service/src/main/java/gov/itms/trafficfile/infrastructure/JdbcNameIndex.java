package gov.itms.trafficfile.infrastructure;

import gov.itms.trafficfile.application.NameIndex;
import gov.itms.trafficfile.domain.NameQuery;
import gov.itms.trafficfile.domain.TrafficFile;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Name search over the generated name columns of {@code traffic_file} (V6). The query is folded and keyed by the same
 * database functions that keyed the files, so both sides follow one set of rules. A file matches when, within one of
 * its names, every query word is found as text or, if its phonetic key is long enough, by that key; EXACT when the
 * folded query is the whole name, PARTIAL when every word was found as text, otherwise PHONETIC.
 */
class JdbcNameIndex implements NameIndex {

    private static final String SQL = """
            with q as (
                select itms_name_fold(:q) as folded,
                       array(select w from regexp_split_to_table(itms_name_fold(:q), ' ') with ordinality as s(w, n)
                             where w <> '' order by n) as words
            ), qk as (
                select folded, words,
                       array(select itms_name_word_key(w) from unnest(words) with ordinality as s(w, n) order by n)
                           as keys
                from q
            )
            select f.file_number, f.kind, f.status, f.region_opened, m.rank,
                   coalesce(f.company_name_nl, nullif(concat_ws(' ', f.given_nl, f.family_nl), '')) as name_nl,
                   coalesce(f.company_name_en, nullif(concat_ws(' ', f.given_en, f.family_en), '')) as name_en
            from traffic_file f
            cross join qk
            cross join lateral (
                select min(case
                               when s.t = qk.folded then 1
                               when not exists (select 1 from unnest(qk.words) as w(w) where position(w.w in s.t) = 0)
                                   then 2
                               else 3
                           end) as rank
                from unnest(string_to_array(f.name_text, ' | '), string_to_array(f.name_key, ' | ')) as s(t, k)
                where not exists (
                    select 1 from unnest(qk.words, qk.keys) as x(w, wk)
                    where position(x.w in s.t) = 0 and (length(x.wk) < :minKey or position(x.wk in s.k) = 0))
            ) m
            where cardinality(qk.words) > 0 and m.rank is not null and f.status <> 'MERGED'
              and (cast(:kind as text) is null or f.kind = cast(:kind as text))
            order by m.rank, f.file_number
            limit :limit""";

    private final JdbcClient jdbc;

    JdbcNameIndex(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Hit> find(NameQuery query, int minKeyLength) {
        return jdbc.sql(SQL)
                .param("q", query.text())
                .param("minKey", minKeyLength)
                .param("kind", query.kind() == null ? null : query.kind().name())
                .param("limit", query.limit())
                .query((rs, row) -> new Hit(rs.getString("file_number"),
                        TrafficFile.Kind.valueOf(rs.getString("kind")),
                        TrafficFile.Status.valueOf(rs.getString("status")),
                        rs.getString("region_opened"),
                        NameQuery.Match.values()[rs.getInt("rank") - 1],
                        rs.getString("name_nl"),
                        rs.getString("name_en")))
                .list();
    }
}
