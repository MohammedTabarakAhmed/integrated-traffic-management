package gov.itms.hold.api;

import gov.itms.hold.application.Caller;
import gov.itms.hold.domain.Actor;
import gov.itms.hold.domain.Channel;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/** The current request's {@link Caller}: actor, channel and the grants that reach a module (CC-001). */
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
            public boolean allows(String permission, String module) {
                return authorisation.allows(permission, new ScopeTarget(null, null, module));
            }

            @Override
            public boolean allowsThroughRole(String permission, List<String> roles, String module) {
                ScopeTarget target = new ScopeTarget(null, null, module);
                return authorisation.grants().stream().anyMatch(g -> roles.contains(g.roleCode())
                        && g.permissions().contains(permission) && g.scopes().allows(target));
            }
        });
    }
}
