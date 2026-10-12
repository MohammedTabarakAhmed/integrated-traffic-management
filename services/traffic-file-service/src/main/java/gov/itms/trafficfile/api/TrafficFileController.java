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
import gov.itms.trafficfile.application.DuplicateMergeService;
import gov.itms.trafficfile.application.NameIndex;
import gov.itms.trafficfile.application.NameSearchService;
import gov.itms.trafficfile.application.TrafficFileService;
import gov.itms.trafficfile.domain.TrafficFile;
import jakarta.validation.Valid;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Codes;
import gov.itms.trafficfile.domain.NameQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
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
    private final DuplicateMergeService merges;
    private final NameSearchService names;
    private final Callers callers;

    TrafficFileController(TrafficFileService service, CompanyFileService companies, DuplicateMergeService merges,
                          NameSearchService names, Callers callers) {
        this.service = service;
        this.companies = companies;
        this.merges = merges;
        this.names = names;
        this.callers = callers;
    }

    /** A name search (CC-101); {@code limit} defaults to the configured most. */
    record NameSearchRequest(@NotBlank @Size(max = 100) String name, TrafficFile.Kind kind,
                             @Min(1) @Max(100) Integer limit) {
    }

    record NameHitDto(String fileNumber, TrafficFile.Kind kind, TrafficFile.Status status, String regionOpened,
                      NameQuery.Match match, String nameNl, String nameEn) {
        static NameHitDto of(NameIndex.Hit h) {
            return new NameHitDto(h.fileNumber(), h.kind(), h.status(), h.regionOpened(), h.match(), h.nameNl(),
                    h.nameEn());
        }
    }

    /**
     * Files of people and companies by name (CC-101): partial, either language, transliterated, phonetic; best match
     * first. A POST so that the name never appears in a URL or access log; audited (CC-102).
     */
    @PostMapping("/name-searches")
    @PreAuthorize("hasAuthority('trafficfile.read')")
    Collection<NameHitDto> searchByName(@Valid @RequestBody NameSearchRequest body) {
        NameQuery query = new NameQuery(body.name(), body.kind(), body.limit() == null ? 100 : body.limit());
        return new Collection<>(names.search(query, callers.current()).stream().map(NameHitDto::of).toList());
    }

    record CandidateDto(TrafficFileDto file, double nameSimilarity) {
    }

    record MergeRequest(@NotNull @Pattern(regexp = Codes.FILE_NUMBER) String mergedFileNumber,
                        @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record MergeDto(UUID id, String mergedFileNumber, String survivingFileNumber, String reason, String actorId,
                    Channel channel, Instant at) {
        static MergeDto of(DuplicateMergeService.Merge m) {
            return new MergeDto(m.id(), m.mergedFileNumber(), m.survivingFileNumber(), m.reason(), m.actorId(),
                    m.channel(), m.at());
        }
    }

    /** Probable duplicates of an individual's file (DL-009), most similar first. */
    @GetMapping("/{fileNumber}/probable-duplicates")
    @PreAuthorize("hasAuthority('trafficfile.read')")
    Collection<CandidateDto> probableDuplicates(@PathVariable String fileNumber) {
        Caller caller = callers.current();
        boolean sensitive = sensitive(caller);
        return new Collection<>(merges.probableDuplicates(fileNumber, caller).stream()
                .map(c -> new CandidateDto(TrafficFileDto.of(c.file(), sensitive),
                        Math.round(c.nameSimilarity() * 1000) / 1000.0))
                .toList());
    }

    /** Merges another file into this one (DL-009); this file survives. */
    @PostMapping("/{fileNumber}/merges")
    @PreAuthorize("hasAuthority('trafficfile.merge')")
    ResponseEntity<MergeDto> merge(@PathVariable String fileNumber, @Valid @RequestBody MergeRequest body) {
        DuplicateMergeService.Merge merge = merges.merge(fileNumber, body.mergedFileNumber(), body.reason(),
                callers.current());
        return ResponseEntity.created(URI.create("/v1/traffic-files/" + body.mergedFileNumber()))
                .body(MergeDto.of(merge));
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
