package gov.itms.trafficfile.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.trafficfile.application.CommercialRegister.CompanyLicence;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Company;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company traffic files (DL-003, DL-004; ADR-042):
 * <ul>
 *   <li>opening needs {@code trafficfile.create} reaching the region the file is opened in (CC-001). The licence is
 *       read from the issuing authority (ICD-04): unknown, cancelled or expired licences are refused, and its names,
 *       activities and expiry are locked;</li>
 *   <li>one file per licence of an authority: a second answers 409 with the number of the existing file;</li>
 *   <li>signatories (at least one) and fleet managers are individuals' active traffic files; they are replaced as a
 *       whole with the version last read ({@code trafficfile.update});</li>
 *   <li>changes are audited and published through the outbox (CC-120, INT-005).</li>
 * </ul>
 */
public class CompanyFileService {

    static final String EVENT_REPRESENTATIVES_UPDATED = "itms.traffic-file.representatives-updated";

    private final TrafficFileRepository files;
    private final CommercialRegister register;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public CompanyFileService(TrafficFileRepository files, CommercialRegister register, AuditRecorder audit,
                              DomainEventRecorder events, Clock clock) {
        this.files = files;
        this.register = register;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** What staff give to open a company's file. */
    public record OpenCompany(Company.Licence licence, TrafficFile.Classification classification, String regionOpened,
                              List<String> signatories, List<String> fleetManagers) {
    }

    @Transactional
    public TrafficFile open(OpenCompany command, Caller caller) {
        Channel channel = TrafficFileService.channelOf(caller);
        if (!caller.grants().allowsInRegion(TrafficFileService.CREATE, command.regionOpened())) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you can only open files in your own regions", List.of("CC-001"));
        }
        files.findByLicence(command.licence().source(), command.licence().number()).ifPresent(f -> {
            throw exists(f);
        });
        CompanyLicence licence;
        try {
            licence = register.lookup(command.licence().source(), command.licence().number())
                    .orElseThrow(() -> DomainException.validation("COMPANY_LICENCE_NOT_FOUND",
                            "the licensing authority does not know this licence", List.of("DL-003")));
        } catch (CommercialRegister.RegisterUnavailableException e) {
            throw new DomainException(DomainException.Kind.UNAVAILABLE, "LICENSING_AUTHORITY_UNAVAILABLE",
                    "the licensing authority did not answer; try again later", List.of("DL-003", "INT-009"));
        }
        if (licence.cancelled()) {
            throw DomainException.validation("COMPANY_LICENCE_CANCELLED", "the commercial licence is cancelled",
                    List.of("DL-003"));
        }
        Company company = new Company(command.licence(), licence.nameNl(), licence.nameEn(), licence.activities(),
                licence.expiryDate(), command.signatories(), command.fleetManagers());
        requireActiveIndividuals(company.representatives());
        Instant now = clock.instant();
        TrafficFile file = TrafficFile.openCompany(UUID.randomUUID(), files.nextFileNumber(),
                command.classification(), command.regionOpened(), company, now, caller.actor(), channel, now,
                LocalDate.ofInstant(now, clock.getZone()));
        try {
            files.insert(file);
        } catch (TrafficFileRepository.DuplicateIdentityException e) {
            throw files.findByLicence(command.licence().source(), command.licence().number()).map(this::exists)
                    .orElseThrow(() -> e);
        }
        audit.record("TRAFFIC_FILE_OPENED", TrafficFileService.ENTITY, file.fileNumber(), null, snapshot(file), null);
        events.record(TrafficFileService.EVENT_OPENED, file.fileNumber(), TrafficFileService.eventData(file));
        return file;
    }

    @Transactional
    public TrafficFile updateRepresentatives(String fileNumber, long expectedVersion, List<String> signatories,
                                             List<String> fleetManagers, Caller caller) {
        Channel channel = TrafficFileService.channelOf(caller);
        if (!caller.grants().holds(TrafficFileService.UPDATE)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not change traffic files", List.of("CC-001"));
        }
        TrafficFile file = files.findByNumberForUpdate(fileNumber)
                .orElseThrow(() -> TrafficFileService.notFound(fileNumber));
        if (file.version() != expectedVersion) {
            throw DomainException.conflict("VERSION_MISMATCH",
                    "the file changed since you read it (now version " + file.version() + ")", List.of("DAT-002"),
                    file.version());
        }
        Map<String, Object> before = file.kind() == TrafficFile.Kind.COMPANY ? snapshot(file) : null;
        file.updateRepresentatives(signatories, fleetManagers, caller.actor(), channel, clock.instant());
        requireActiveIndividuals(file.company().representatives());
        files.saveRepresentatives(file);
        audit.record("TRAFFIC_FILE_REPRESENTATIVES_UPDATED", TrafficFileService.ENTITY, fileNumber, before,
                snapshot(file), null);
        events.record(EVENT_REPRESENTATIVES_UPDATED, fileNumber, TrafficFileService.eventData(file));
        return file;
    }

    /** Signatories and fleet managers must be existing, active individuals' files (DL-004). */
    private void requireActiveIndividuals(List<String> numbers) {
        Set<String> valid = files.findByNumbers(numbers).stream()
                .filter(f -> f.kind() == TrafficFile.Kind.INDIVIDUAL && f.isActive())
                .map(TrafficFile::fileNumber).collect(Collectors.toSet());
        List<String> invalid = new ArrayList<>(numbers);
        invalid.removeAll(valid);
        if (!invalid.isEmpty()) {
            throw DomainException.validation("REPRESENTATIVE_INVALID",
                    "these are not active individuals' traffic files: " + invalid, List.of("DL-004"),
                    String.join(", ", invalid));
        }
    }

    private DomainException exists(TrafficFile file) {
        return DomainException.conflict("FILE_ALREADY_EXISTS",
                "this commercial licence already has traffic file " + file.fileNumber(), List.of("DL-003", "DL-009"),
                file.fileNumber());
    }

    private static Map<String, Object> snapshot(TrafficFile file) {
        Map<String, Object> m = new LinkedHashMap<>(TrafficFileService.eventData(file));
        Company c = file.company();
        m.put("licenceSource", c.licence().source());
        m.put("commercialLicenceNo", c.licence().number());
        m.put("licenceExpiry", c.licenceExpiry().toString());
        m.put("activities", c.activities());
        m.put("signatories", c.signatories());
        m.put("fleetManagers", c.fleetManagers());
        return m;
    }
}
