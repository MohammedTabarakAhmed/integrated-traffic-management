package gov.itms.iam.application;

import gov.itms.iam.domain.AccessReviewCampaign;
import gov.itms.iam.domain.AccessReviewItem;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for access review campaigns and items (SEC-007). Neither is ever deleted. */
public interface AccessReviewRepository {

    void insertCampaign(AccessReviewCampaign campaign);

    Optional<AccessReviewCampaign> findCampaign(UUID id);

    /** Loads the campaign and locks it against closure until the transaction ends (taken by a decision). */
    Optional<AccessReviewCampaign> findCampaignForShare(UUID id);

    /** Loads and locks the campaign until the transaction ends, so it cannot be closed twice or during a decision. */
    Optional<AccessReviewCampaign> findCampaignForUpdate(UUID id);

    /** Newest first. {@code null} = any status. */
    List<AccessReviewCampaign> searchCampaigns(AccessReviewCampaign.Status status);

    void saveClosure(AccessReviewCampaign campaign);

    /**
     * Inserts the PENDING items, skipping (without error) any whose assignment is already PENDING in another review
     * or is no longer ACTIVE. Returns how many were inserted.
     */
    int insertItemsSkippingPending(List<AccessReviewItem> items);

    Optional<AccessReviewItem> findItem(UUID id);

    /** Loads and locks the item until the transaction ends, so it cannot be decided twice concurrently. */
    Optional<AccessReviewItem> findItemForUpdate(UUID id);

    /** Ordered by campaign (newest first), then subject. Every filter is optional ({@code null} = any). */
    List<AccessReviewItem> searchItems(UUID campaignId, AccessReviewItem.Status status, String subjectId);

    /** Saves the outcome of a PENDING item (a decision, or NOT_REVIEWED / ENDED at closure). */
    void saveItemDecision(AccessReviewItem item);

    /** Number of items of the campaign per status (statuses without items are absent). */
    Map<AccessReviewItem.Status, Integer> counts(UUID campaignId);

    /** When each of the given assignments was last KEPT by a review; assignments never kept are absent. */
    Map<UUID, Instant> lastKeptAt(Collection<UUID> assignmentIds);

    /** The PENDING item of each of the given assignments; assignments with none are absent. */
    Map<UUID, UUID> pendingItemIds(Collection<UUID> assignmentIds);
}
