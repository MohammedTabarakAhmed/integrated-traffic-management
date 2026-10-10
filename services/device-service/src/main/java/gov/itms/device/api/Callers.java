package gov.itms.device.api;

import gov.itms.device.application.Caller;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import org.springframework.stereotype.Component;

/** Resolves the authenticated user and IAM grants for device-service operations. */
@Component
class Callers {

    private final CurrentActorProvider actors;

    Callers(CurrentActorProvider actors) {
        this.actors = actors;
    }

    Caller current() {
        var actor = actors.current();
        Authorisation auth = actors.authorisation();
        return new Caller(actor.userId(), actor.username(), new Caller.Grants() {
            @Override
            public boolean allows(String permission, String region) {
                return auth.allows(permission, new ScopeTarget(region, null, null));
            }

            @Override
            public boolean holds(String permission) {
                return auth.permissions().contains(permission);
            }
        });
    }
}
