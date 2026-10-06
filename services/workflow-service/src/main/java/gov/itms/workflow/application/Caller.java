package gov.itms.workflow.application;

import gov.itms.workflow.domain.Actor;
import gov.itms.workflow.domain.Channel;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Who calls a work item command, through which channel ({@code null} when the request named no known channel), and
 * what their grants allow (CC-001 data scopes). Queues are role + region + service centre (CC-061), so the grants are
 * also asked per role.
 */
public record Caller(Actor actor, Channel channel, Grants grants, String correlationId) {

    /** One grant as a queue scope: its role, and the regions and centres it reaches ({@code *} = all). */
    public record RoleScope(String role, Set<String> regions, Set<String> centres) {
        public RoleScope {
            regions = Set.copyOf(regions);
            centres = Set.copyOf(centres);
        }

        public boolean reaches(String region, String centre) {
            return (regions.contains("*") || regions.contains(region))
                    && (centre == null || centres.contains("*") || centres.contains(centre));
        }
    }

    public interface Grants {

        /** True if some grant holds {@code permission} and reaches the region and centre ({@code null} = any). */
        boolean allowsAt(String permission, String region, String centre);

        /** True if some grant, of any scope, holds {@code permission}. */
        boolean holds(String permission);

        /** The caller's grants that hold {@code permission}, as role scopes. */
        List<RoleScope> roleScopes(String permission);
    }

    public Caller {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(grants);
    }

    /** True if the caller works in the queue of {@code role} for the region and centre (CC-061). */
    public boolean worksIn(String permission, String role, String region, String centre) {
        return grants.roleScopes(permission).stream().anyMatch(s -> s.role().equals(role) && s.reaches(region, centre));
    }
}
