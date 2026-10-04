package gov.itms.iam.domain;

/** Who performed an administrative action (CC-120). */
public record Actor(String id, String username) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("actor id required");
        }
    }
}
