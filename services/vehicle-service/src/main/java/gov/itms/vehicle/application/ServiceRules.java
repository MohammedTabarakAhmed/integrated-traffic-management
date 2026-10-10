package gov.itms.vehicle.application;

import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configurable rules of the Increment 2 vehicle and plate transactions (ADR-059; NFR-042). Values the BRD gives are
 * defaults here; the others are interim answers recorded in Q-075.
 *
 * @param fees                       fees-service service code per paid transaction kind
 * @param reservationWorkingDays     how long an unpaid number reservation is held (VL-033; BRD default 10 working
 *                                   days)
 * @param weekend                    days that are not working days
 * @param retentionMonths            how long a retained number without a vehicle is kept (VL-039; BRD default 12)
 * @param trialPlateCategory         plate category of trial (dealer) plate series (VL-038)
 * @param trialPlateMaxDays          longest trial plate period (VL-038)
 * @param permitMaxDays              longest temporary entry/exit permit (VL-016)
 * @param requireCustomsCertificate  first registration needs a customs or dealer certificate (VL-010, VL-002)
 * @param approvals                  approvals a vehicle needs before registration, by category or body type
 *                                   (VL-018, VL-019)
 * @param auctionFinancialItem       the financial item an auction price is paid to (VL-034)
 * @param auctionBeneficiary         the beneficiary of an auction price
 * @param currency                   currency of auction prices
 */
public record ServiceRules(Map<Registration.Kind, String> fees, int reservationWorkingDays, Set<DayOfWeek> weekend,
                           int retentionMonths, String trialPlateCategory, int trialPlateMaxDays, int permitMaxDays,
                           boolean requireCustomsCertificate, List<ApprovalRule> approvals,
                           String auctionFinancialItem, String auctionBeneficiary, String currency) {

    /** A vehicle of {@code category} (or, when set, of {@code bodyType}) needs an approved {@code kind}. */
    public record ApprovalRule(String category, String bodyType, Records.ApprovalKind kind) {
    }

    public ServiceRules {
        fees = Map.copyOf(fees);
        weekend = Set.copyOf(weekend);
        approvals = List.copyOf(approvals);
    }

    public String fee(Registration.Kind kind) {
        String code = fees.get(kind);
        if (code == null) {
            throw new IllegalStateException("no fee service code for " + kind);
        }
        return code;
    }

    /** The day {@code workingDays} working days after {@code from} (VL-033). */
    public LocalDate addWorkingDays(LocalDate from, int workingDays) {
        LocalDate d = from;
        int added = 0;
        while (added < workingDays) {
            d = d.plusDays(1);
            if (!weekend.contains(d.getDayOfWeek())) {
                added++;
            }
        }
        return d;
    }

    public List<Records.ApprovalKind> approvalsFor(String category, String bodyType) {
        return approvals.stream().filter(r -> (r.category() != null && r.category().equals(category))
                || (r.bodyType() != null && r.bodyType().equals(bodyType))).map(ApprovalRule::kind).distinct()
                .toList();
    }
}
