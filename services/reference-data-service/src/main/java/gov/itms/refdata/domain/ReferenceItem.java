package gov.itms.refdata.domain;

import java.util.UUID;

/**
 * A code in a list. {@code regionCode == null} is the national item; a non-null region code is a regional variant
 * that overrides the national item in that region (CC-112). {@code version} supports optimistic concurrency and the
 * "base version" check of maker-checker updates.
 */
public record ReferenceItem(UUID id, String listCode, String code, String regionCode, ItemData data, long version) {

    public ReferenceItem {
        Codes.require("listCode", listCode);
        Codes.require("itemCode", code);
        Codes.optional("regionCode", regionCode);
    }

    public ReferenceItem withData(ItemData newData) {
        return new ReferenceItem(id, listCode, code, regionCode, newData, version + 1);
    }
}
