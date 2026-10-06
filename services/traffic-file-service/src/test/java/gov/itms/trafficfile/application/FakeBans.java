package gov.itms.trafficfile.application;

import gov.itms.trafficfile.domain.BanEntry;
import gov.itms.trafficfile.domain.Identity;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link BanRepository} for unit tests. */
final class FakeBans implements BanRepository {

    final Map<UUID, BanEntry> byId = new LinkedHashMap<>();

    @Override
    public void insert(BanEntry ban) {
        boolean duplicate = ban.spec().sourceRef() != null && byId.values().stream()
                .anyMatch(b -> b.spec().source().equals(ban.spec().source())
                        && Objects.equals(b.spec().sourceRef(), ban.spec().sourceRef()));
        if (duplicate) {
            throw new DuplicateSourceRefException(null);
        }
        byId.put(ban.id(), ban);
    }

    @Override
    public Optional<BanEntry> find(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<BanEntry> findForUpdate(UUID id) {
        return find(id);
    }

    @Override
    public List<BanEntry> findByDocument(Identity.IdentityDocument document) {
        return byId.values().stream().filter(b -> b.spec().person().equals(document))
                .sorted(Comparator.comparing((BanEntry b) -> b.placed().at()).reversed()).toList();
    }

    @Override
    public void saveLift(BanEntry ban) {
        byId.put(ban.id(), ban);
    }
}
