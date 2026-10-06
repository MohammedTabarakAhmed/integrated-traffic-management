package gov.itms.workflow.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Small records kept beside work items. */
public final class WorkRecords {

    private WorkRecords() {
    }

    /**
     * An internal note on a case (CC-064), with optional attachments (document ids of document-service). Notes are
     * never shown to the customer: no customer-facing operation returns them.
     */
    public record Note(UUID id, UUID itemId, String text, List<String> attachmentIds, Actor author, Instant at) {
        public Note {
            if (text == null || text.isBlank() || text.length() > 4000
                    || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw DomainException.validation("NOTE_INVALID", "a note is 1 to 4000 characters", List.of("CC-064"));
            }
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            if (attachmentIds.size() > 20 || attachmentIds.stream().anyMatch(a -> a == null
                    || !a.matches("^[A-Za-z0-9-]{1,64}$"))) {
                throw DomainException.validation("ATTACHMENTS_INVALID", "give at most 20 document ids",
                        List.of("CC-064"));
            }
        }
    }

    /** One entry of a case's timeline. */
    public record HistoryEntry(UUID itemId, String action, String stepCode, String actorId, Instant at,
                               Map<String, Object> detail) {
    }

    /**
     * A user available for automatic assignment in the queues of one role (CC-061), within the regions and centres of
     * their own grant. Users join and leave themselves; the scopes come from their grants, never from the request.
     */
    public record QueueMember(String userId, String username, String role, Set<String> regions, Set<String> centres,
                              boolean available, Instant lastAssignedAt, int openItems) {
        public QueueMember {
            regions = Set.copyOf(regions);
            centres = Set.copyOf(centres);
        }

        public boolean reaches(String region, String centre) {
            return (regions.contains("*") || regions.contains(region))
                    && (centre == null || centres.contains("*") || centres.contains(centre));
        }
    }
}
