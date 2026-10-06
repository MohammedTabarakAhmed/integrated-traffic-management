package gov.itms.workflow.api;

import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.workflow.application.Caller;
import gov.itms.workflow.domain.Actor;
import gov.itms.workflow.domain.Channel;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/** The current request's {@link Caller}: actor, channel and grants (CC-001). */
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
            public boolean allowsAt(String permission, String region, String centre) {
                return authorisation.allows(permission, new ScopeTarget(region, centre, null));
            }

            @Override
            public boolean holds(String permission) {
                return authorisation.grants().stream().anyMatch(g -> g.permissions().contains(permission));
            }

            @Override
            public List<Caller.RoleScope> roleScopes(String permission) {
                return authorisation.grants().stream().filter(g -> g.permissions().contains(permission))
                        .map(g -> new Caller.RoleScope(g.roleCode(), g.scopes().regions(), g.scopes().centres()))
                        .toList();
            }
        }, current.correlationId());
    }
}
