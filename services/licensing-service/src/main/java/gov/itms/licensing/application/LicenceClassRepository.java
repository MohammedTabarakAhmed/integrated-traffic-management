package gov.itms.licensing.application;

import gov.itms.licensing.domain.LicenceClassVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Licence class versions (DL-020, DL-062). */
public interface LicenceClassRepository {

    int nextVersionNo(String classCode);

    /** Serialises changes to one class. */
    void lockClass(String classCode);

    void insert(LicenceClassVersion version);

    Optional<LicenceClassVersion> find(UUID id);

    Optional<LicenceClassVersion> findForUpdate(UUID id);

    /** Every version of a class, oldest first. */
    List<LicenceClassVersion> byClass(String classCode);

    /** Every approved version of every class, by class code then version. */
    List<LicenceClassVersion> approved();

    void saveDecision(LicenceClassVersion version);

    void saveEnd(LicenceClassVersion version);
}
