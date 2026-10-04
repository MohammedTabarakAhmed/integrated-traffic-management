package gov.itms.refdata.domain;

/**
 * A code list (CC-110). A list may have a parent list (for example CITY under REGION); its items must then
 * reference a parent item.
 */
public record ReferenceList(String code, BilingualName name, String parentListCode) {

    public ReferenceList {
        Codes.require("listCode", code);
        Codes.optional("parentListCode", parentListCode);
        if (name == null) {
            throw DomainException.validation("NAME_REQUIRED", "name is required");
        }
    }

    public boolean hasParent() {
        return parentListCode != null;
    }
}
