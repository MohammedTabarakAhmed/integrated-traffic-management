package gov.itms.rental.infrastructure;

import gov.itms.rental.application.Ports;
import gov.itms.rental.application.RentalService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for rental companies and contracts. */
public class JdbcRentalRepository implements Ports.RentalRepository {
    private final JdbcClient jdbc;
    public JdbcRentalRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void insertCompany(UUID id, String file, String licence, String region, String actor, Instant at) {
        jdbc.sql("insert into rental_company(id,traffic_file_number,commercial_licence,region,status,created_at,created_by) "
                        + "values (:id,:file,:licence,:region,'ACTIVE',:at,:actor)")
                .param("id", id).param("file", file).param("licence", licence).param("region", region)
                .param("at", at).param("actor", actor).update();
    }

    @Override
    public Optional<RentalService.Company> companyForUpdate(String companyFile) {
        return jdbc.sql("select id,traffic_file_number,commercial_licence,region,status,created_at from rental_company "
                        + "where traffic_file_number=:file for update")
                .param("file", companyFile).query(JdbcRentalRepository::mapCompany).optional();
    }

    @Override
    public Optional<String> companyRegion(String companyFile) {
        return jdbc.sql("select region from rental_company where traffic_file_number=:file")
                .param("file", companyFile).query(String.class).optional();
    }

    @Override
    public RentalService.Branch insertBranch(String companyFile,String code,String name,String region,String actor,Instant at) {
        jdbc.sql("insert into rental_branch(company_file,branch_code,name,region,active,created_at,created_by) values (:company,:code,:name,:region,true,:at,:actor)")
                .param("company",companyFile).param("code",code).param("name",name).param("region",region).param("at",at).param("actor",actor).update();
        return new RentalService.Branch(companyFile,code,name,region,true,at);
    }

    @Override
    public boolean activeBranch(String companyFile,String code) {
        return jdbc.sql("select exists(select 1 from rental_branch where company_file=:company and branch_code=:code and active=true)")
                .param("company",companyFile).param("code",code).query(Boolean.class).single();
    }

    @Override
    public RentalService.Company setCompanyStatus(String file,String status,String reason,String actor,Instant at) {
        jdbc.sql("update rental_company set status=:status,status_reason=:reason,status_changed_at=:at,status_changed_by=:actor where traffic_file_number=:file")
                .param("status",status).param("reason",reason).param("at",at).param("actor",actor).param("file",file).update();
        return companyForUpdate(file).orElseThrow();
    }

    @Override
    public void insertContract(UUID id, String companyFile, String plate, String renterFile, String passport,
                               String foreignLicence, List<String> drivers, Instant startsAt, Instant endsAt,
                               String pickup, String returns, String type, long bondMinor, String actor, Instant at) {
        jdbc.sql("insert into rental_contract(id,company_file,vehicle_plate,renter_file,visitor_passport,"
                        + "foreign_licence,starts_at,ends_at,pickup_branch,return_branch,contract_type,bond_minor,"
                        + "status,created_at,created_by) values (:id,:company,:plate,:renter,:passport,:licence,"
                        + ":starts,:ends,:pickup,:return,:type,:bond,'ACTIVE',:at,:actor)")
                .param("id", id).param("company", companyFile).param("plate", plate).param("renter", renterFile)
                .param("passport", passport).param("licence", foreignLicence).param("starts", startsAt)
                .param("ends", endsAt).param("pickup", pickup).param("return", returns).param("type", type)
                .param("bond", bondMinor).param("at", at).param("actor", actor).update();
        for (int i = 0; i < drivers.size(); i++) {
            jdbc.sql("insert into rental_contract_driver(contract_id,driver_no,traffic_file_number) "
                            + "values (:id,:no,:driver)")
                    .param("id", id).param("no", i + 1).param("driver", drivers.get(i)).update();
        }
    }

    @Override
    public Optional<RentalService.Contract> contract(UUID id) {
        Optional<RentalService.Contract> found = jdbc.sql("select c.region,r.* from rental_contract r "
                        + "join rental_company c on c.traffic_file_number=r.company_file where r.id=:id")
                .param("id", id).query(JdbcRentalRepository::mapContract).optional();
        return found.map(c -> new RentalService.Contract(c.id(), c.companyFile(), c.region(), c.vehiclePlate(),
                c.renterFile(), c.visitorPassport(), c.foreignLicence(), jdbc.sql("select traffic_file_number "
                + "from rental_contract_driver where contract_id=:id order by driver_no").param("id", id)
                .query(String.class).list(), c.startsAt(), c.endsAt(), c.pickupBranch(), c.returnBranch(),
                c.type(), c.bondMinor(), c.status(), c.createdAt()));
    }

    @Override
    public void insertInspection(RentalService.Inspection inspection) {
        jdbc.sql("insert into rental_inspection(id,contract_id,inspection_type,inspected_at,odometer_km,fuel_percent,"
                        + "damage_notes,evidence_ids,notes,inspected_by) values (:id,:contract,:type,:at,:odometer,"
                        + ":fuel,:damage,:evidence,:notes,:by)")
                .param("id", inspection.id()).param("contract", inspection.contractId())
                .param("type", inspection.type()).param("at", inspection.inspectedAt())
                .param("odometer", inspection.odometerKm()).param("fuel", inspection.fuelPercent())
                .param("damage", inspection.damageNotes().toArray(String[]::new))
                .param("evidence", inspection.evidenceIds().toArray(String[]::new))
                .param("notes", inspection.notes()).param("by", inspection.inspectedBy()).update();
    }

    @Override
    public List<RentalService.Inspection> inspections(UUID contractId) {
        return jdbc.sql("select * from rental_inspection where contract_id=:id order by inspected_at,inspection_type")
                .param("id", contractId).query(JdbcRentalRepository::mapInspection).list();
    }

    private static RentalService.Company mapCompany(ResultSet rs, int row) throws SQLException {
        return new RentalService.Company(rs.getObject("id", UUID.class), rs.getString("traffic_file_number"),
                rs.getString("commercial_licence"), rs.getString("region"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static RentalService.Contract mapContract(ResultSet rs, int row) throws SQLException {
        return new RentalService.Contract(rs.getObject("id", UUID.class), rs.getString("company_file"),
                rs.getString("region"), rs.getString("vehicle_plate"), rs.getString("renter_file"),
                rs.getString("visitor_passport"), rs.getString("foreign_licence"), List.of(),
                rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                rs.getString("pickup_branch"), rs.getString("return_branch"), rs.getString("contract_type"),
                rs.getLong("bond_minor"), rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }

    private static RentalService.Inspection mapInspection(ResultSet rs, int row) throws SQLException {
        return new RentalService.Inspection(rs.getObject("id", UUID.class), rs.getObject("contract_id", UUID.class),
                rs.getString("inspection_type"), rs.getTimestamp("inspected_at").toInstant(),
                rs.getLong("odometer_km"), rs.getInt("fuel_percent"), arrayValues(rs.getArray("damage_notes")),
                arrayValues(rs.getArray("evidence_ids")), rs.getString("notes"), rs.getString("inspected_by"));
    }

    private static List<String> arrayValues(java.sql.Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(Object::toString).toList();
    }
}
