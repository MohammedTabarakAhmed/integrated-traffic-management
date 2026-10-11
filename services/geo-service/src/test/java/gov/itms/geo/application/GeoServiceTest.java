package gov.itms.geo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import gov.itms.geo.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("RE-001")
class GeoServiceTest {

    private final Map<UUID, GeoService.Node> nodes = new HashMap<>();
    private final GeoService geo = new GeoService(new Ports.GeoRepository() {
        @Override
        public void insert(GeoService.Node node, String actor, Instant at) {
            nodes.put(node.id(), node);
        }

        @Override
        public Optional<GeoService.Node> find(UUID id) {
            return Optional.ofNullable(nodes.get(id));
        }

        @Override
        public List<GeoService.Node> children(UUID parentId, String type) {
            return new ArrayList<>(nodes.values().stream().filter(n -> parentId.equals(n.parentId())).toList());
        }
    }, mock(AuditRecorder.class), mock(DomainEventRecorder.class),
            Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC));

    private GeoService.Node create(UUID parent, String type) {
        return geo.create(parent, type, "C1", "Name", "اسم", "NORTH", null, null, null, null, null, null, null, null,
                null, null, "u");
    }

    @Test
    void parentTypesFollowTheHierarchy() {
        GeoService.Node region = create(null, "REGION");
        GeoService.Node city = create(region.id(), "CITY");
        GeoService.Node area = create(city.id(), "AREA");
        for (String t : List.of("STREET", "INTERSECTION", "BRIDGE", "TUNNEL", "SERVICE_CENTRE")) {
            assertThat(create(area.id(), t).parentId()).isEqualTo(area.id());
        }
        assertThat(geo.get(city.id()).type()).isEqualTo("CITY");
        assertThat(geo.children(area.id(), null)).hasSize(5);
        assertThatThrownBy(() -> create(region.id(), "AREA")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> create(null, "CITY")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> create(UUID.randomUUID(), "CITY"))
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> geo.get(UUID.randomUUID())).isInstanceOf(DomainException.class);
    }

    @Test
    @Tag("RE-002")
    void fieldsAreChecked() {
        assertThatThrownBy(() -> create(null, "PLANET")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> geo.create(null, "REGION", " ", "n", "n", null, null, null, null, null, null, null,
                null, null, null, null, "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> geo.create(null, "REGION", "R", "n", "n", null, 10.0, null, null, null, null, null,
                null, null, null, null, "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> geo.create(null, "REGION", "R", "n", "n", null, 91.0, 0.0, null, null, null, null,
                null, null, null, null, "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> geo.create(null, "REGION", "R", "n", "n", null, null, null, 0.0, null, null, null,
                null, null, null, null, "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> geo.create(null, "REGION", "R", "n", "n", null, null, null, null, -1.0, null, null,
                null, null, null, null, "u")).isInstanceOf(DomainException.class);
        GeoService.Node region = create(null, "REGION");
        GeoService.Node street = create(create(create(region.id(), "CITY").id(), "AREA").id(), "STREET");
        assertThatThrownBy(() -> geo.create(street.id(), "ROAD_SEGMENT", "S", "n", "n", null, null, null, null, null,
                null, null, "ARTERIAL", 2, "SIDEWAYS", "ROADS", "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> geo.create(street.id(), "ROAD_SEGMENT", "S", "n", "n", null, null, null, null, null,
                null, null, "ARTERIAL", 33, "FORWARD", "ROADS", "u")).isInstanceOf(DomainException.class);
        GeoService.Node seg = geo.create(street.id(), "ROAD_SEGMENT", "S", "n", "n", null, 1.0, 2.0, 60.0, null,
                null, null, "ARTERIAL", 2, "BIDIRECTIONAL", "ROADS", "u");
        assertThat(seg.laneCount()).isEqualTo(2);
    }
}
