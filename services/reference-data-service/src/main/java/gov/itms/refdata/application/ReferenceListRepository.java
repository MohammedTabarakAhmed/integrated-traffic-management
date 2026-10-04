package gov.itms.refdata.application;

import gov.itms.refdata.domain.ReferenceList;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for code-list definitions. */
public interface ReferenceListRepository {

    Optional<ReferenceList> find(String code);

    List<ReferenceList> findAll();

    void insert(ReferenceList list, String actorId, UUID changeId, Instant at);
}
