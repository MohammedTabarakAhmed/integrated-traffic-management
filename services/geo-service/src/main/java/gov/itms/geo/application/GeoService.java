package gov.itms.geo.application;

import gov.itms.geo.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** National geographic hierarchy and road network (CC-111, RE-001..RE-005). */
public class GeoService {
    public record Node(UUID id,UUID parentId,String type,String code,String nameEn,String nameAr,String region,
                       Double latitude,Double longitude,Double speedLimitKmh,Double bridgeLoadTonnes,
                       String restrictions,String openingHours,String roadType,Integer laneCount,String direction,
                       String owningAuthority) { }
    private static final List<String> TYPES=List.of("REGION","CITY","AREA","STREET","INTERSECTION","BRIDGE","TUNNEL","ROAD_SEGMENT","SERVICE_CENTRE");
    private final Ports.GeoRepository repo; private final AuditRecorder audit; private final DomainEventRecorder events; private final Clock clock;
    public GeoService(Ports.GeoRepository repo,AuditRecorder audit,DomainEventRecorder events,Clock clock) {
        this.repo=repo;this.audit=audit;this.events=events;this.clock=clock;
    }
    @Transactional
    public Node create(UUID parent,String type,String code,String nameEn,String nameAr,String region,Double lat,Double lon,
                       Double speed,Double load,String restrictions,String hours,String roadType,Integer laneCount,String direction,String owningAuthority,String actor) {
        if(!TYPES.contains(type)||code==null||code.isBlank()||nameEn==null||nameEn.isBlank()||nameAr==null||nameAr.isBlank()
                ||((lat==null)!=(lon==null))||(lat!=null&&(lat < -90||lat>90||lon < -180||lon>180))
                ||(speed!=null&&speed<=0)||(load!=null&&load<=0)
                ||("ROAD_SEGMENT".equals(type)&&(roadType==null||roadType.isBlank()||laneCount==null||laneCount<1||laneCount>32
                    ||!List.of("BIDIRECTIONAL","FORWARD","REVERSE").contains(direction)
                    ||owningAuthority==null||owningAuthority.isBlank()))) throw DomainException.validation("geographic node fields are invalid", "RE-001", "RE-002", "RE-005");
        if(parent!=null) {
            Node p=repo.find(parent).orElseThrow(()->DomainException.notFound("parent geographic node does not exist", "RE-001"));
            if(!parentType(type).equals(p.type())) throw DomainException.validation("geographic parent type is invalid", "RE-001");
        } else if(!"REGION".equals(type)) throw DomainException.validation("only a region has no parent", "RE-001");
        Node node=new Node(UUID.randomUUID(),parent,type,code,nameEn,nameAr,region,lat,lon,speed,load,restrictions,hours,
                roadType,laneCount,direction,owningAuthority);
        repo.insert(node,actor,clock.instant());
        audit.record("GEO_NODE_CREATED","GeoNode",node.id().toString(),null,Map.of("type",type,"code",code),null);
        events.record("itms.geo.node-created",node.id().toString(),Map.of("nodeId",node.id().toString(),"type",type,"code",code));
        return node;
    }
    @Transactional(readOnly=true)
    public Node get(UUID id) { return repo.find(id).orElseThrow(()->DomainException.notFound("geographic node does not exist", "RE-001")); }
    @Transactional(readOnly=true)
    public List<Node> children(UUID parent,String type) { return repo.children(parent,type); }
    private static String parentType(String type) {
        return switch(type) { case "CITY"->"REGION";case "AREA"->"CITY";case "STREET","INTERSECTION","BRIDGE","TUNNEL","SERVICE_CENTRE"->"AREA";case "ROAD_SEGMENT"->"STREET";default->""; };
    }
}
