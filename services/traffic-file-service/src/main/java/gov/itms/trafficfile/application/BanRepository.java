package gov.itms.trafficfile.application;

import gov.itms.trafficfile.domain.BanEntry;
import gov.itms.trafficfile.domain.Identity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The ban list (DL-013); the database guards the lifecycle and keeps every version (DAT-002). */
public interface BanRepository {

    /**
     * @throws DuplicateSourceRefException if the source already entered this reference
     */
    void insert(BanEntry ban);

    Optional<BanEntry> find(UUID id);

    Optional<BanEntry> findForUpdate(UUID id);

    /** Every entry (any status) for this identity document, newest first. */
    List<BanEntry> findByDocument(Identity.IdentityDocument document);

    void saveLift(BanEntry ban);

    class DuplicateSourceRefException extends RuntimeException {
        public DuplicateSourceRefException(Throwable cause) {
            super("source reference already used", cause);
        }
    }
}
