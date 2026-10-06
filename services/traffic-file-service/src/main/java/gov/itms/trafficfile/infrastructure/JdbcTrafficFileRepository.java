package gov.itms.trafficfile.infrastructure;

import gov.itms.trafficfile.application.TrafficFileRepository;
import gov.itms.trafficfile.domain.Actor;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Company;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.PersonName;
import gov.itms.trafficfile.domain.Profile;
import gov.itms.trafficfile.domain.TrafficFile;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Traffic files in {@code traffic_file}; the database guards the identity and number and writes the temporal history
 * ({@code traffic_file_history}) in the same transaction (V1).
 */
class JdbcTrafficFileRepository implements TrafficFileRepository {

    private static final String COLUMNS = """
            id, file_number, kind, category, type, status, region_opened, identity_source, register_synced_at,
            national_id_no, passport_no, passport_country, given_nl, family_nl, given_en, family_en, nationality,
            birth_date, gender, residency_status, residency_expiry, mobile, email, address, blood_group,
            education_level, profession, languages, physical_conditions, religion, determination_status, opened_at,
            opened_by, opened_by_username, opened_channel, changed_at, changed_by, changed_by_username,
            changed_channel, version, licence_source, commercial_licence_no, company_name_nl, company_name_en,
            activities, licence_expiry, signatories, fleet_managers""";
    private static final String SELECT = "select " + COLUMNS + " from traffic_file";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final String numberPrefix;
    private final int numberWidth;

    JdbcTrafficFileRepository(JdbcClient jdbc, ObjectMapper json, String numberPrefix, int numberWidth) {
        this.jdbc = jdbc;
        this.json = json;
        this.numberPrefix = numberPrefix;
        this.numberWidth = numberWidth;
    }

    @Override
    public String nextFileNumber() {
        long next = jdbc.sql("select nextval('traffic_file_number_seq')").query(Long.class).single();
        String digits = Long.toString(next);
        if (digits.length() < numberWidth) {
            digits = "0".repeat(numberWidth - digits.length()) + digits;
        }
        return numberPrefix + digits + luhn(digits);
    }

    /** Luhn check digit of a digit string (Q-022: the file number carries a check digit). */
    static int luhn(String digits) {
        int sum = 0;
        boolean twice = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return (10 - sum % 10) % 10;
    }

    @Override
    public void insert(TrafficFile file) {
        if (file.kind() == TrafficFile.Kind.COMPANY) {
            insertCompany(file);
            return;
        }
        Identity identity = file.identity();
        String nationalId = identity.document() instanceof Identity.NationalId n ? n.number() : null;
        String passportNo = identity.document() instanceof Identity.Passport p ? p.number() : null;
        String passportCountry = identity.document() instanceof Identity.Passport p ? p.country() : null;
        PersonName name = identity.name();
        Profile profile = file.profile();
        try {
            jdbc.sql("""
                            insert into traffic_file (id, file_number, kind, category, type, status, region_opened,
                                identity_source, register_synced_at, national_id_no, passport_no, passport_country,
                                given_nl, family_nl, given_en, family_en, nationality, birth_date, gender,
                                residency_status, residency_expiry, mobile, email, address, blood_group,
                                education_level, profession, languages, physical_conditions, religion,
                                determination_status, opened_at, opened_by, opened_by_username, opened_channel,
                                changed_at, changed_by, changed_by_username, changed_channel, version)
                            values (:id, :fileNumber, :kind, :category, :type, :status, :region, :source, :syncedAt,
                                :nationalId, :passportNo, :passportCountry, :givenNl, :familyNl, :givenEn, :familyEn,
                                :nationality, :birthDate, :gender, :residencyStatus, :residencyExpiry, :mobile, :email,
                                :address, :bloodGroup, :educationLevel, :profession,
                                string_to_array(:languages, ','), string_to_array(:conditions, ','), :religion,
                                :determination, :at, :by, :byName, :channel, :at, :by, :byName, :channel, :version)""")
                    .param("id", file.id())
                    .param("fileNumber", file.fileNumber())
                    .param("kind", file.kind().name())
                    .param("category", file.classification().category())
                    .param("type", file.classification().type())
                    .param("status", file.status().name())
                    .param("region", file.regionOpened())
                    .param("source", file.identitySource().name())
                    .param("syncedAt", timestamp(file.registerSyncedAt()))
                    .param("nationalId", nationalId)
                    .param("passportNo", passportNo)
                    .param("passportCountry", passportCountry)
                    .param("givenNl", name.givenNl())
                    .param("familyNl", name.familyNl())
                    .param("givenEn", name.givenEn())
                    .param("familyEn", name.familyEn())
                    .param("nationality", identity.nationality())
                    .param("birthDate", Date.valueOf(identity.birthDate()))
                    .param("gender", identity.gender())
                    .param("residencyStatus", identity.residencyStatus())
                    .param("residencyExpiry", date(identity.residencyExpiry()))
                    .param("mobile", profile.mobile())
                    .param("email", profile.email())
                    .param("address", profile.address())
                    .param("bloodGroup", profile.bloodGroup())
                    .param("educationLevel", profile.educationLevel())
                    .param("profession", profile.profession())
                    .param("languages", String.join(",", profile.languages()))
                    .param("conditions", String.join(",", profile.physicalConditions()))
                    .param("religion", profile.religion())
                    .param("determination", profile.determinationStatus())
                    .param("at", Timestamp.from(file.opened().at()))
                    .param("by", file.opened().actor().id())
                    .param("byName", file.opened().actor().username())
                    .param("channel", file.opened().channel().name())
                    .param("version", file.version())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdentityException(e);
        }
    }

