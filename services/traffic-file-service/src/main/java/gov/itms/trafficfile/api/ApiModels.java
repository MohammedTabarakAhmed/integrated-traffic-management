package gov.itms.trafficfile.api;

import gov.itms.trafficfile.application.TrafficFileRepository.FileVersion;
import gov.itms.trafficfile.application.CompanyFileService;
import gov.itms.trafficfile.application.TrafficFileService;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Codes;
import gov.itms.trafficfile.domain.Company;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.PersonName;
import gov.itms.trafficfile.domain.Profile;
import gov.itms.trafficfile.domain.TrafficFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Request/response shapes of {@code traffic-file-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    enum DocumentType { NATIONAL_ID, PASSPORT }

    record DocumentDto(@NotNull DocumentType type, @Size(max = 40) String nationalIdNo,
                       @Pattern(regexp = Codes.PASSPORT) String passportNo,
                       @Pattern(regexp = Codes.COUNTRY) String passportCountry) {

        Identity.IdentityDocument toDomain() {
            return switch (type) {
                case NATIONAL_ID -> {
                    if (nationalIdNo == null || passportNo != null || passportCountry != null) {
                        throw DomainException.validation("DOCUMENT_FIELDS_INVALID",
                                "a national ID document has only nationalIdNo", List.of("DL-001"));
                    }
                    yield new Identity.NationalId(nationalIdNo);
                }
                case PASSPORT -> {
                    if (nationalIdNo != null || passportNo == null || passportCountry == null) {
                        throw DomainException.validation("DOCUMENT_FIELDS_INVALID",
                                "a passport document has passportNo and passportCountry", List.of("DL-001"));
                    }
                    yield new Identity.Passport(passportNo, passportCountry);
                }
            };
        }
    }

    record NameDto(@Size(max = 100) String givenNl, @Size(max = 100) String familyNl, @Size(max = 100) String givenEn,
                   @Size(max = 100) String familyEn) {

        static NameDto of(PersonName n) {
            return new NameDto(n.givenNl(), n.familyNl(), n.givenEn(), n.familyEn());
        }

        PersonName toDomain() {
            return new PersonName(givenNl, familyNl, givenEn, familyEn);
        }
    }

    record VisitorIdentityDto(@NotNull @Valid NameDto name, @NotNull @Pattern(regexp = Codes.COUNTRY) String nationality,
                              @NotNull LocalDate birthDate, @NotNull @Pattern(regexp = Codes.CODE) String gender) {

        TrafficFileService.VisitorIdentity toDomain() {
            return new TrafficFileService.VisitorIdentity(name.toDomain(), nationality, birthDate, gender);
        }
    }

    record ProfileDto(
            @Pattern(regexp = Codes.MOBILE) String mobile,
            @Size(max = 320) String email,
            @Size(max = 500) String address,
            @Pattern(regexp = Codes.CODE) String bloodGroup,
            @Pattern(regexp = Codes.CODE) String educationLevel,
            @Pattern(regexp = Codes.CODE) String profession,
            @Size(max = 20) List<@NotNull @Pattern(regexp = Codes.CODE) String> languages,
            @Size(max = 20) List<@NotNull @Pattern(regexp = Codes.CODE) String> physicalConditions,
            @Pattern(regexp = Codes.CODE) String religion,
            @Pattern(regexp = Codes.CODE) String determinationStatus) {

        /** Health-related fields (classified secret) are left out unless the caller may see them (SEC-014). */
        static ProfileDto of(Profile p, boolean sensitive) {
            return new ProfileDto(p.mobile(), p.email(), p.address(), sensitive ? p.bloodGroup() : null,
                    p.educationLevel(), p.profession(), p.languages(),
                    sensitive ? p.physicalConditions() : null, p.religion(), p.determinationStatus());
        }

        Profile toDomain() {
            return new Profile(mobile, email, address, bloodGroup, educationLevel, profession, languages,
                    physicalConditions, religion, determinationStatus);
        }
    }

    record OpenIndividualRequest(
            @NotNull @Valid DocumentDto document,
            @NotNull @Pattern(regexp = Codes.CODE) String category,
            @NotNull @Pattern(regexp = Codes.CODE) String type,
            @NotNull @Pattern(regexp = Codes.CODE) String regionOpened,
            @Valid VisitorIdentityDto visitorIdentity,
            @Valid ProfileDto profile) {

        TrafficFileService.OpenIndividual toDomain() {
            return new TrafficFileService.OpenIndividual(document.toDomain(),
                    new TrafficFile.Classification(category, type), regionOpened,
                    visitorIdentity == null ? null : visitorIdentity.toDomain(),
                    profile == null ? null : profile.toDomain());
        }
    }

    record SearchRequest(@NotNull @Valid DocumentDto document) {
    }

    record LicenceDto(@NotNull @Pattern(regexp = Codes.CODE) String source,
                      @NotNull @Pattern(regexp = Company.Licence.NUMBER) String number) {

        static LicenceDto of(Company.Licence l) {
            return new LicenceDto(l.source(), l.number());
        }

        Company.Licence toDomain() {
            return new Company.Licence(source, number);
        }
    }

    record OpenCompanyRequest(
            @NotNull @Valid LicenceDto licence,
            @NotNull @Pattern(regexp = Codes.CODE) String category,
            @NotNull @Pattern(regexp = Codes.CODE) String type,
            @NotNull @Pattern(regexp = Codes.CODE) String regionOpened,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Pattern(regexp = Codes.FILE_NUMBER) String> signatories,
            @Size(max = 20) List<@NotNull @Pattern(regexp = Codes.FILE_NUMBER) String> fleetManagers) {

        CompanyFileService.OpenCompany toDomain() {
            return new CompanyFileService.OpenCompany(licence.toDomain(),
                    new TrafficFile.Classification(category, type), regionOpened, signatories,
                    fleetManagers == null ? List.of() : fleetManagers);
        }
    }

    record RepresentativesUpdateRequest(
            @NotNull @Min(1) Long version,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Pattern(regexp = Codes.FILE_NUMBER) String> signatories,
            @Size(max = 20) List<@NotNull @Pattern(regexp = Codes.FILE_NUMBER) String> fleetManagers) {
    }

    record CompanyDto(LicenceDto licence, String nameNl, String nameEn, List<String> activities,
                      LocalDate licenceExpiry, List<String> signatories, List<String> fleetManagers) {

        static CompanyDto of(Company c) {
            return new CompanyDto(LicenceDto.of(c.licence()), c.nameNl(), c.nameEn(), c.activities(),
                    c.licenceExpiry(), c.signatories(), c.fleetManagers());
        }
    }

    record ProfileUpdateRequest(@NotNull @Min(1) Long version, @NotNull @Valid ProfileDto profile) {
    }

    record ChangeDto(String actorId, String actorUsername, Channel channel, Instant at) {
        static ChangeDto of(TrafficFile.Change c) {
            return new ChangeDto(c.actor().id(), c.actor().username(), c.channel(), c.at());
        }
    }

    record IdentityDto(DocumentType documentType, String nationalIdNo, String passportNo, String passportCountry,
                       NameDto name, String nationality, LocalDate birthDate, String gender, String residencyStatus,
                       LocalDate residencyExpiry) {

        static IdentityDto of(Identity i, boolean sensitive) {
            return switch (i.document()) {
                case Identity.NationalId n -> new IdentityDto(DocumentType.NATIONAL_ID,
                        sensitive ? n.number() : mask(n.number()), null, null, NameDto.of(i.name()),
                        i.nationality(), i.birthDate(), i.gender(), i.residencyStatus(), i.residencyExpiry());
                case Identity.Passport p -> new IdentityDto(DocumentType.PASSPORT, null,
                        sensitive ? p.number() : mask(p.number()), p.country(), NameDto.of(i.name()),
                        i.nationality(), i.birthDate(), i.gender(), i.residencyStatus(), i.residencyExpiry());
            };
        }
    }

    record TrafficFileDto(
            String fileNumber,
            TrafficFile.Kind kind,
            String category,
            String type,
            TrafficFile.Status status,
            String survivingFileNumber,
            String regionOpened,
            TrafficFile.IdentitySource identitySource,
            Instant registerSyncedAt,
            IdentityDto identity,
            CompanyDto company,
            Set<String> lockedFields,
            ProfileDto profile,
            ChangeDto opened,
            ChangeDto lastChange,
            long version) {

        static TrafficFileDto of(TrafficFile f, boolean sensitive) {
            boolean company = f.kind() == TrafficFile.Kind.COMPANY;
            Set<String> locked = company ? new TreeSet<>(Company.REGISTER_FIELDS)
                    : f.identitySource() == TrafficFile.IdentitySource.REGISTER
                    ? new TreeSet<>(Identity.REGISTER_FIELDS) : new TreeSet<>();
            return new TrafficFileDto(f.fileNumber(), f.kind(), f.classification().category(),
                    f.classification().type(), f.status(), f.survivingFileNumber(), f.regionOpened(),
                    f.identitySource(),
                    f.registerSyncedAt(), company ? null : IdentityDto.of(f.identity(), sensitive),
                    company ? CompanyDto.of(f.company()) : null, locked,
                    company ? null : ProfileDto.of(f.profile(), sensitive), ChangeDto.of(f.opened()),
                    ChangeDto.of(f.lastChange()), f.version());
        }
    }

    record FileVersionDto(long version, Instant validFrom, Instant validTo, String changedBy, Map<String, Object> state) {

        /** Stored row images minus fields the caller may not see (SEC-014). */
        static FileVersionDto of(FileVersion v, boolean sensitive) {
            Map<String, Object> state = new java.util.LinkedHashMap<>(v.state());
            if (!sensitive) {
                state.computeIfPresent("national_id_no", (k, value) -> mask(value.toString()));
                state.computeIfPresent("passport_no", (k, value) -> mask(value.toString()));
                state.remove("blood_group");
                state.remove("physical_conditions");
            }
            return new FileVersionDto(v.version(), v.validFrom(), v.validTo(), v.changedBy(), state);
        }
    }

    record Collection<T>(List<T> items) {
    }

    /** Shows only the last four characters (SEC-014). */
    static String mask(String value) {
        int keep = Math.min(4, value.length());
        return "*".repeat(value.length() - keep) + value.substring(value.length() - keep);
    }
}
