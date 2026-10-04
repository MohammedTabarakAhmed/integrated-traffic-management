package gov.itms.refdata.api;

import gov.itms.refdata.application.ReferenceItemRepository.ItemVersion;
import gov.itms.refdata.domain.BilingualName;
import gov.itms.refdata.domain.ChangeRequest;
import gov.itms.refdata.domain.ChangeStatus;
import gov.itms.refdata.domain.ChangeType;
import gov.itms.refdata.domain.Codes;
import gov.itms.refdata.domain.ItemData;
import gov.itms.refdata.domain.ReferenceItem;
import gov.itms.refdata.domain.ReferenceList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of {@code reference-data-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record Name(@NotBlank @Size(max = 200) String nl, @NotBlank @Size(max = 200) String en) {
        static Name of(BilingualName n) {
            return n == null ? null : new Name(n.nl(), n.en());
        }

        BilingualName toDomain() {
            return new BilingualName(nl, en);
        }
    }

    record ListDto(String code, Name name, String parentListCode) {
        static ListDto of(ReferenceList l) {
            return new ListDto(l.code(), Name.of(l.name()), l.parentListCode());
        }
    }

    record ItemDto(String listCode, String code, String regionCode, Name name, boolean active, LocalDate effectiveFrom,
                   LocalDate effectiveTo, String parentCode, Map<String, String> externalCodes, int sortOrder,
                   long version) {
        static ItemDto of(ReferenceItem i) {
            ItemData d = i.data();
            return new ItemDto(i.listCode(), i.code(), i.regionCode(), Name.of(d.name()), d.active(), d.effectiveFrom(),
                    d.effectiveTo(), d.parentCode(), d.externalCodes(), d.sortOrder(), i.version());
        }
    }

    record ItemVersionDto(String listCode, String code, String regionCode, Name name, boolean active,
                          LocalDate effectiveFrom, LocalDate effectiveTo, String parentCode,
                          Map<String, String> externalCodes, int sortOrder, long version, Instant validFrom,
                          Instant validTo, String changedBy, UUID changeId) {
        static ItemVersionDto of(ItemVersion v) {
            ItemDto i = ItemDto.of(v.item());
            return new ItemVersionDto(i.listCode(), i.code(), i.regionCode(), i.name(), i.active(), i.effectiveFrom(),
                    i.effectiveTo(), i.parentCode(), i.externalCodes(), i.sortOrder(), i.version(), v.validFrom(),
                    v.validTo(), v.changedBy(), v.changeId());
        }
    }

    record Collection<T>(List<T> items) {
    }

    record SubmitChangeRequest(
            @NotNull ChangeType changeType,
            @NotNull @Pattern(regexp = Codes.PATTERN) String listCode,
            @Pattern(regexp = Codes.PATTERN) String itemCode,
            @Pattern(regexp = Codes.PATTERN) String regionCode,
            @NotNull @Valid Name name,
            @Pattern(regexp = Codes.PATTERN) String parentListCode,
            Boolean active,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Pattern(regexp = Codes.PATTERN) String parentCode,
            Map<String, String> externalCodes,
            @Min(0) @Max(100000) Integer sortOrder,
            @Min(1) Long baseVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {

        ItemData itemData() {
            if (changeType == ChangeType.CREATE_LIST) {
                return null;
            }
            return new ItemData(name.toDomain(), active == null || active, effectiveFrom, effectiveTo, parentCode,
                    externalCodes, sortOrder == null ? 0 : sortOrder);
        }
    }

    record RejectChangeRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record ActorDto(String id, String username) {
        static ActorDto of(ChangeRequest.Actor a) {
            return a == null ? null : new ActorDto(a.id(), a.username());
        }
    }

    record ChangeDto(UUID id, ChangeType changeType, ChangeStatus status, String listCode, String itemCode,
                     String regionCode, Name name, String parentListCode, Boolean active, LocalDate effectiveFrom,
                     LocalDate effectiveTo, String parentCode, Map<String, String> externalCodes, Integer sortOrder,
                     Long baseVersion, String reason, ActorDto maker, Instant submittedAt, ActorDto checker,
                     Instant decidedAt, String decisionReason) {
        static ChangeDto of(ChangeRequest c) {
            ItemData d = c.itemData();
            return new ChangeDto(c.id(), c.type(), c.status(), c.listCode(), c.itemCode(), c.regionCode(),
                    Name.of(d != null ? d.name() : c.listName()), c.parentListCode(),
                    d == null ? null : d.active(), d == null ? null : d.effectiveFrom(),
                    d == null ? null : d.effectiveTo(), d == null ? null : d.parentCode(),
                    d == null ? null : d.externalCodes(), d == null ? null : d.sortOrder(), c.baseVersion(),
                    c.reason(), ActorDto.of(c.maker()), c.submittedAt(), ActorDto.of(c.checker()), c.decidedAt(),
                    c.decisionReason());
        }
    }
}
