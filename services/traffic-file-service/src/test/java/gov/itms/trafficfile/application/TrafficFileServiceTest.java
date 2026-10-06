package gov.itms.trafficfile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.trafficfile.application.IdentityRegister.RegisterPerson;
import gov.itms.trafficfile.domain.Actor;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.Identity;
import gov.itms.trafficfile.domain.PersonName;
import gov.itms.trafficfile.domain.Profile;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Use cases of {@link TrafficFileService} with in-memory fakes (DL-001, DL-002, DL-005, CC-001, CC-120). */
@Tag("DL-001")
class TrafficFileServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);
    private static final String CITIZEN = "784199012345671";

    private final FakeFiles files = new FakeFiles();
    private final FakeRegister register = new FakeRegister();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final TrafficFileService service = new TrafficFileService(files, register, auditRecorder(),
            eventRecorder(), CLOCK, Pattern.compile("^[0-9]{6,20}$"));

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

    private static TrafficFileService.OpenIndividual citizen(String region) {
        return new TrafficFileService.OpenIndividual(new Identity.NationalId(CITIZEN),
                new TrafficFile.Classification("INDIVIDUAL", "CITIZEN"), region, null, null);
    }

    private static TrafficFileService.OpenIndividual visitor(String passport) {
        return new TrafficFileService.OpenIndividual(new Identity.Passport(passport, "GBR"),
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB",
                new TrafficFileService.VisitorIdentity(new PersonName(null, null, "Jane", "Doe"), "GBR",
                        LocalDate.of(1985, 5, 5), "FEMALE"), null);
    }

    @Test
    @Tag("DL-002")
    @DisplayName("a national ID file is filled from the register, audited and published")
    void opensFromRegister() {
        TrafficFile file = service.openIndividual(citizen("DXB"), officer());
        assertThat(file.identitySource()).isEqualTo(TrafficFile.IdentitySource.REGISTER);
        assertThat(file.identity().name().givenEn()).isEqualTo("Ahmed");
        assertThat(file.registerSyncedAt()).isEqualTo(CLOCK.instant());
        assertThat(file.fileNumber()).isEqualTo("TF0000000001");
        assertThat(audit).containsExactly("TRAFFIC_FILE_OPENED:" + file.fileNumber());
        assertThat(events).containsExactly("itms.traffic-file.opened:" + file.fileNumber());
    }

    @Test
    @Tag("CC-001")
    @DisplayName("opening needs trafficfile.create reaching the region; nothing is written otherwise")
    void regionScope() {
        assertThatThrownBy(() -> service.openIndividual(citizen("AUH"), officer()))
                .hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
        assertThat(files.byNumber).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(register.calls).isZero();
    }

    @Test
    @DisplayName("a second file for the same document is refused before calling the register")
    void duplicate() {
        TrafficFile first = service.openIndividual(citizen("DXB"), officer());
        int calls = register.calls;
        assertThatThrownBy(() -> service.openIndividual(citizen("DXB"), officer()))
                .hasFieldOrPropertyWithValue("code", "FILE_ALREADY_EXISTS")
                .hasMessageContaining(first.fileNumber());
        assertThat(register.calls).isEqualTo(calls);
    }

    @Test
    @DisplayName("a concurrent insert of the same document is reported as the existing file")
    void concurrentDuplicate() {
        service.openIndividual(visitor("AB12345"), officer());
        files.failNextInsertAsDuplicate = true;
        files.hideNextLookup = true;
        assertThatThrownBy(() -> service.openIndividual(visitor("AB12345"), officer()))
                .hasFieldOrPropertyWithValue("code", "FILE_ALREADY_EXISTS");
    }

    @Test
    @Tag("DL-002")
    @DisplayName("register outcomes: unknown, deceased, unavailable; register fields cannot be entered")
    void registerOutcomes() {
        register.person = Optional.empty();
        assertThatThrownBy(() -> service.openIndividual(citizen("DXB"), officer()))
                .hasFieldOrPropertyWithValue("code", "REGISTER_PERSON_NOT_FOUND");
        register.person = Optional.of(person(true));
        assertThatThrownBy(() -> service.openIndividual(citizen("DXB"), officer()))
                .hasFieldOrPropertyWithValue("code", "PERSON_DECEASED");
        register.unavailable = true;
        assertThatThrownBy(() -> service.openIndividual(citizen("DXB"), officer()))
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAVAILABLE);
        TrafficFileService.OpenIndividual withFields = new TrafficFileService.OpenIndividual(
                new Identity.NationalId(CITIZEN), new TrafficFile.Classification("INDIVIDUAL", "CITIZEN"), "DXB",
                visitor("AB12345").visitor(), null);
        assertThatThrownBy(() -> service.openIndividual(withFields, officer()))
                .hasFieldOrPropertyWithValue("code", "REGISTER_FIELDS_GIVEN");
        TrafficFileService.OpenIndividual malformed = new TrafficFileService.OpenIndividual(
                new Identity.NationalId("12-34"), new TrafficFile.Classification("INDIVIDUAL", "CITIZEN"), "DXB",
                null, null);
        assertThatThrownBy(() -> service.openIndividual(malformed, officer()))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }

    @Test
    @DisplayName("a passport file needs the visitor identity")
    void visitorNeedsIdentity() {
        TrafficFileService.OpenIndividual noIdentity = new TrafficFileService.OpenIndividual(
                new Identity.Passport("AB12345", "GBR"), new TrafficFile.Classification("INDIVIDUAL", "VISITOR"),
                "DXB", null, null);
        assertThatThrownBy(() -> service.openIndividual(noIdentity, officer()))
                .hasFieldOrPropertyWithValue("code", "VISITOR_IDENTITY_REQUIRED");
        assertThat(service.openIndividual(visitor("CD12345"), officer()).identitySource())
                .isEqualTo(TrafficFile.IdentitySource.DOCUMENT);
    }

    @Test
    @DisplayName("a channel is required for changes")
    void channelRequired() {
        Caller noChannel = new Caller(new Actor("u-1", "x"), null, officer().grants());
        assertThatThrownBy(() -> service.openIndividual(visitor("AB12345"), noChannel))
                .hasFieldOrPropertyWithValue("code", "CHANNEL_REQUIRED");
    }

    @Test
    @Tag("DL-005")
    @DisplayName("updating the profile checks the version read, audits and publishes")
    void profileUpdate() {
        TrafficFile file = service.openIndividual(visitor("AB12345"), officer());
        audit.clear();
        events.clear();
        Profile profile = new Profile("+971501234567", null, null, null, null, null, null, null, null, null);
        TrafficFile updated = service.updateProfile(file.fileNumber(), 1, profile, officer());
        assertThat(updated.version()).isEqualTo(2);
        assertThat(audit).containsExactly("TRAFFIC_FILE_PROFILE_UPDATED:" + file.fileNumber());
        assertThat(events).containsExactly("itms.traffic-file.profile-updated:" + file.fileNumber());
        assertThatThrownBy(() -> service.updateProfile(file.fileNumber(), 1, profile, officer()))
                .hasFieldOrPropertyWithValue("code", "VERSION_MISMATCH");
        assertThatThrownBy(() -> service.updateProfile(file.fileNumber(), 2, profile,
                caller(Set.of("*"), TrafficFileService.READ))).hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.updateProfile("TF404", 1, profile, officer()))
                .hasFieldOrPropertyWithValue("code", "FILE_NOT_FOUND");
    }

    @Test
    @Tag("CC-113")
    @DisplayName("reads work in any region and are audited; searches are audited found or not (CC-102)")
    void readsAndSearches() {
        TrafficFile file = service.openIndividual(visitor("AB12345"), officer());
        audit.clear();
        Caller elsewhere = caller(Set.of("SHJ"), TrafficFileService.READ);
        assertThat(service.get(file.fileNumber(), elsewhere).fileNumber()).isEqualTo(file.fileNumber());
        assertThat(service.findByDocument(new Identity.Passport("AB12345", "GBR"), elsewhere)).isPresent();
        assertThat(service.findByDocument(new Identity.Passport("ZZ99999", "GBR"), elsewhere)).isEmpty();
        assertThat(service.history(file.fileNumber(), elsewhere)).isEmpty();
        assertThat(audit).containsExactly("TRAFFIC_FILE_VIEWED:" + file.fileNumber(),
                "TRAFFIC_FILE_SEARCHED:" + file.fileNumber(), "TRAFFIC_FILE_SEARCHED:-",
                "TRAFFIC_FILE_HISTORY_VIEWED:" + file.fileNumber());
        assertThatThrownBy(() -> service.get(file.fileNumber(), caller(Set.of("*"))))
                .hasFieldOrPropertyWithValue("code", "SCOPE_EXCEEDED");
    }

    // ------------------------------------------------------------------ fakes

    private static RegisterPerson person(boolean deceased) {
        return new RegisterPerson(CITIZEN, new PersonName("أحمد", "المنصوري", "Ahmed", "Al Mansoori"), "ARE",
                LocalDate.of(1990, 4, 12), "MALE", "CITIZEN", null, deceased);
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

    private static final class FakeRegister implements IdentityRegister {
        Optional<RegisterPerson> person = Optional.of(TrafficFileServiceTest.person(false));
        boolean unavailable;
        int calls;

        @Override
        public Optional<RegisterPerson> lookup(String nationalIdNo) {
            calls++;
            if (unavailable) {
                throw new RegisterUnavailableException("down", null);
            }
            return person;
        }
    }

    static final class FakeFiles implements TrafficFileRepository {
        final Map<String, TrafficFile> byNumber = new HashMap<>();
        long sequence;
        boolean failNextInsertAsDuplicate;
        boolean hideNextLookup;

        @Override
        public String nextFileNumber() {
            return "TF%010d".formatted(++sequence);
        }

        @Override
        public void insert(TrafficFile file) {
            if (failNextInsertAsDuplicate) {
                failNextInsertAsDuplicate = false;
                throw new DuplicateIdentityException(null);
            }
            byNumber.put(file.fileNumber(), file);
        }

        @Override
        public Optional<TrafficFile> findByNumber(String fileNumber) {
            return Optional.ofNullable(byNumber.get(fileNumber));
        }

        @Override
        public Optional<TrafficFile> findByNumberForUpdate(String fileNumber) {
            return findByNumber(fileNumber);
        }

        @Override
        public Optional<TrafficFile> findByNationalId(String nationalIdNo) {
            return find(new Identity.NationalId(nationalIdNo));
        }

        @Override
        public Optional<TrafficFile> findByPassport(String passportNo, String passportCountry) {
            return find(new Identity.Passport(passportNo, passportCountry));
        }

        private Optional<TrafficFile> find(Identity.IdentityDocument document) {
            if (hideNextLookup) {
                hideNextLookup = false;
                return Optional.empty();
            }
            return byNumber.values().stream().filter(f -> f.identity().document().equals(document)).findFirst();
        }

        @Override
        public Optional<TrafficFile> findByLicence(String source, String licenceNo) {
            return byNumber.values().stream().filter(f -> f.company() != null
                    && f.company().licence().equals(new gov.itms.trafficfile.domain.Company.Licence(source, licenceNo)))
                    .findFirst();
        }

        @Override
        public List<TrafficFile> findByNumbers(List<String> fileNumbers) {
            return fileNumbers.stream().map(byNumber::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public void saveRepresentatives(TrafficFile file) {
            byNumber.put(file.fileNumber(), file);
        }

        @Override
        public void saveProfile(TrafficFile file) {
            byNumber.put(file.fileNumber(), file);
        }

        @Override
        public List<FileVersion> history(UUID fileId) {
            return List.of();
        }
    }
}
