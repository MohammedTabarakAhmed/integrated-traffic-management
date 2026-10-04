package gov.itms.refdata.infrastructure;

import gov.itms.refdata.application.ReferenceListRepository;
import gov.itms.refdata.domain.BilingualName;
import gov.itms.refdata.domain.ReferenceList;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcReferenceListRepository implements ReferenceListRepository {

    private static final RowMapper<ReferenceList> MAPPER = (rs, n) -> new ReferenceList(rs.getString("code"),
            new BilingualName(rs.getString("name_nl"), rs.getString("name_en")), rs.getString("parent_list_code"));

    private final JdbcClient jdbc;

    JdbcReferenceListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReferenceList> find(String code) {
        return jdbc.sql("select code, name_nl, name_en, parent_list_code from reference_list where code = :code")
                .param("code", code).query(MAPPER).optional();
    }

    @Override
    public List<ReferenceList> findAll() {
        return jdbc.sql("select code, name_nl, name_en, parent_list_code from reference_list order by code")
                .query(MAPPER).list();
    }

    @Override
    public void insert(ReferenceList list, String actorId, UUID changeId, Instant at) {
        jdbc.sql("""
                        insert into reference_list (code, name_nl, name_en, parent_list_code, created_at, created_by,
                                                    created_by_change)
                        values (:code, :nl, :en, :parent, :at, :by, :change)""")
                .param("code", list.code())
                .param("nl", list.name().nl())
                .param("en", list.name().en())
                .param("parent", list.parentListCode())
                .param("at", Timestamp.from(at))
                .param("by", actorId)
                .param("change", changeId)
                .update();
    }
}
