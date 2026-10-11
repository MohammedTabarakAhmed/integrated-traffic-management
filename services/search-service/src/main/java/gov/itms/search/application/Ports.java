package gov.itms.search.application;

import gov.itms.search.domain.Search;
import java.util.List;

/** What search-service needs from the services that own the records. */
public final class Ports {

    private Ports() {
    }

    /** Who searches: the user, the channel and the user's own bearer token, which the owning services check. */
    public record Caller(String actorId, String channel, String bearerToken) {
        @Override
        public String toString() {
            return "Caller[" + actorId + ", " + channel + "]";
        }
    }

    /**
     * One owning service's lookup for one record type. It is asked with the caller's token, so that service applies
     * its own permissions, data scope (CC-001, CC-102) and audit of the read.
     */
    public interface Source {

        String name();

        Search.EntityType type();

        /** Whether the query has the shape of this source's identifier. */
        boolean accepts(String query);

        /**
         * The records matching the query; none when the owning service knows no such record.
         *
         * @throws Forbidden   the caller may not read such records there
         * @throws Unavailable the owning service did not answer in time or answered with an error
         */
        List<Search.Hit> find(String query, Caller caller);
    }

    /** The owning service refused the caller (401 or 403). */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    /** The owning service could not be asked (timeout, refused connection, 5xx or an unreadable answer). */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
