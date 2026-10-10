package gov.itms.marine.application;

import gov.itms.marine.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Marine craft registration, inspection, operator licence and renewal lifecycle (RE-030..RE-033). */
public class MarineService {
    public record Craft(UUID id,String hullId,String type,double lengthMetres,int engineCount,int capacity,String homePort,String owner,String status) { }
    public record Inspection(UUID id,String hullId,String purpose,String result,String workshop,String documentId,Instant inspectedAt) { }
    public record OperatorLicence(UUID id,String number,String trafficFile,String craftType,String status,Instant issuedAt,Instant expiresAt) { }
    private final Ports.MarineRepository repo;private final AuditRecorder audit;private final DomainEventRecorder events;private final Clock clock;
    public MarineService(Ports.MarineRepository repo,AuditRecorder audit,DomainEventRecorder events,Clock clock){this.repo=repo;this.audit=audit;this.events=events;this.clock=clock;}
    @Transactional
    public Craft register(String hull,String type,double length,int engines,int capacity,String port,String owner,String actor){
        if(hull==null||hull.isBlank()||type==null||type.isBlank()||length<=0||engines<1||engines>10||capacity<1||port==null||port.isBlank()||owner==null||owner.isBlank())throw DomainException.validation("marine craft data is invalid", "RE-030");
        UUID id=UUID.randomUUID();Craft craft=new Craft(id,hull,type,length,engines,capacity,port,owner,"ACTIVE");
        repo.insertCraft(craft,actor,clock.instant());
        audit.record("MARINE_CRAFT_REGISTERED","MarineCraft",id.toString(),null,Map.of("hullId",hull,"type",type,"status","ACTIVE"),null);
        events.record("itms.marine.craft-registered",id.toString(),Map.of("craftId",id.toString(),"hullId",hull,"owner",owner));
        return craft;
    }
    @Transactional
    public Inspection inspect(String hull,String purpose,String result,String workshop,String document,String actor){
        craft(hull);
        if(!java.util.List.of("REGISTRATION","RENEWAL","SAFETY").contains(purpose)||!java.util.List.of("PASS","FAIL").contains(result)||workshop==null||workshop.isBlank())throw DomainException.validation("marine inspection is invalid", "RE-031");
        UUID id=UUID.randomUUID();Instant at=clock.instant();Inspection inspection=new Inspection(id,hull,purpose,result,workshop,document,at);repo.insertInspection(inspection,actor);
        audit.record("MARINE_INSPECTION_RECORDED","MarineCraft",hull,null,Map.of("inspectionId",id.toString(),"result",result,"purpose",purpose),null);
        return inspection;
    }
    @Transactional
    public OperatorLicence issueLicence(String file,String type,int validityDays,String actor){
        if(file==null||file.isBlank()||type==null||type.isBlank()||validityDays<1||validityDays>3650)throw DomainException.validation("operator licence data is invalid", "RE-032");
        UUID id=UUID.randomUUID();Instant now=clock.instant(),expiry=now.plusSeconds(validityDays*86400L);String number="MOL-"+now.atZone(java.time.ZoneOffset.UTC).getYear()+"-"+id.toString().substring(0,8).toUpperCase(java.util.Locale.ROOT);
        OperatorLicence licence=new OperatorLicence(id,number,file,type,"ACTIVE",now,expiry);repo.insertLicence(licence,actor);
        audit.record("MARINE_OPERATOR_LICENCE_ISSUED","MarineOperatorLicence",id.toString(),null,Map.of("number",number,"craftType",type,"expiresAt",expiry.toString()),null);
        return licence;
    }
    @Transactional
    public Craft transfer(String hull,String owner,String actor){Craft old=craft(hull);repo.transfer(hull,owner,clock.instant());audit.record("MARINE_OWNERSHIP_TRANSFERRED","MarineCraft",old.id().toString(),Map.of("owner",old.owner()),Map.of("owner",owner),null);events.record("itms.marine.ownership-transferred",old.id().toString(),Map.of("hullId",hull,"owner",owner));return craft(hull);}
    @Transactional
    public Craft renew(String hull,int validityDays,String actor){Craft c=craft(hull);if(validityDays<1||validityDays>3650)throw DomainException.validation("validity is invalid", "RE-030");var last=repo.latestRenewalResult(hull).orElse(null);if(!"PASS".equals(last))throw DomainException.validation("a passing renewal inspection is required", "RE-030", "RE-031");Instant expiry=clock.instant().plusSeconds(validityDays*86400L);repo.renew(hull,expiry,clock.instant());audit.record("MARINE_REGISTRATION_RENEWED","MarineCraft",c.id().toString(),null,Map.of("expiresAt",expiry.toString()),null);return craft(hull);}
    private Craft craft(String hull){return repo.craft(hull).orElseThrow(()->DomainException.notFound("marine craft does not exist", "RE-030"));}
}
