package gov.itms.trafficfile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.trafficfile.application.CommercialRegister.CompanyLicence;
import gov.itms.trafficfile.domain.Actor;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Company;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.PersonName;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Use cases of {@link CompanyFileService} with in-memory fakes (DL-003, DL-004, CC-001, CC-120). */
@Tag("DL-003")
class CompanyFileServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);
    private static final Company.Licence LICENCE = new Company.Licence("DXB_DED", "CN-1001234");

    private final TrafficFileServiceTest.FakeFiles files = new TrafficFileServiceTest.FakeFiles();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private Optional<CompanyLicence> licence = Optional.of(new CompanyLicence("CN-1001234", "شركة", "Company LLC",
            List.of("LAND_TRANSPORT"), LocalDate.of(2030, 12, 31), false));
    private boolean unavailable;
    private CompanyFileService service;
    private String signatory;
    private String manager;

    @BeforeEach
    void setUp() {
        CommercialRegister register = (source, number) -> {
            if (unavailable) {
                throw new CommercialRegister.RegisterUnavailableException("down", null);
            }
            return licence;
        };
        service = new CompanyFileService(files, register, auditRecorder(), eventRecorder(), CLOCK);
        signatory = individual("AB12345");
        manager = individual("CD12345");
    }

    private String individual(String passport) {
        TrafficFile file = TrafficFile.openIndividual(UUID.randomUUID(), files.nextFileNumber(),
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB",
                new Identity(new Identity.Passport(passport, "GBR"), new PersonName(null, null, "A", "B"), "GBR",
                        LocalDate.of(1980, 1, 1), "MALE", null, null),
                TrafficFile.IdentitySource.DOCUMENT, null, null, new Actor("u-0", "seed"), Channel.BACKOFFICE,
                CLOCK.instant(), LocalDate.of(2026, 10, 6));
        files.insert(file);
        return file.fileNumber();
    }

    private Caller caller(Set<String> regions, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor("u-1", "officer"), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission) && (regions.contains("*") || regions.contains(region));
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    private Caller officer() {
        return caller(Set.of("DXB"), TrafficFileService.CREATE, TrafficFileService.READ, TrafficFileService.UPDATE);
    }

    private CompanyFileService.OpenCompany command(String region, List<String> signatories) {
        return new CompanyFileService.OpenCompany(LICENCE, new TrafficFile.Classification("COMPANY", "LLC"), region,
                signatories, List.of(manager));
    }

    @Test
    @DisplayName("a company file takes the licence details from the authority, is audited and published")
    void opens() {
        TrafficFile file = service.open(command("DXB", List.of(signatory)), officer());
        assertThat(file.kind()).isEqualTo(TrafficFile.Kind.COMPANY);
        assertThat(file.company().nameEn()).isEqualTo("Company LLC");
        assertThat(file.company().activities()).containsExactly("LAND_TRANSPORT");
        assertThat(file.company().fleetManagers()).containsExactly(manager);
        assertThat(file.registerSyncedAt()).isEqualTo(CLOCK.instant());
        assertThat(audit).containsExactly("TRAFFIC_FILE_OPENED:" + file.fileNumber());
        assertThat(events).containsExactly("itms.traffic-file.opened:" + file.fileNumber());
    }

    @Test
    @Tag("CC-001")
    @DisplayName("opening is scoped by region; one file per licence")
    void scopeAndDuplicates() {
        assertThatThrownBy(() -> service.open(command("AUH", List.of(signatory)), officer()))
                .hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
        TrafficFile first = service.open(command("DXB", List.of(signatory)), officer());
        assertThatThrownBy(() -> service.open(command("DXB", List.of(signatory)), officer()))
                .hasFieldOrPropertyWithValue("code", "FILE_ALREADY_EXISTS").hasMessageContaining(first.fileNumber());
    }

    @Test
    @DisplayName("unknown, cancelled and unavailable licences are refused")
    void authorityOutcomes() {
        licence = Optional.empty();
        assertThatThrownBy(() -> service.open(command("DXB", List.of(signatory)), officer()))
                .hasFieldOrPropertyWithValue("code", "COMPANY_LICENCE_NOT_FOUND");
        licence = Optional.of(new CompanyLicence("CN-1001234", null, "X", List.of(), LocalDate.of(2030, 1, 1), true));
        assertThatThrownBy(() -> service.open(command("DXB", List.of(signatory)), officer()))
                .hasFieldOrPropertyWithValue("code", "COMPANY_LICENCE_CANCELLED");
        unavailable = true;
        assertThatThrownBy(() -> service.open(command("DXB", List.of(signatory)), officer()))
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAVAILABLE);
        assertThat(audit).isEmpty();
    }

    @Test
    @Tag("DL-004")
    @DisplayName("representatives must be existing active individuals' files, never a company")
    void representativesMustBeIndividuals() {
        assertThatThrownBy(() -> service.open(command("DXB", List.of("99999999990")), officer()))
                .hasFieldOrPropertyWithValue("code", "REPRESENTATIVE_INVALID").hasMessageContaining("99999999990");
        TrafficFile company = service.open(command("DXB", List.of(signatory)), officer());
        licence = Optional.of(new CompanyLicence("CN-2", null, "Other", List.of(), LocalDate.of(2030, 1, 1), false));
        CompanyFileService.OpenCompany other = new CompanyFileService.OpenCompany(new Company.Licence("DXB_DED", "CN-2"),
                new TrafficFile.Classification("COMPANY", "LLC"), "DXB", List.of(company.fileNumber()), List.of());
        assertThatThrownBy(() -> service.open(other, officer()))
                .hasFieldOrPropertyWithValue("code", "REPRESENTATIVE_INVALID");
    }

    @Test
    @Tag("DL-004")
    @DisplayName("representatives are replaced with the version read; audited and published")
    void updateRepresentatives() {
        TrafficFile company = service.open(command("DXB", List.of(signatory)), officer());
        audit.clear();
        events.clear();
        TrafficFile updated = service.updateRepresentatives(company.fileNumber(), 1, List.of(manager), List.of(),
                officer());
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.company().signatories()).containsExactly(manager);
        assertThat(audit).containsExactly("TRAFFIC_FILE_REPRESENTATIVES_UPDATED:" + company.fileNumber());
        assertThat(events).containsExactly("itms.traffic-file.representatives-updated:" + company.fileNumber());
        assertThatThrownBy(() -> service.updateRepresentatives(company.fileNumber(), 1, List.of(manager), List.of(),
                officer())).hasFieldOrPropertyWithValue("code", "VERSION_MISMATCH");
        assertThatThrownBy(() -> service.updateRepresentatives(signatory, 1, List.of(manager), List.of(), officer()))
                .hasFieldOrPropertyWithValue("code", "WRONG_FILE_KIND");
        assertThatThrownBy(() -> service.updateRepresentatives(company.fileNumber(), 2, List.of(manager), List.of(),
                caller(Set.of("*"), TrafficFileService.READ))).hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
    }

    private AuditRecorder auditRecorder() {
        return new AuditRecorder() {
            @Override
            public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                               String reason) {
                audit.add(action + ":" + entityId);
            }

            @Override
            public void recordAs(CurrentActor actor, String action, String entityType, String entityId,
                                 Object oldValue, Object newValue, String reason) {
                audit.add(action + ":" + entityId);
            }
        };
    }

    private DomainEventRecorder eventRecorder() {
        return new DomainEventRecorder() {
            @Override
            public void record(String eventType, String subject, Object data) {
                events.add(eventType + ":" + subject);
            }

            @Override
            public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
                events.add(eventType + ":" + subject);
            }
        };
    }
}