    private void insertCompany(TrafficFile file) {
        Company company = file.company();
        try {
            jdbc.sql("""
                            insert into traffic_file (id, file_number, kind, category, type, status, region_opened,
                                identity_source, register_synced_at, licence_source, commercial_licence_no,
                                company_name_nl, company_name_en, activities, licence_expiry, signatories,
                                fleet_managers, opened_at, opened_by, opened_by_username, opened_channel, changed_at,
                                changed_by, changed_by_username, changed_channel, version)
                            values (:id, :fileNumber, 'COMPANY', :category, :type, :status, :region, 'REGISTER',
                                :syncedAt, :source, :licenceNo, :nameNl, :nameEn, string_to_array(:activities, ','),
                                :expiry, string_to_array(:signatories, ','), string_to_array(:fleetManagers, ','),
                                :at, :by, :byName, :channel, :at, :by, :byName, :channel, :version)""")
                    .param("id", file.id())
                    .param("fileNumber", file.fileNumber())
                    .param("category", file.classification().category())
                    .param("type", file.classification().type())
                    .param("status", file.status().name())
                    .param("region", file.regionOpened())
                    .param("syncedAt", timestamp(file.registerSyncedAt()))
                    .param("source", company.licence().source())
                    .param("licenceNo", company.licence().number())
                    .param("nameNl", company.nameNl())
                    .param("nameEn", company.nameEn())
                    .param("activities", String.join(",", company.activities()))
                    .param("expiry", Date.valueOf(company.licenceExpiry()))
                    .param("signatories", String.join(",", company.signatories()))
                    .param("fleetManagers", String.join(",", company.fleetManagers()))
                    .param("at", Timestamp.from(file.opened().at()))
                    .param("by", file.opened().actor().id())
                    .param("byName", file.opened().actor().username())
                    .param("channel", file.opened().channel().name())
                    .param("version", file.version())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdentityException(e);
        }
    }

    @Override
    public Optional<TrafficFile> findByLicence(String source, String licenceNo) {
        return jdbc.sql(SELECT + " where licence_source = :s and commercial_licence_no = :n and status <> 'MERGED'")
                .param("s", source).param("n", licenceNo).query(this::map).optional();
    }

    @Override
    public List<TrafficFile> findByNumbers(List<String> fileNumbers) {
        if (fileNumbers.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where file_number in (:numbers)").param("numbers", fileNumbers).query(this::map)
                .list();
    }

    @Override
    public void saveRepresentatives(TrafficFile file) {
        Company company = file.company();
        TrafficFile.Change change = file.lastChange();
        int updated = jdbc.sql("""
                        update traffic_file set signatories = string_to_array(:signatories, ','),
                            fleet_managers = string_to_array(:fleetManagers, ','), changed_at = :at, changed_by = :by,
                            changed_by_username = :byName, changed_channel = :channel, version = :version
                        where id = :id and version = :previous""")
                .param("signatories", String.join(",", company.signatories()))
                .param("fleetManagers", String.join(",", company.fleetManagers()))
                .param("at", Timestamp.from(change.at()))
                .param("by", change.actor().id())
                .param("byName", change.actor().username())
                .param("channel", change.channel().name())
                .param("version", file.version())
                .param("previous", file.version() - 1)
                .param("id", file.id())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("traffic file " + file.fileNumber() + " changed concurrently");
        }
    }

    @Override
    public Optional<TrafficFile> findByNumber(String fileNumber) {
        return jdbc.sql(SELECT + " where file_number = :n").param("n", fileNumber).query(this::map).optional();
    }

    @Override
    public Optional<TrafficFile> findByNumberForUpdate(String fileNumber) {
        return jdbc.sql(SELECT + " where file_number = :n for update").param("n", fileNumber).query(this::map)
                .optional();
    }

    @Override
    public Optional<TrafficFile> findByNationalId(String nationalIdNo) {
        return jdbc.sql(SELECT + " where national_id_no = :n and status <> 'MERGED'").param("n", nationalIdNo)
                .query(this::map).optional();
    }

