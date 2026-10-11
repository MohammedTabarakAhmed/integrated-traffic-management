package gov.itms.rental.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Ports {
    private Ports() { }
    public interface RentalRepository {
        void insertCompany(UUID id, String file, String licence, String region, String actor, Instant at);
        Optional<RentalService.Company> companyForUpdate(String companyFile);
        Optional<String> companyRegion(String companyFile);
        RentalService.Branch insertBranch(String companyFile,String code,String name,String region,String actor,Instant at);
        boolean activeBranch(String companyFile,String code);
        RentalService.Company setCompanyStatus(String companyFile,String status,String reason,String actor,Instant at);
        void insertContract(UUID id, String companyFile, String plate, String renterFile, String passport,
                            String foreignLicence, List<String> additionalDrivers, Instant startsAt, Instant endsAt,
                            String pickup, String returns, String type, long bondMinor, String actor, Instant at);
        Optional<RentalService.Contract> contract(UUID id);
        /** The non-cancelled contract whose period covers the instant for the plate, latest start first (TR-032). */
        Optional<RentalService.Coverage> coverage(String plate, Instant at);
        void insertInspection(RentalService.Inspection inspection);
        List<RentalService.Inspection> inspections(UUID contractId);
    }
}
