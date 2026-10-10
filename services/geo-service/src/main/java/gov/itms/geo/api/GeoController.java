package gov.itms.geo.api;

import gov.itms.geo.application.GeoService;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GeoController {
    private final GeoService geo; private final CurrentActorProvider actors;
    GeoController(GeoService geo,CurrentActorProvider actors){this.geo=geo;this.actors=actors;}
    record NodeRequest(UUID parentId,@NotBlank String type,@NotBlank @Size(max=40) String code,@NotBlank @Size(max=200) String nameEn,
                       @NotBlank @Size(max=200) String nameAr,String region,Double latitude,Double longitude,
                       Double speedLimitKmh,Double bridgeLoadTonnes,@Size(max=1000) String restrictions,
                       @Size(max=500) String openingHours,@Size(max=24) String roadType,@jakarta.validation.constraints.Min(1) Integer laneCount,
                       @Size(max=12) String direction,@Size(max=80) String owningAuthority) { }
    record Collection<T>(List<T> items) { }
    @PostMapping("/v1/geographic-nodes") @PreAuthorize("hasAuthority('geo.node.manage')")
    ResponseEntity<GeoService.Node> create(@Valid @RequestBody NodeRequest b){
        GeoService.Node n=geo.create(b.parentId(),b.type(),b.code(),b.nameEn(),b.nameAr(),b.region(),b.latitude(),b.longitude(),b.speedLimitKmh(),b.bridgeLoadTonnes(),b.restrictions(),b.openingHours(),b.roadType(),b.laneCount(),b.direction(),b.owningAuthority(),actors.current().userId());
        return ResponseEntity.created(URI.create("/v1/geographic-nodes/"+n.id())).body(n);
    }
    @GetMapping("/v1/geographic-nodes/{id}") @PreAuthorize("hasAuthority('geo.node.read')")
    GeoService.Node get(@PathVariable UUID id){return geo.get(id);}
    @GetMapping("/v1/geographic-nodes") @PreAuthorize("hasAuthority('geo.node.read')")
    Collection<GeoService.Node> children(@RequestParam UUID parentId,@RequestParam(required=false) String type){return new Collection<>(geo.children(parentId,type));}
}
