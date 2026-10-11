package gov.itms.search.api;

import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.search.application.Ports;
import gov.itms.search.application.SearchService;
import gov.itms.search.domain.DomainException;
import gov.itms.search.domain.Search;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /v1/searches}: one search bar across the records of CC-100. */
@RestController
class SearchController {

    private final SearchService search;
    private final CurrentActorProvider actors;

    SearchController(SearchService search, CurrentActorProvider actors) {
        this.search = search;
        this.actors = actors;
    }

    record SearchRequest(@NotNull @Size(max = 200) String query, @Size(max = 6) Set<Search.EntityType> types) {
    }

    record HitDto(Search.EntityType type, String id, String reference, String region, Map<String, String> fields) {
        static HitDto of(Search.Hit h) {
            return new HitDto(h.type(), h.id(), h.reference(), h.region(), h.fields());
        }
    }

    record SourceDto(String name, Search.EntityType type, Search.SourceStatus status) {
    }

    record ResultDto(String searchId, List<HitDto> items, List<SourceDto> sources) {
    }

    @PostMapping("/v1/searches")
    @PreAuthorize("hasAuthority('search.execute')")
    ResultDto search(@Valid @RequestBody SearchRequest body) {
        CurrentActor actor = actors.current();
        if (!CurrentActor.CHANNELS.contains(actor.channel())) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CHANNEL_REQUIRED",
                    "the request does not name its channel (X-Channel)", List.of("CC-120"));
        }
        Search.Result r = search.search(new Search.Query(body.query(), body.types()),
                new Ports.Caller(actor.userId(), actor.channel(), bearerToken()));
        return new ResultDto(r.searchId(), r.items().stream().map(HitDto::of).toList(),
                r.sources().stream().map(s -> new SourceDto(s.name(), s.type(), s.status())).toList());
    }

    /** The caller's own token: the owning services check it themselves (no service account widens the reach). */
    private static String bearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new DomainException(DomainException.Kind.FORBIDDEN, "TOKEN_REQUIRED",
                "searching needs the caller's own bearer token", List.of("CC-102"));
    }
}
