package gov.itms.trafficfile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.trafficfile.domain.Actor;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.NameQuery;
import gov.itms.trafficfile.domain.TrafficFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link NameSearchService} with an in-memory index (CC-101, CC-102). */
@Tag("CC-101")
class NameSearchServiceTest {

    private final List<NameQuery> asked = new ArrayList<>();
    private final List<Object> audited = new ArrayList<>();
    private final NameIndex index = (query, minKeyLength) -> {
        asked.add(query);
        return List.of(new NameIndex.Hit("10000000017", TrafficFile.Kind.INDIVIDUAL, TrafficFile.Status.ACTIVE, "DXB",
                NameQuery.Match.PHONETIC, "محمد المنصوري", "Mohammed Al-Mansoori"));
    };
    private final NameSearchService service = new NameSearchService(index, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audited.add(action + " " + newValue);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            audited.add(action + " " + newValue);
        }
    }, 20, 3);

    private static Caller caller(String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor("u-1", "officer"), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    @Test
    @Tag("CC-102")
    @DisplayName("a search is capped at the configured most and audited with the files shown, not the name")
    void searchIsCappedAndAudited() {
        List<NameIndex.Hit> hits = service.search(new NameQuery("Muhammad", null, 100),
                caller(TrafficFileService.READ));
        assertThat(hits).extracting(NameIndex.Hit::fileNumber).containsExactly("10000000017");
        assertThat(asked).singleElement().extracting(NameQuery::limit).isEqualTo(20);
        assertThat(audited).singleElement().asString().startsWith("TRAFFIC_FILE_NAME_SEARCHED")
                .contains("10000000017").doesNotContain("Muhammad");
    }

    @Test
    @Tag("CC-001")
    @DisplayName("without trafficfile.read nothing is searched")
    void needsRead() {
        assertThatThrownBy(() -> service.search(new NameQuery("Muhammad", null, 5), caller("vehicle.read")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("SCOPE_EXCEEDED"));
        assertThat(asked).isEmpty();
        assertThat(audited).isEmpty();
    }
}
