package gov.itms.trafficfile.domain;

/** Identity of whoever opened or changed a traffic file. */
public record Actor(String id, String username) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("actor id required");
        }
    }
}
