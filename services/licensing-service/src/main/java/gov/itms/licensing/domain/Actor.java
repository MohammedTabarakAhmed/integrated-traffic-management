package gov.itms.licensing.domain;

/** Identity of whoever submitted or decided a rule version, or asked for a decision. */
public record Actor(String id, String username) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("actor id required");
        }
    }
}
