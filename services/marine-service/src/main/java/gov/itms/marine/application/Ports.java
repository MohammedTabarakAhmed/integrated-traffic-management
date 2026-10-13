package gov.itms.marine.application;

import java.time.Instant;
import java.util.Optional;

public final class Ports {
    private Ports() { }
    public interface MarineRepository {
        void insertCraft(MarineService.Craft craft,String actor,Instant at);
        Optional<MarineService.Craft> craft(String hullId);
        void insertInspection(MarineService.Inspection inspection,String actor);
        Optional<String> latestRenewalResult(String hullId);
        void insertLicence(MarineService.OperatorLicence licence,String actor);
        void transfer(String hullId,String owner,Instant at);
        void renew(String hullId,Instant expiresAt,Instant at);
    }
}
