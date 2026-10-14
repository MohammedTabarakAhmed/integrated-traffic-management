package gov.itms.ai.infrastructure;

import gov.itms.ai.application.Ports;
import gov.itms.ai.domain.Models;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory kill switch implementation (AI-014, AI-019).
 * In production, this would be backed by a distributed coordination system (etcd, Consul, etc.).
 */
public class InMemoryKillSwitch implements Ports.KillSwitch {

    private final AtomicReference<State> state = new AtomicReference<>(
            new State(false, null, null, null, Models.KillSwitchScope.USE_CASE)
    );

    @Override
    public State getState() {
        return state.get();
    }

    @Override
    public void activate(String reason, String activatedBy, Models.KillSwitchScope scope) {
        State newState = new State(true, reason, activatedBy, Instant.now(), scope);
        if (!state.compareAndSet(state.get(), newState)) {
            throw new IllegalStateException("Failed to activate kill switch - concurrent modification");
        }
    }

    @Override
    public void deactivate(String deactivatedBy) {
        State current = state.get();
        if (!current.active()) {
            throw new IllegalStateException("Kill switch is not active");
        }
        State newState = new State(false, null, null, null, current.scope());
        if (!state.compareAndSet(current, newState)) {
            throw new IllegalStateException("Failed to deactivate kill switch - concurrent modification");
        }
    }

    @Override
    public boolean isActive(Models.KillSwitchScope scope) {
        State current = state.get();
        if (!current.active()) return false;
        // SERVICE_WIDE covers all scopes
        return current.scope() == Models.KillSwitchScope.SERVICE_WIDE || current.scope() == scope;
    }
}