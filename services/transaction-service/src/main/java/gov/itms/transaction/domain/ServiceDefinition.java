package gov.itms.transaction.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A service in the catalogue (CC-020) and its version. Every approved change creates the next version; the code never
 * changes and nothing is deleted (DAT-002).
 */
public final class ServiceDefinition {

    private final ServiceSpec spec;
    private final long version;
    private final UUID lastChangeId;
    private final Instant updatedAt;
    private final Actor updatedBy;

    public ServiceDefinition(ServiceSpec spec, long version, UUID lastChangeId, Instant updatedAt, Actor updatedBy) {
        this.spec = Objects.requireNonNull(spec);
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1");
        }
        this.version = version;
        this.lastChangeId = Objects.requireNonNull(lastChangeId);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.updatedBy = Objects.requireNonNull(updatedBy);
    }

    /** Version 1, from an approved CREATE_SERVICE change. */
    public static ServiceDefinition create(ServiceSpec spec, UUID changeId, Actor checker, Instant now) {
        return new ServiceDefinition(spec, 1, changeId, now, checker);
    }

    /** The next version, from an approved UPDATE_SERVICE change. The code cannot change. */
    public ServiceDefinition update(ServiceSpec next, UUID changeId, Actor checker, Instant now) {
        if (!next.code().equals(spec.code())) {
            throw DomainException.validation("SERVICE_CODE_IMMUTABLE", "the code of a service cannot change");
        }
        return new ServiceDefinition(next, version + 1, changeId, now, checker);
    }

    public String code() { return spec.code(); }
    public ServiceSpec spec() { return spec; }
    public long version() { return version; }
    public UUID lastChangeId() { return lastChangeId; }
    public Instant updatedAt() { return updatedAt; }
    public Actor updatedBy() { return updatedBy; }
}
