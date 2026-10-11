package gov.itms.rental.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.rental.domain.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Company registration and time-bounded rental contracts (TR-020..TR-033). */
public class RentalService {
    public record Company(UUID id, String trafficFileNumber, String commercialLicence, String region, String status,
                         Instant createdAt) { }
    public record Branch(String companyFile,String code,String name,String region,boolean active,Instant createdAt) { }
    public record Contract(UUID id, String companyFile, String region, String vehiclePlate, String renterFile,
                           String visitorPassport, String foreignLicence, List<String> additionalDrivers,
                           Instant startsAt, Instant endsAt, String pickupBranch, String returnBranch,
                           String type, long bondMinor, String status, Instant createdAt) {
        public Contract {
            additionalDrivers = additionalDrivers == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(additionalDrivers));
        }
    }
    public record Inspection(UUID id, UUID contractId, String type, Instant inspectedAt, long odometerKm,
                             int fuelPercent, List<String> damageNotes, List<String> evidenceIds, String notes,
                             String inspectedBy) {
        public Inspection {
            damageNotes = List.copyOf(damageNotes);
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
    /** The contract covering a plate at an instant (TR-032): renterFile is null for a visitor without a file. */
    public record Coverage(UUID contractId, String companyFile, String renterFile, boolean visitor) { }
    private final Ports.RentalRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public RentalService(Ports.RentalRepository repo, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Company registerCompany(String file, String licence, String region, String actor) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            repo.insertCompany(id, file, licence, region, actor, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DomainException(DomainException.Kind.CONFLICT, "RENTAL_COMPANY_EXISTS",
                    "a rental company already uses this file or commercial licence", List.of("TR-020"));
        }
        audit.record("RENTAL_COMPANY_REGISTERED", "RentalCompany", id.toString(), null,
                Map.of("file", file, "region", region), null);
        return new Company(id, file, licence, region, "ACTIVE", now);
    }

    @Transactional
    public Branch addBranch(String companyFile,String code,String name,String region,String actor) {
        Company company=repo.companyForUpdate(companyFile).orElseThrow(()->new DomainException(
                DomainException.Kind.NOT_FOUND,"RENTAL_COMPANY_NOT_FOUND","rental company is not registered",List.of("TR-020")));
        if(!"ACTIVE".equals(company.status())||code==null||!code.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$")
                ||name==null||name.isBlank()||name.length()>120||region==null||!region.equals(company.region()))
            throw new DomainException(DomainException.Kind.VALIDATION,"RENTAL_BRANCH_INVALID",
                    "branch details must be valid and use the company's region",List.of("TR-020"));
        Branch branch=repo.insertBranch(companyFile,code,name.strip(),region,actor,clock.instant());
        audit.record("RENTAL_BRANCH_REGISTERED","RentalBranch",companyFile+":"+code,null,
                Map.of("companyFile",companyFile,"region",region,"active",true),null);
        events.record("itms.rental.branch-registered",companyFile+":"+code,Map.of("companyFile",companyFile,"branchCode",code,"region",region));
        return branch;
    }

    @Transactional
    public Company suspendCompany(String companyFile,String reason,String actor) {
        return changeCompanyStatus(companyFile,"SUSPENDED",reason,actor);
    }

    @Transactional
    public Company reinstateCompany(String companyFile,String reason,String actor) {
        return changeCompanyStatus(companyFile,"ACTIVE",reason,actor);
    }

    private Company changeCompanyStatus(String file,String status,String reason,String actor) {
        if(reason==null||reason.isBlank()||reason.length()>500)
            throw new DomainException(DomainException.Kind.VALIDATION,"RENTAL_STATUS_REASON_REQUIRED",
                    "a reason is required",List.of("TR-021"));
        Company before=repo.companyForUpdate(file).orElseThrow(()->new DomainException(
                DomainException.Kind.NOT_FOUND,"RENTAL_COMPANY_NOT_FOUND","rental company is not registered",List.of("TR-021")));
        if(before.status().equals(status))
            throw new DomainException(DomainException.Kind.CONFLICT,"RENTAL_COMPANY_STATUS_UNCHANGED",
                    "company already has the requested status",List.of("TR-021"));
        Company after=repo.setCompanyStatus(file,status,reason.strip(),actor,clock.instant());
        audit.record("RENTAL_COMPANY_"+status,"RentalCompany",file,Map.of("status",before.status()),
                Map.of("status",status),reason);
        events.record("itms.rental.company-status-changed",file,Map.of("companyFile",file,"status",status,"reason",reason));
        return after;
    }

    @Transactional
    public Contract createContract(String companyFile, String vehiclePlate, String renterFile, String passport,
                                   String foreignLicence, List<String> additionalDrivers, Instant startsAt,
                                   Instant endsAt, String pickup, String returns, String type, long bondMinor,
                                   String actor) {
        Company company = repo.companyForUpdate(companyFile)
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "RENTAL_COMPANY_NOT_FOUND",
                        "rental company is not registered", List.of("TR-021")));
        if (!"ACTIVE".equals(company.status())) {
            throw new DomainException(DomainException.Kind.CONFLICT, "RENTAL_COMPANY_SUSPENDED",
                    "rental company is suspended", List.of("TR-021"));
        }
        boolean localIdentityValid = renterFile != null && !renterFile.isBlank();
        boolean visitorIdentityValid = passport != null && !passport.isBlank() && foreignLicence != null
                && !foreignLicence.isBlank();
        if (localIdentityValid == visitorIdentityValid || (passport == null) != (foreignLicence == null)
                || (renterFile != null && renterFile.isBlank())
                || (passport != null && passport.isBlank()) || (foreignLicence != null && foreignLicence.isBlank())
                || startsAt == null || endsAt == null || !startsAt.isBefore(endsAt) || bondMinor < 0) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RENTAL_CONTRACT_INVALID",
                    "contract renter, dates and bond are invalid", List.of("TR-030", "TR-033"));
        }
        if (pickup==null||returns==null||!repo.activeBranch(companyFile,pickup)||!repo.activeBranch(companyFile,returns)) {
            throw new DomainException(DomainException.Kind.VALIDATION,"RENTAL_BRANCH_REQUIRED",
                    "pickup and return branches must be active branches of the registered company",List.of("TR-020","TR-030"));
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        List<String> drivers = additionalDrivers == null ? List.of() : List.copyOf(additionalDrivers);
        repo.insertContract(id, companyFile, vehiclePlate, renterFile, passport, foreignLicence, drivers, startsAt,
                endsAt, pickup, returns, type, bondMinor, actor, now);
        audit.record("RENTAL_CONTRACT_CREATED", "RentalContract", id.toString(), null,
                Map.of("companyFile", companyFile, "vehiclePlate", vehiclePlate, "status", "ACTIVE"), null);
        events.record("itms.rental.contract-created", id.toString(), Map.of("contractId", id.toString(),
                "companyFile", companyFile, "vehiclePlate", vehiclePlate, "renterFile", renterFile == null ? "" : renterFile,
                "startsAt", startsAt.toString(), "endsAt", endsAt.toString()));
        return new Contract(id, companyFile, company.region(), vehiclePlate, renterFile, passport, foreignLicence,
                drivers, startsAt, endsAt,
                pickup, returns, type, bondMinor, "ACTIVE", now);
    }

    @Transactional(readOnly = true)
    public Contract contract(UUID id) {
        return repo.contract(id).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "RENTAL_CONTRACT_NOT_FOUND", "rental contract does not exist", List.of("TR-030")));
    }

    @Transactional
    public Inspection recordInspection(UUID contractId, String type, Instant inspectedAt, long odometerKm,
                                       int fuelPercent, List<String> damageNotes, List<String> evidenceIds,
                                       String notes, String actor) {
        Contract contract = contract(contractId);
        if (!"ACTIVE".equals(contract.status()) || !List.of("PICKUP", "RETURN").contains(type)
                || inspectedAt == null || inspectedAt.isBefore(contract.startsAt()) || odometerKm < 0
                || fuelPercent < 0 || fuelPercent > 100 || damageNotes == null || damageNotes.size() > 100
                || damageNotes.stream().anyMatch(d -> d == null || d.isBlank() || d.length() > 500)
                || evidenceIds == null || evidenceIds.size() > 100
                || evidenceIds.stream().anyMatch(e -> e == null || e.isBlank() || e.length() > 64)
                || (notes != null && notes.length() > 1000)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RENTAL_INSPECTION_INVALID",
                    "active contract, inspection time, vehicle condition and bounded evidence are required",
                    List.of("TR-034"));
        }
        List<Inspection> previous = repo.inspections(contractId);
        if ("RETURN".equals(type) && previous.stream().noneMatch(i -> "PICKUP".equals(i.type()))) {
            throw new DomainException(DomainException.Kind.CONFLICT, "RENTAL_PICKUP_INSPECTION_REQUIRED",
                    "record the pickup condition before the return inspection", List.of("TR-034"));
        }
        if (previous.stream().anyMatch(i -> "PICKUP".equals(i.type()) && odometerKm < i.odometerKm())) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RENTAL_ODOMETER_DECREASED",
                    "inspection odometer cannot be lower than the pickup reading", List.of("TR-034"));
        }
        Inspection inspection = new Inspection(UUID.randomUUID(), contractId, type, inspectedAt, odometerKm,
                fuelPercent, damageNotes, evidenceIds, notes == null ? null : notes.strip(), actor);
        try {
            repo.insertInspection(inspection);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DomainException(DomainException.Kind.CONFLICT, "RENTAL_INSPECTION_EXISTS",
                    "this contract already has a " + type.toLowerCase(java.util.Locale.ROOT) + " inspection",
                    List.of("TR-034"));
        }
        audit.record("RENTAL_" + type + "_INSPECTION_RECORDED", "RentalContract", contractId.toString(), null,
                Map.of("inspectionId", inspection.id().toString(), "odometerKm", odometerKm,
                        "fuelPercent", fuelPercent, "damageCount", damageNotes.size()), notes);
        events.record("itms.rental.inspection-recorded", inspection.id().toString(), Map.of(
                "inspectionId", inspection.id().toString(), "contractId", contractId.toString(),
                "type", type, "damageNotes", damageNotes, "evidenceIds", evidenceIds));
        return inspection;
    }

    /**
     * Which contract, if any, covered the plate at the instant (TR-032, VF-014): asked by violation-service when a
     * violation has no identified driver. The read reveals the renter, so it is audited.
     */
    @Transactional
    public java.util.Optional<Coverage> coverage(String plate, Instant at) {
        if (plate == null || plate.isBlank() || plate.length() > 40 || at == null) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RENTAL_COVERAGE_INVALID",
                    "give the plate and the instant", List.of("TR-032"));
        }
        java.util.Optional<Coverage> found = repo.coverage(plate, at);
        audit.record("RENTAL_COVERAGE_CHECKED", "RentalContract",
                found.map(c -> c.contractId().toString()).orElse("none"), null,
                Map.of("covered", found.isPresent(), "at", at.toString()), null);
        return found;
    }

    @Transactional(readOnly = true)
    public List<Inspection> inspections(UUID contractId) {
        contract(contractId);
        return repo.inspections(contractId);
    }

    @Transactional(readOnly = true)
    public String companyRegion(String companyFile) {
        return repo.companyRegion(companyFile)
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "RENTAL_COMPANY_NOT_FOUND",
                        "rental company is not registered", List.of("TR-021")));
    }
}
