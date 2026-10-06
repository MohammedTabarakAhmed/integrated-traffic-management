package gov.itms.trafficfile.api;

import gov.itms.trafficfile.api.ApiModels.Collection;
import gov.itms.trafficfile.api.ApiModels.FileVersionDto;
import gov.itms.trafficfile.api.ApiModels.OpenCompanyRequest;
import gov.itms.trafficfile.api.ApiModels.OpenIndividualRequest;
import gov.itms.trafficfile.api.ApiModels.ProfileUpdateRequest;
import gov.itms.trafficfile.api.ApiModels.RepresentativesUpdateRequest;
import gov.itms.trafficfile.api.ApiModels.SearchRequest;
import gov.itms.trafficfile.api.ApiModels.TrafficFileDto;
import gov.itms.trafficfile.application.Caller;
import gov.itms.trafficfile.application.CompanyFileService;
import gov.itms.trafficfile.application.TrafficFileService;
import gov.itms.trafficfile.domain.TrafficFile;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Traffic files of {@code traffic-file-service.v1.yaml}. {@code @PreAuthorize} checks that the caller holds the
 * permission somewhere; {@link TrafficFileService} checks the region where it matters (CC-001).
 */
@RestController
@RequestMapping("/v1/traffic-files")
class TrafficFileController {

    private final TrafficFileService service;
    private final CompanyFileService companies;
    private final Callers callers;

    TrafficFileController(TrafficFileService service, CompanyFileService companies, Callers callers) {
        this.service = service;
        this.companies = companies;
        this.callers = callers;
    }

    @PostMapping("/companies")
    @PreAuthorize("hasAuthority('trafficfile.create')")
    ResponseEntity<TrafficFileDto> openCompany(@Valid @RequestBody OpenCompanyRequest body) {
        Caller caller = callers.current();
        TrafficFile file = companies.open(body.toDomain(), caller);
        return ResponseEntity.created(URI.create("/v1/traffic-files/" + file.fileNumber()))
                .body(TrafficFileDto.of(file, sensitive(caller)));
    }

    @PostMapping("/{fileNumber}/representatives-updates")
    @PreAuthorize("hasAuthority('trafficfile.update')")
    TrafficFileDto updateRepresentatives(@PathVariable String fileNumber,
                                         @Valid @RequestBody RepresentativesUpdateRequest body) {
        Caller caller = callers.current();
        TrafficFile file = companies.updateRepresentatives(fileNumber, body.version(), body.signatories(),
                body.fleetManagers() == null ? List.of() : body.fleetManagers(), caller);
        return TrafficFileDto.of(file, sensitive(caller));
    }

    @PostMapping("/individuals")
    @PreAuthorize("hasAuthority('trafficfile.create')")
    ResponseEntity<TrafficFileDto> openIndividual(@Valid @RequestBody OpenIndividualRequest body) {
        Caller caller = callers.current();
        TrafficFile file = service.openIndividual(body.toDomain(), caller);
        return ResponseEntity.created(URI.create("/v1/traffic-files/" + file.fileNumber()))
                .body(TrafficFileDto.of(file, sensitive(caller)));
    }

    /** Exact search by identity document (CC-100); a POST so the identity number never appears in a URL or log. */
    @PostMapping("/searches")
    @PreAuthorize("hasAuthority('trafficfile.read')")
    Collection<TrafficFileDto> search(@Valid @RequestBody SearchRequest body) {
        Caller caller = callers.current();
        return new Collection<>(service.findByDocument(body.document().toDomain(), caller).stream()
                .map(f -> TrafficFileDto.of(f, sensitive(caller))).toList());
    }

    @GetMapping("/{fileNumber}")
    @PreAuthorize("hasAuthority('trafficfile.read')")
    TrafficFileDto get(@PathVariable String fileNumber) {
        Caller caller = callers.current();
        return TrafficFileDto.of(service.get(fileNumber, caller), sensitive(caller));
    }

    @GetMapping("/{fileNumber}/history")
    @PreAuthorize("hasAuthority('trafficfile.read')")
    Collection<FileVersionDto> history(@PathVariable String fileNumber) {
        Caller caller = callers.current();
        boolean sensitive = sensitive(caller);
        return new Collection<>(service.history(fileNumber, caller).stream()
                .map(v -> FileVersionDto.of(v, sensitive)).toList());
    }

    @PostMapping("/{fileNumber}/profile-updates")
    @PreAuthorize("hasAuthority('trafficfile.update')")
    TrafficFileDto updateProfile(@PathVariable String fileNumber,
                                 @Valid @RequestBody ProfileUpdateRequest body) {
        Caller caller = callers.current();
        TrafficFile file = service.updateProfile(fileNumber, body.version(), body.profile().toDomain(), caller);
        return TrafficFileDto.of(file, sensitive(caller));
    }

    private static boolean sensitive(Caller caller) {
        return caller.grants().holds(TrafficFileService.SENSITIVE_READ);
    }
}
