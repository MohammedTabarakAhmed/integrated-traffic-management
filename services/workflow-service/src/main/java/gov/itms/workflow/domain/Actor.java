package gov.itms.workflow.domain;

/** Identity of whoever acts: a staff user, a customer or a service account. */
public record Actor(String id, String username) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("actor id required");
        }
    }
}
