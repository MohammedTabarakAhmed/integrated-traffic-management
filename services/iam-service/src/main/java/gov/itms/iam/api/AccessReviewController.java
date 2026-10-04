package gov.itms.iam.api;

import gov.itms.iam.api.ApiModels.AccessReviewDecisionRequest;
import gov.itms.iam.api.ApiModels.AccessReviewDto;
import gov.itms.iam.api.ApiModels.AccessReviewItemDto;
import gov.itms.iam.api.ApiModels.AccessReviewRequest;
import gov.itms.iam.api.ApiModels.Collection;
import gov.itms.iam.api.ApiModels.OverdueAssignmentsDto;
import gov.itms.iam.api.ApiModels.ReasonRequest;
import gov.itms.iam.application.AccessReviewService;
import gov.itms.iam.domain.AccessReviewCampaign;
import gov.itms.iam.domain.AccessReviewItem;
import gov.itms.iam.domain.Actor;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Access reviews of {@code iam-service.v1.yaml} (SEC-007, ADR-026). Permissions follow {@code x-itms-permission}. */
@RestController
@RequestMapping("/v1")
class AccessReviewController {

    private final AccessReviewService service;
    private final CurrentActorProvider actors;

    AccessReviewController(AccessReviewService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/access-reviews")
    @PreAuthorize("hasAuthority('iam.review.read')")
    Collection<AccessReviewDto> reviews(@RequestParam(required = false) AccessReviewCampaign.Status status) {
        return new Collection<>(service.reviews(status, actors.authorisation()).stream()
                .map(AccessReviewDto::of).toList());
    }

    @PostMapping("/access-reviews")
    @PreAuthorize("hasAuthority('iam.review.manage')")
    ResponseEntity<AccessReviewDto> open(@Valid @RequestBody AccessReviewRequest body) {
        AccessReviewService.Review review = service.open(body.title(), body.scopes().toDomain(), body.dueAt(),
                body.reason(), actor(), actors.authorisation());
        return ResponseEntity.created(URI.create("/v1/access-reviews/" + review.campaign().id()))
                .body(AccessReviewDto.of(review));
    }

    @GetMapping("/access-reviews/overdue-assignments")
    @PreAuthorize("hasAuthority('iam.review.read')")
    OverdueAssignmentsDto overdueAssignments() {
        return OverdueAssignmentsDto.of(service.overdueAssignments(actors.authorisation()));
    }

    @GetMapping("/access-reviews/{reviewId}")
    @PreAuthorize("hasAuthority('iam.review.read')")
    AccessReviewDto review(@PathVariable UUID reviewId) {
        return AccessReviewDto.of(service.review(reviewId, actors.authorisation()));
    }

    @PostMapping("/access-reviews/{reviewId}/closures")
    @PreAuthorize("hasAuthority('iam.review.manage')")
    AccessReviewDto close(@PathVariable UUID reviewId, @Valid @RequestBody ReasonRequest body) {
        return AccessReviewDto.of(service.close(reviewId, body.reason(), actor(), actors.authorisation()));
    }

    @GetMapping("/access-review-items")
    @PreAuthorize("hasAuthority('iam.review.read')")
    Collection<AccessReviewItemDto> items(@RequestParam(required = false) UUID campaignId,
                                          @RequestParam(required = false) AccessReviewItem.Status status,
                                          @RequestParam(required = false) String subjectId) {
        return new Collection<>(service.items(campaignId, status, subjectId, actors.authorisation()).stream()
                .map(AccessReviewItemDto::of).toList());
    }

    @PostMapping("/access-review-items/{itemId}/decisions")
    @PreAuthorize("hasAuthority('iam.review.decide')")
    AccessReviewItemDto decide(@PathVariable UUID itemId, @Valid @RequestBody AccessReviewDecisionRequest body) {
        return AccessReviewItemDto.of(service.decide(itemId, body.decision(), body.reason(), actor(),
                actors.authorisation()));
    }

    private Actor actor() {
        CurrentActor a = actors.current();
        return new Actor(a.userId(), a.username());
    }
}
