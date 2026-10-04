package gov.itms.iam.domain;

/**
 * A permission declared by a service's API ({@code x-itms-permission}) and registered in the catalogue. Roles may
 * only contain catalogue permissions (CC-005).
 */
public record Permission(String code, String service, BilingualName description) {

    public Permission {
        Codes.permissionCode(code);
    }
}
