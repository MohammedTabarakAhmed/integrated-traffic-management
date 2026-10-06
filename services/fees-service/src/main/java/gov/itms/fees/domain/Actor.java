package gov.itms.fees.domain;

/** Identity of whoever submitted or decided a fee version, or asked for a quote. */
public record Actor(String id, String username) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("actor id required");
        }
    }
}
