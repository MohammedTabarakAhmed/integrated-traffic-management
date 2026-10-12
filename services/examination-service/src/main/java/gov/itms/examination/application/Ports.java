package gov.itms.examination.application;

import gov.itms.examination.domain.Exams;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** What the examination use cases need from storage and from licensing-service. */
public final class Ports {

    private Ports() {
    }

    /** Who calls and which of their grants reach a region (CC-001). */
    public interface Access {
        String userId();

        boolean allows(String permission, String region);
    }

    /** A recorded result as the use cases see it. */
    public record Result(UUID id, UUID bookingId, UUID fileId, String testTypeCode, String examinerId,
                         String clientResultId, int score, boolean passed, List<String> faults,
                         Instant recordedAt, boolean firstAttempt, List<String> exceptions) {
        public Result {
            faults = List.copyOf(faults);
            exceptions = List.copyOf(exceptions);
        }
    }

    public record ExceptionItem(UUID id, UUID resultId, String reason, String status, Instant raisedAt,
                                String decidedBy, String decisionReason) {
    }

    public record SlotUsage(UUID slotId, String location, String classCode, String testTypeCode, Instant startsAt,
                            int capacity, int booked, int sat, int noShows) {
    }

    public record Transfer(UUID fileId, String status, int attempts, String licensingTransferId) {
    }

    public interface Repository {
        // settings, schools (DL-043, DL-044)
        void saveTestType(Exams.TestType type, String actor, Instant at);

        Optional<Exams.TestType> testType(String code);

        void saveCertificateType(Exams.CertificateType type);

        Optional<Exams.CertificateType> certificateType(String code);

        void insertSchool(Exams.School school, String actor, Instant at);

        Optional<Exams.School> school(UUID id);

        void insertBranch(Exams.Branch branch);

        void insertVehicle(Exams.SchoolVehicle vehicle);

        void insertInstructor(Exams.Instructor instructor);

        Optional<Exams.Instructor> instructor(UUID id);

        void insertComplaint(UUID id, UUID schoolId, UUID instructorId, String summary, Instant at, String actor);

        /** Learners, first attempts, first passes and complaints of a school, or of one instructor (DL-045). */
        Exams.Rating rating(UUID schoolId, UUID instructorId);

        // learning files (DL-030..DL-032)
        String nextFileNumber(int year);

        void insertFile(Exams.LearningFile file, String actor);

        Optional<Exams.LearningFile> file(UUID id);

        Optional<Exams.LearningFile> fileForUpdate(UUID id);

        void updateFile(Exams.LearningFile file, String reason, String actor, Instant at, boolean statusChanged);

        void insertTraining(UUID id, UUID fileId, UUID instructorId, int hours, String certificateType,
                            LocalDate heldOn, Instant at, String actor);

        List<Exams.LearningFile> stale(Instant before, int limit);

        // committees, calendar (DL-040..DL-042)
        void insertCommittee(Exams.Committee committee, String actor, Instant at);

        Optional<Exams.Committee> committee(UUID id);

        void insertSlot(Exams.Slot slot, String actor);

        Optional<Exams.Slot> slotForUpdate(UUID id);

        List<SlotUsage> usage(String location, Instant from, Instant to);

        // bookings, papers, results (DL-033..DL-037)
        void insertBooking(Exams.Booking booking, Long paperSeed, String actor);

        Optional<Exams.Booking> booking(UUID id);

        Optional<Long> paperSeed(UUID bookingId);

        void markBooking(UUID id, String status);

        Map<String, Integer> pairings(List<String> examiners, UUID instructorId);

        Map<String, Integer> load(List<String> examiners, LocalDate day);

        Optional<Instant> lastFailure(UUID fileId, String testTypeCode);

        List<Exams.Question> bank(String testTypeCode, String language);

        void insertQuestion(Exams.Question question, String actor, Instant at);

        Optional<Result> resultByClientId(String clientResultId);

        boolean anyResult(UUID fileId, String testTypeCode);

        void insertResult(Result result, Map<UUID, Integer> answers, List<Exams.RoutePoint> route,
                          UUID videoDocumentId, Instant receivedAt);

        void insertException(UUID id, UUID resultId, String reason, Instant at);

        Optional<ExceptionItem> exceptionForUpdate(UUID id);

        void decideException(UUID id, String status, String actor, String reason, Instant at);

        List<ExceptionItem> openExceptions(String region, int limit);

        /** Test types with at least one passing result whose exceptions are all accepted. */
        List<String> cleanPasses(UUID fileId);

        List<Result> results(UUID fileId);

        UUID resultFile(UUID resultId);

        // DL-036 hand-off
        void insertTransfer(UUID fileId, Instant at);

        List<Transfer> pendingTransfers(int limit);

        void transferSent(UUID fileId, String licensingTransferId, Instant at);

        void transferFailed(UUID fileId, String error);

        Optional<Transfer> transfer(UUID fileId);
    }

    /** licensing-service {@code POST /v1/examination-transfers} with this service's own account (DL-036). */
    public interface Licensing {
        /** @return licensing's transfer id */
        String transfer(String examinationFileRef, String trafficFileNumber, String classCode, String gearbox,
                        LocalDate passedOn, String region);
    }
}
