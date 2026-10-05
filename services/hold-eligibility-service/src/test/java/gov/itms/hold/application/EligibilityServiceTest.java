package gov.itms.hold.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.hold.domain.Actor;
import gov.itms.hold.domain.Channel;
import gov.itms.hold.domain.DomainException;
import gov.itms.hold.domain.Eligibility;
import gov.itms.hold.domain.EligibilityPolicy;
import gov.itms.hold.domain.EligibilityPolicy.Block;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.Subject;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-032")
class EligibilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Actor OFFICER = new Actor("u-officer", "officer");
    private static final Subject VEHICLE = new Subject(Subject.Type.VEHICLE, "VIN-1");
    private static final Subject FILE = new Subject(Subject.Type.TRAFFIC_FILE, "TF-1");

    private final HoldServiceTest.Holds holds = new HoldServiceTest.Holds();
    private final List<String> audit = new ArrayList<>();
    private final List<Object> auditValues = new ArrayList<>();
    private final EligibilityService service = new EligibilityService(holds,
            new EligibilityPolicy(Map.of("UNPAID_FINES", "PAY_FINES")), new AuditRecorder() {
                @Override
                public void record(String action, String entityType, String entityId, Object oldValue,
                                   Object newValue, String reason) {
                    audit.add(action + " " + entityType + " " + entityId);
                    auditValues.add(newValue);
                }

                @Override
                public void recordAs(CurrentActor actor, String action, String entityType, String entityId,
                                     Object oldValue, Object newValue, String reason) {
                    record(action, entityType, entityId, oldValue, newValue, reason);
                }
            }, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Caller caller(boolean mayCheck) {
        return new Caller(OFFICER, Channel.PORTAL, new Caller.Grants() {
            @Override
            public boolean allows(String permission, String module) {
                return mayCheck && EligibilityService.CHECK.equals(permission) && module == null;
            }

            @Override
            public boolean allowsThroughRole(String permission, List<String> roles, String module) {
                return false;
            }
        });
    }

    private Hold place(Subject subject, String type, String module) {
        Hold hold = Hold.place(UUID.randomUUID(), new Hold.Spec(subject, type, module, module, null, "reason", null,
                null, List.of("*"), List.of("HOLD_OFFICER")), OFFICER, Channel.BACKOFFICE, NOW, TODAY);
        holds.insert(hold);
        return hold;
    }

    @Test
    @DisplayName("CC-032/CC-001: holds of every module on every subject count, without hold.read in the module")
    void checksEverySubjectWhateverTheModule() {
        Hold fines = place(FILE, "UNPAID_FINES", "VIOLATIONS");
        Hold circular = place(VEHICLE, "POLICE_CIRCULAR", "POLICE");
        place(new Subject(Subject.Type.VEHICLE, "VIN-OTHER"), "COURT_ORDER", "COURTS");

        Eligibility result = service.check("VL_RENEW", List.of(VEHICLE, FILE), caller(true));

        assertThat(result.eligible()).isFalse();
        assertThat(result.blocks()).containsExactly(new Block("RESTRICTED", "CONTACT_AUTHORITY"),
                new Block("UNPAID_FINES", "PAY_FINES"));
        assertThat(result.blockingHolds()).containsExactly(circular.id(), fines.id());
        assertThat(service.check("VL_RENEW", List.of(new Subject(Subject.Type.PLATE, "P-1")), caller(true))
                .eligible()).isTrue();
    }

    @Test
    @Tag("CC-120")
    @DisplayName("CC-120: each subject checked is audited with its own result and blocking holds")
    void eachSubjectIsAudited() {
        Hold fines = place(FILE, "UNPAID_FINES", "VIOLATIONS");
        service.check("VL_RENEW", List.of(VEHICLE, FILE), caller(true));
        assertThat(audit).containsExactly("ELIGIBILITY_CHECKED SubjectEligibility VEHICLE:VIN-1",
                "ELIGIBILITY_CHECKED SubjectEligibility TRAFFIC_FILE:TF-1");
        assertThat(auditValues.get(0)).isEqualTo(Map.of("serviceCode", "VL_RENEW", "checkedOn", "2026-10-05",
                "eligible", true, "blockingHolds", List.of()));
        assertThat(auditValues.get(1)).isEqualTo(Map.of("serviceCode", "VL_RENEW", "checkedOn", "2026-10-05",
                "eligible", false, "blockingHolds", List.of(fines.id().toString())));
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: without eligibility.check nothing is read or audited")
    void needsThePermission() {
        place(FILE, "UNPAID_FINES", "VIOLATIONS");
        assertThatThrownBy(() -> service.check("VL_RENEW", List.of(FILE), caller(false)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("SCOPE_EXCEEDED"));
        assertThat(audit).isEmpty();
    }
}
