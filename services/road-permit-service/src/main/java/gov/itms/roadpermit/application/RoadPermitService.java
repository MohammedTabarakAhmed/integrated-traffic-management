package gov.itms.roadpermit.application;

import gov.itms.roadpermit.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Parking and road-use permit application workflow (RE-010..RE-023). */
public class RoadPermitService {
    public record Application(UUID id,String number,String type,String applicant,String eligibilityReference,
                              List<String> vehicles,List<String> routeSegments,String region,String status,
                              Instant startsAt,Instant endsAt,Double loadTonnes,Double widthMetres,
                              Double heightMetres,Double lengthMetres,int vehicleLimit) {
        public Application {
            vehicles = vehicles == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(vehicles));
            routeSegments = routeSegments == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(routeSegments));
        }
    }
    public interface Repository {
        void insert(Application application,String[] authorities,String actor,Instant at);
        Application get(UUID id);
        void approve(UUID id,String authority,String approvedBy,String reason,Instant at);
        List<String> pendingAuthorities(UUID id);
        void transition(UUID id,String status,String actor,String reason,Instant at);
    }
    private final Repository repo;private final AuditRecorder audit;private final DomainEventRecorder events;private final Clock clock;
    public RoadPermitService(Repository repo,AuditRecorder audit,DomainEventRecorder events,Clock clock){this.repo=repo;this.audit=audit;this.events=events;this.clock=clock;}
    @Transactional
    public Application apply(String type,String applicant,String eligibilityReference,List<String> vehicles,
                             List<String> route,String region,Instant starts,Instant ends,Double load,Double width,
                             Double height,Double length,int vehicleLimit,List<String> authorities,String actor){
        if(!List.of("PARKING","OVERSIZE","OVERWEIGHT","HEAVY_RESTRICTED","ROAD_CLOSURE").contains(type)
                ||applicant==null||applicant.isBlank()||region==null||region.isBlank()||starts==null||ends==null||!starts.isBefore(ends)
                ||vehicles==null||vehicles.isEmpty()||vehicles.size()>20||vehicleLimit<1||vehicles.size()>vehicleLimit
                ||(type.equals("PARKING")&&(eligibilityReference==null||eligibilityReference.isBlank()))
                ||(!type.equals("PARKING")&&(route==null||route.isEmpty()))
                ||(load!=null&&load<=0)||(width!=null&&width<=0)||(height!=null&&height<=0)||(length!=null&&length<=0)
                ||authorities==null||authorities.isEmpty()) throw DomainException.validation("road permit application is invalid", "RE-010", "RE-011", "RE-020", "RE-021", "RE-022", "RE-023");
        UUID id=UUID.randomUUID();String number="RPU-"+starts.atZone(java.time.ZoneOffset.UTC).getYear()+"-"+id.toString().substring(0,8).toUpperCase(java.util.Locale.ROOT);
        Application a=new Application(id,number,type,applicant,eligibilityReference,List.copyOf(vehicles),route==null?List.of():List.copyOf(route),region,"APPLIED",starts,ends,load,width,height,length,vehicleLimit);
        repo.insert(a,authorities.toArray(String[]::new),actor,clock.instant());
        audit.record("ROAD_PERMIT_APPLIED","RoadPermit",id.toString(),null,Map.of("number",number,"type",type,"status","APPLIED"),null);
        return a;
    }
    @Transactional
    public Application approve(UUID id,String authority,String reason,String actor){
        Application a=repo.get(id);if(!"APPLIED".equals(a.status())||authority==null||authority.isBlank())throw DomainException.validation("permit is not awaiting authority approval", "RE-020", "RE-021");
        repo.approve(id,authority,actor,reason,clock.instant());
        List<String> pending=repo.pendingAuthorities(id);
        if(pending.isEmpty()){
            repo.transition(id,"APPROVED",actor,reason,clock.instant());
            audit.record("ROAD_PERMIT_APPROVED","RoadPermit",id.toString(),Map.of("status","APPLIED"),Map.of("status","APPROVED"),reason);
            events.record("itms.road-permit.approved",id.toString(),Map.of("permitId",id.toString(),"number",a.number(),"region",a.region(),"vehicles",a.vehicles()));
        }
        return repo.get(id);
    }
    @Transactional
    public Application transition(UUID id,String action,String reason,String actor){
        Application a=repo.get(id);String status=switch(action){case "issue"->"ISSUED";case "cancel"->"CANCELLED";case "expire"->"EXPIRED";default->throw DomainException.validation("unsupported permit action", "RE-020", "RE-021");};
        if(!(("issue".equals(action)&&"APPROVED".equals(a.status()))||(!"issue".equals(action)&&"ISSUED".equals(a.status()))))throw DomainException.validation("road permit transition is invalid", "RE-020", "RE-021");
        repo.transition(id,status,actor,reason,clock.instant());audit.record("ROAD_PERMIT_"+status,"RoadPermit",id.toString(),Map.of("status",a.status()),Map.of("status",status),reason);
        events.record("itms.road-permit.status-changed",id.toString(),Map.of("permitId",id.toString(),"status",status,"vehicles",a.vehicles()));return repo.get(id);
    }
}
