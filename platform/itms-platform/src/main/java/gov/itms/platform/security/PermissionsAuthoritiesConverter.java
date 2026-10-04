package gov.itms.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps the token's permission claim to authorities (CC-001). Permissions are administered as data in the identity
 * layer, not in code (CC-005). Values that are not well-formed permission strings are ignored.
 */
public class PermissionsAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Pattern PERMISSION = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*){1,4}$");

    private final String claim;

    public PermissionsAuthoritiesConverter(String claim) {
        this.claim = claim;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> values = jwt.getClaimAsStringList(claim);
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> PERMISSION.matcher(v).matches())
                .distinct()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }
}
