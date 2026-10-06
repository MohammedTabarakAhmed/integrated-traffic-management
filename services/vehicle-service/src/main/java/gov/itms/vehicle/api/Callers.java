package gov.itms.vehicle.api;

import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.vehicle.application.Caller;
import gov.itms.vehicle.domain.Actor;
import gov.itms.vehicle.domain.Channel;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** The current request's {@link Caller}: actor, channel and the grants that reach a region (CC-001). */
@Component
class Callers {

    private final CurrentActorProvider actors;

    Callers(CurrentActorProvider actors) {
        this.actors = actors;
    }

    /** The channel comes from {@code X-Channel}; anything else than a channel of {@link Channel} counts as none. */
    Caller current() {
        CurrentActor current = actors.current();
        Authorisation authorisation = actors.authorisation();
        Channel channel = Arrays.stream(Channel.values()).filter(c -> c.name().equals(current.channel())).findFirst()
                .orElse(null);
        return new Caller(new Actor(current.userId(), current.username()), channel, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return authorisation.allows(permission, new ScopeTarget(region, null, null));
            }

            @Override
            public boolean holds(String permission) {
                return authorisation.grants().stream().anyMatch(g -> g.permissions().contains(permission));
            }
        }, current.correlationId());
    }
}
