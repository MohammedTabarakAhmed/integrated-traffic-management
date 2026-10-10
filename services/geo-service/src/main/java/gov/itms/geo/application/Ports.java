package gov.itms.geo.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Ports {
    private Ports() { }
    public interface GeoRepository {
        void insert(GeoService.Node node,String actor,Instant at);
        Optional<GeoService.Node> find(UUID id);
        List<GeoService.Node> children(UUID parentId,String type);
    }
}
