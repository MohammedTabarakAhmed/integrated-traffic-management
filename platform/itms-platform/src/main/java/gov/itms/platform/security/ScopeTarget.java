package gov.itms.platform.security;

/**
 * The data scope of the resource being acted on (CC-001). A {@code null} dimension means the resource is not
 * scoped by it (for example national reference data has no centre).
 */
public record ScopeTarget(String region, String centre, String module) {

    public static final ScopeTarget UNSCOPED = new ScopeTarget(null, null, null);

    public static ScopeTarget region(String region) {
        return new ScopeTarget(region, null, null);
    }
}