    @Override
    public Optional<TrafficFile> findByPassport(String passportNo, String passportCountry) {
        return jdbc.sql(SELECT + " where passport_no = :n and passport_country = :c and status <> 'MERGED'")
                .param("n", passportNo).param("c", passportCountry).query(this::map).optional();
    }

    @Override
    public void saveProfile(TrafficFile file) {
        Profile profile = file.profile();
        TrafficFile.Change change = file.lastChange();
        int updated = jdbc.sql("""
                        update traffic_file set mobile = :mobile, email = :email, address = :address,
                            blood_group = :bloodGroup, education_level = :educationLevel, profession = :profession,
                            languages = string_to_array(:languages, ','),
                            physical_conditions = string_to_array(:conditions, ','), religion = :religion,
                            determination_status = :determination, changed_at = :at, changed_by = :by,
                            changed_by_username = :byName, changed_channel = :channel, version = :version
                        where id = :id and version = :previous""")
                .param("mobile", profile.mobile())
                .param("email", profile.email())
                .param("address", profile.address())
                .param("bloodGroup", profile.bloodGroup())
                .param("educationLevel", profile.educationLevel())
                .param("profession", profile.profession())
                .param("languages", String.join(",", profile.languages()))
                .param("conditions", String.join(",", profile.physicalConditions()))
                .param("religion", profile.religion())
                .param("determination", profile.determinationStatus())
                .param("at", Timestamp.from(change.at()))
                .param("by", change.actor().id())
                .param("byName", change.actor().username())
                .param("channel", change.channel().name())
                .param("version", file.version())
                .param("previous", file.version() - 1)
                .param("id", file.id())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("traffic file " + file.fileNumber() + " changed concurrently");
        }
    }

    @Override
    public List<FileVersion> history(UUID fileId) {
        return jdbc.sql("""
                        select version, valid_from, valid_to, changed_by, row_image::text as row_image
                        from traffic_file_history where file_id = :id order by version""")
                .param("id", fileId)
                .query((rs, n) -> new FileVersion(rs.getLong("version"), instant(rs.getTimestamp("valid_from")),
                        instant(rs.getTimestamp("valid_to")), rs.getString("changed_by"),
                        json.readValue(rs.getString("row_image"), new TypeReference<Map<String, Object>>() {
                        })))
                .list();
    }

    private TrafficFile map(ResultSet rs, int row) throws SQLException {
        TrafficFile.Kind kind = TrafficFile.Kind.valueOf(rs.getString("kind"));
        Identity identity = null;
        Profile profile = null;
        Company company = null;
        if (kind == TrafficFile.Kind.COMPANY) {
            company = new Company(new Company.Licence(rs.getString("licence_source"),
                    rs.getString("commercial_licence_no")), rs.getString("company_name_nl"),
                    rs.getString("company_name_en"), strings(rs.getArray("activities")),
                    localDate(rs.getDate("licence_expiry")), strings(rs.getArray("signatories")),
                    strings(rs.getArray("fleet_managers")));
        } else {
            identity = identity(rs);
            profile = new Profile(rs.getString("mobile"), rs.getString("email"), rs.getString("address"),
                    rs.getString("blood_group"), rs.getString("education_level"), rs.getString("profession"),
                    strings(rs.getArray("languages")), strings(rs.getArray("physical_conditions")),
                    rs.getString("religion"), rs.getString("determination_status"));
        }
        return TrafficFile.restore(
                rs.getObject("id", UUID.class),
                rs.getString("file_number"),
                kind,
                new TrafficFile.Classification(rs.getString("category"), rs.getString("type")),
                rs.getString("region_opened"),
                identity,
                TrafficFile.IdentitySource.valueOf(rs.getString("identity_source")),
                instant(rs.getTimestamp("register_synced_at")),
                change(rs, "opened"),
                TrafficFile.Status.valueOf(rs.getString("status")),
                profile,
                company,
                change(rs, "changed"),
                rs.getLong("version"));
    }

    private static Identity identity(ResultSet rs) throws SQLException {
        String nationalId = rs.getString("national_id_no");
        Identity.IdentityDocument document = nationalId != null ? new Identity.NationalId(nationalId)
                : new Identity.Passport(rs.getString("passport_no"), rs.getString("passport_country"));
        return new Identity(document,
                new PersonName(rs.getString("given_nl"), rs.getString("family_nl"), rs.getString("given_en"),
                        rs.getString("family_en")),
                rs.getString("nationality"), localDate(rs.getDate("birth_date")), rs.getString("gender"),
                rs.getString("residency_status"), localDate(rs.getDate("residency_expiry")));
    }

    private static TrafficFile.Change change(ResultSet rs, String prefix) throws SQLException {
        return new TrafficFile.Change(new Actor(rs.getString(prefix + "_by"), rs.getString(prefix + "_by_username")),
                Channel.valueOf(rs.getString(prefix + "_channel")), rs.getTimestamp(prefix + "_at").toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Date date(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static LocalDate localDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
