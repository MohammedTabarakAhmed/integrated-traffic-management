package gov.itms.platform.security;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * Effective authorisation of a subject at one moment (CC-001, ADR-022). Grants are kept separate: a permission held
 * in one scope is never widened by another assignment's scope.
 *
 * @param subjectId   identity-provider subject
 * @param grants      one entry per active role assignment
 * @param evaluatedAt when the owning service computed the grants
 */
public record Authorisation(String subjectId, List<Grant> grants, Instant evaluatedAt) {

    /**
     * Highest amount a role may approve for one kind of approval in one currency (CC-003), in minor units.
     *
     * @param limitType      for example {@code REFUND_AMOUNT} or {@code FINE_REDUCTION_AMOUNT}
     * @param maxAmountMinor the limit, &ge; 0
     * @param currency       ISO 4217 code
     */
    public record ApprovalLimit(String limitType, long maxAmountMinor, String currency) {
    }

    /**
     * Permissions and approval limits of one role, held within one set of scopes until {@code validTo}
     * ({@code null} = open).
     */
    public record Grant(String roleCode, Set<String> permissions, Scopes scopes, Instant validTo,
                        List<ApprovalLimit> approvalLimits) {
        public Grant {
            permissions = Set.copyOf(permissions);
            approvalLimits = approvalLimits == null ? List.of() : List.copyOf(approvalLimits);
        }

        /** A grant without approval limits. */
        public Grant(String roleCode, Set<String> permissions, Scopes scopes, Instant validTo) {
            this(roleCode, permissions, scopes, validTo, List.of());
        }
    }

    public Authorisation {
        grants = List.copyOf(grants);
    }

    /** Every permission held in at least one scope; used for coarse {@code @PreAuthorize} checks. */
    public Set<String> permissions() {
        Set<String> all = new TreeSet<>();
        grants.forEach(g -> all.addAll(g.permissions()));
        return all;
    }

    /** Role codes of the active grants, recorded in audit (CC-120). */
    public List<String> roles() {
        return grants.stream().map(Grant::roleCode).distinct().sorted().toList();
    }

    /** True if some grant holds {@code permission} and reaches {@code target}. */
    public boolean allows(String permission, ScopeTarget target) {
        return grants.stream().anyMatch(g -> g.permissions().contains(permission) && g.scopes().allows(target));
    }

    /** True if some single grant holds {@code permission} with scopes covering all of {@code requested}. */
    public boolean covers(String permission, Scopes requested) {
        return grants.stream().anyMatch(g -> g.permissions().contains(permission) && g.scopes().covers(requested));
    }

    /**
     * The subject's approval limit for {@code limitType} in {@code currency} on {@code target} (CC-003): the highest
     * limit among the grants that reach the target. Empty means no authority to approve. Limits of a grant never
     * apply outside its scopes, and amounts in different currencies are never compared.
     */
    public OptionalLong approvalLimit(String limitType, String currency, ScopeTarget target) {
        return grants.stream()
                .filter(g -> g.scopes().allows(target))
                .flatMap(g -> g.approvalLimits().stream())
                .filter(l -> l.limitType().equals(limitType) && l.currency().equals(currency))
                .mapToLong(ApprovalLimit::maxAmountMinor)
                .max();
    }
}
