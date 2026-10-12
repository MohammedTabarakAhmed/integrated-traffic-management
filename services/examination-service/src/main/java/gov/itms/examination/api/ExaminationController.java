package gov.itms.examination.api;

import gov.itms.examination.application.ExaminationService;
import gov.itms.examination.application.Ports;
import gov.itms.examination.domain.DomainException;
import gov.itms.examination.domain.Exams;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * examination-service.v1.yaml. {@code @PreAuthorize} checks the permission is held somewhere;
 * {@link ExaminationService} checks it reaches the record's region (CC-001).
 */
@RestController
class ExaminationController {

    private static final String CODE = "^[A-Z0-9_]{1,40}$";
    private final ExaminationService service;
    private final CurrentActorProvider actors;

    ExaminationController(ExaminationService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    private Ports.Access access() {
        String user = actors.current().userId();
        var authorisation = actors.authorisation();
        return new Ports.Access() {
            @Override
            public String userId() {
                return user;
            }

            @Override
            public boolean allows(String permission, String region) {
                return authorisation.allows(permission, ScopeTarget.region(region));
            }
        };
    }

    record Items<T>(List<T> items) {
    }

    // ------------------------------------------------------------------ settings (DL-043)

    record SheetItemBody(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 300) String textNl,
                         @NotBlank @Size(max = 300) String textEn, @Min(0) @Max(1000) int penalty,
                         boolean critical) {
    }

    record TestTypeBody(@NotNull Exams.TestKind kind, @NotBlank @Size(max = 200) String nameNl,
                        @NotBlank @Size(max = 200) String nameEn, @Min(1) @Max(1000) int maxScore,
                        @Min(1) @Max(1000) int passMark, @Min(0) @Max(365) int retestWaitDays,
                        @Pattern(regexp = CODE) String feeServiceCode, @Min(0) @Max(200) int questionCount,
                        @Size(max = 100) List<@Valid SheetItemBody> sheet, boolean active) {
    }

    @PutMapping("/v1/test-types/{code}")
    @PreAuthorize("hasAuthority('exam.settings.manage')")
    Exams.TestType saveTestType(@PathVariable @Pattern(regexp = CODE) String code,
                                @Valid @RequestBody TestTypeBody b) {
        List<Exams.SheetItem> sheet = b.sheet() == null ? List.of() : b.sheet().stream()
                .map(i -> new Exams.SheetItem(i.code(), i.textNl(), i.textEn(), i.penalty(), i.critical())).toList();
        return service.saveTestType(new Exams.TestType(code, b.kind(), b.nameNl(), b.nameEn(), b.maxScore(),
                b.passMark(), b.retestWaitDays(), b.feeServiceCode(), b.questionCount(), sheet, b.active()),
                access());
    }

    record CertificateTypeBody(@NotBlank @Size(max = 200) String nameNl, @NotBlank @Size(max = 200) String nameEn,
                               @Min(1) @Max(500) int hours) {
    }

    @PutMapping("/v1/training-certificate-types/{code}")
    @PreAuthorize("hasAuthority('exam.settings.manage')")
    Exams.CertificateType saveCertificateType(@PathVariable @Pattern(regexp = CODE) String code,
                                              @Valid @RequestBody CertificateTypeBody b) {
        return service.saveCertificateType(new Exams.CertificateType(code, b.nameNl(), b.nameEn(), b.hours()),
                access());
    }

    record QuestionBody(@NotBlank @Pattern(regexp = "^[a-z]{2}$") String language,
                        @NotBlank @Pattern(regexp = CODE) String category, @Min(1) @Max(100) int weight,
                        @NotBlank @Size(max = 2000) String text,
                        @NotEmpty @Size(min = 2, max = 6) List<@NotBlank @Size(max = 500) String> options,
                        @Min(0) @Max(5) int correctOption) {
    }

    /** The created question; the correct option is not echoed back. */
    record QuestionCreated(UUID id, String testTypeCode, String language, String category, int weight) {
    }

    @PostMapping("/v1/test-types/{code}/questions")
    @PreAuthorize("hasAuthority('exam.settings.manage')")
    ResponseEntity<QuestionCreated> addQuestion(@PathVariable @Pattern(regexp = CODE) String code,
                                                @Valid @RequestBody QuestionBody b) {
        Exams.Question q = service.addQuestion(code, b.language(), b.category(), b.weight(), b.text(), b.options(),
                b.correctOption(), access());
        return ResponseEntity.created(URI.create("/v1/test-types/" + code + "/questions/" + q.id()))
                .body(new QuestionCreated(q.id(), code, q.language(), q.category(), q.weight()));
    }

    // ------------------------------------------------------------------ schools (DL-044, DL-045)

    record SchoolBody(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String nameNl,
                      @NotBlank @Size(max = 200) String nameEn, @NotBlank @Pattern(regexp = CODE) String region) {
    }

    @PostMapping("/v1/driving-schools")
    @PreAuthorize("hasAuthority('exam.school.manage')")
    ResponseEntity<Exams.School> registerSchool(@Valid @RequestBody SchoolBody b) {
        Exams.School s = service.registerSchool(b.code(), b.nameNl(), b.nameEn(), b.region(), access());
        return ResponseEntity.created(URI.create("/v1/driving-schools/" + s.id())).body(s);
    }

    record BranchBody(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 500) String address,
                      @NotBlank @Pattern(regexp = CODE) String region) {
    }

    @PostMapping("/v1/driving-schools/{id}/branches")
    @PreAuthorize("hasAuthority('exam.school.manage')")
    ResponseEntity<Exams.Branch> addBranch(@PathVariable UUID id, @Valid @RequestBody BranchBody b) {
        Exams.Branch branch = service.addBranch(id, b.code(), b.address(), b.region(), access());
        return ResponseEntity.created(URI.create("/v1/driving-schools/" + id + "/branches/" + branch.id()))
                .body(branch);
    }

    record VehicleBody(@NotBlank @Size(max = 40) String plate, @NotBlank @Pattern(regexp = CODE) String classCode,
                       boolean dualControls) {
    }

    @PostMapping("/v1/driving-schools/{id}/vehicles")
    @PreAuthorize("hasAuthority('exam.school.manage')")
    ResponseEntity<Exams.SchoolVehicle> addVehicle(@PathVariable UUID id, @Valid @RequestBody VehicleBody b) {
        Exams.SchoolVehicle v = service.addVehicle(id, b.plate(), b.classCode(), b.dualControls(), access());
        return ResponseEntity.created(URI.create("/v1/driving-schools/" + id + "/vehicles/" + v.id())).body(v);
    }

    record InstructorBody(@Size(max = 100) String userId, @NotBlank @Size(max = 40) String trafficFileNumber,
                          @NotBlank @Pattern(regexp = CODE) String grade,
                          @NotEmpty @Size(max = 20) Set<@Pattern(regexp = CODE) String> classes) {
    }

    @PostMapping("/v1/driving-schools/{id}/instructors")
    @PreAuthorize("hasAuthority('exam.school.manage')")
    ResponseEntity<Exams.Instructor> addInstructor(@PathVariable UUID id, @Valid @RequestBody InstructorBody b) {
        Exams.Instructor i = service.addInstructor(id, b.userId(), b.trafficFileNumber(), b.grade(), b.classes(),
                access());
        return ResponseEntity.created(URI.create("/v1/driving-schools/" + id + "/instructors/" + i.id())).body(i);
    }

    record ComplaintBody(UUID instructorId, @NotBlank @Size(max = 1000) String summary) {
    }

    record Created(UUID id) {
    }

    @PostMapping("/v1/driving-schools/{id}/complaints")
    @PreAuthorize("hasAuthority('exam.school.manage')")
    ResponseEntity<Created> complaint(@PathVariable UUID id, @Valid @RequestBody ComplaintBody b) {
        UUID c = service.recordComplaint(id, b.instructorId(), b.summary(), access());
        return ResponseEntity.created(URI.create("/v1/driving-schools/" + id + "/complaints/" + c))
                .body(new Created(c));
    }

    @GetMapping("/v1/driving-schools/{id}/rating")
    @PreAuthorize("hasAuthority('exam.school.read')")
    Exams.Rating rating(@PathVariable UUID id, @RequestParam(required = false) UUID instructorId) {
        return service.rating(id, instructorId, access());
    }

    // ------------------------------------------------------------------ learning files (DL-030..DL-032)

    record FileBody(@NotBlank @Size(max = 40) String trafficFileNumber,
                    @NotBlank @Pattern(regexp = CODE) String classCode,
                    @NotBlank @Pattern(regexp = "^(MANUAL|AUTOMATIC)$") String gearbox,
                    @NotBlank @Pattern(regexp = CODE) String region, UUID schoolId, UUID instructorId) {
    }

    @PostMapping("/v1/learning-files")
    @PreAuthorize("hasAuthority('exam.file.manage')")
    ResponseEntity<Exams.LearningFile> openFile(@Valid @RequestBody FileBody b) {
        Exams.LearningFile f = service.openFile(b.trafficFileNumber(), b.classCode(), b.gearbox(), b.region(),
                b.schoolId(), b.instructorId(), access());
        return ResponseEntity.created(URI.create("/v1/learning-files/" + f.id())).body(f);
    }

    @GetMapping("/v1/learning-files/{id}")
    @PreAuthorize("hasAuthority('exam.file.read')")
    Exams.LearningFile file(@PathVariable UUID id) {
        return service.file(id, access());
    }

    record MoveBody(@NotBlank @Pattern(regexp = "^(start-training|ready-for-test|cancel)$") String action,
                    @Size(max = 500) String reason) {
    }

    @PostMapping("/v1/learning-files/{id}/transitions")
    @PreAuthorize("hasAuthority('exam.file.manage')")
    Exams.LearningFile move(@PathVariable UUID id, @Valid @RequestBody MoveBody b) {
        return service.move(id, b.action(), b.reason(), access());
    }

    record TrainingBody(UUID instructorId, @Min(1) @Max(12) int hours, @Pattern(regexp = CODE) String certificateType,
                        @NotNull LocalDate heldOn) {
    }

    @PostMapping("/v1/learning-files/{id}/training-sessions")
    @PreAuthorize("hasAuthority('exam.file.manage')")
    Exams.LearningFile training(@PathVariable UUID id, @Valid @RequestBody TrainingBody b) {
        return service.recordTraining(id, b.instructorId(), b.hours(), b.certificateType(), b.heldOn(), access());
    }

    @GetMapping("/v1/learning-files/{id}/results")
    @PreAuthorize("hasAuthority('exam.file.read')")
    Items<Ports.Result> results(@PathVariable UUID id) {
        return new Items<>(service.results(id, access()));
    }

    record TransferView(String status, int attempts, String licensingTransferId) {
    }

    @GetMapping("/v1/learning-files/{id}/licensing-transfer")
    @PreAuthorize("hasAuthority('exam.file.read')")
    TransferView transfer(@PathVariable UUID id) {
        Ports.Transfer t = service.transfer(id, access()).orElseThrow(() -> DomainException.missing(
                "TRANSFER_NOT_FOUND", "the learning file has not passed yet", "DL-036"));
        return new TransferView(t.status(), t.attempts(), t.licensingTransferId());
    }

    // ------------------------------------------------------------------ committees, calendar (DL-040..DL-042)

    record MemberBody(@NotBlank @Size(max = 100) String userId, @NotNull Exams.MemberRole role) {
    }

    record CommitteeBody(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 80) String location,
                         @NotBlank @Pattern(regexp = CODE) String region, @NotNull LocalDate validFrom,
                         @NotNull LocalDate validTo, @NotEmpty @Size(max = 50) List<@Valid MemberBody> members) {
    }

    @PostMapping("/v1/committees")
    @PreAuthorize("hasAuthority('exam.committee.manage')")
    ResponseEntity<Exams.Committee> committee(@Valid @RequestBody CommitteeBody b) {
        Exams.Committee c = service.createCommittee(b.code(), b.location(), b.region(), b.validFrom(), b.validTo(),
                b.members().stream().map(m -> new Exams.Member(m.userId(), m.role())).toList(), access());
        return ResponseEntity.created(URI.create("/v1/committees/" + c.id())).body(c);
    }

    record SlotBody(@NotBlank @Pattern(regexp = CODE) String classCode,
                    @NotBlank @Pattern(regexp = CODE) String testTypeCode, @NotNull Instant startsAt,
                    @Min(1) @Max(500) int capacity) {
    }

    @PostMapping("/v1/committees/{id}/slots")
    @PreAuthorize("hasAuthority('exam.calendar.manage')")
    ResponseEntity<Exams.Slot> slot(@PathVariable UUID id, @Valid @RequestBody SlotBody b) {
        Exams.Slot s = service.createSlot(id, b.classCode(), b.testTypeCode(), b.startsAt(), b.capacity(), access());
        return ResponseEntity.created(URI.create("/v1/committees/" + id + "/slots/" + s.id())).body(s);
    }

    @GetMapping("/v1/calendar-usage")
    @PreAuthorize("hasAuthority('exam.calendar.read')")
    Items<Ports.SlotUsage> usage(@RequestParam @Size(max = 80) String location,
                                 @RequestParam @Pattern(regexp = CODE) String region, @RequestParam Instant from,
                                 @RequestParam Instant to) {
        return new Items<>(service.usage(location, region, from, to, access()));
    }

    // ------------------------------------------------------------------ bookings and tests (DL-032..DL-038)

    record BookingBody(@NotNull UUID slotId) {
    }

    @PostMapping("/v1/learning-files/{id}/bookings")
    @PreAuthorize("hasAuthority('exam.booking.manage')")
    ResponseEntity<Exams.Booking> book(@PathVariable UUID id, @Valid @RequestBody BookingBody b) {
        Exams.Booking booking = service.book(id, b.slotId(), access());
        return ResponseEntity.created(URI.create("/v1/bookings/" + booking.id())).body(booking);
    }

    /** A paper question without its answer. */
    record PaperQuestion(UUID id, String category, String text, List<String> options) {
    }

    @GetMapping("/v1/bookings/{id}/paper")
    @PreAuthorize("hasAuthority('exam.test.conduct')")
    Items<PaperQuestion> paper(@PathVariable UUID id, @RequestParam @Pattern(regexp = "^[a-z]{2}$") String language) {
        return new Items<>(service.paper(id, language, access()).stream()
                .map(q -> new PaperQuestion(q.id(), q.category(), q.text(), q.options())).toList());
    }

    record TheoryBody(@NotBlank @Pattern(regexp = "^[a-z]{2}$") String language,
                      @NotNull @Size(max = 200) Map<UUID, @Min(0) @Max(5) Integer> answers) {
    }

    @PostMapping("/v1/bookings/{id}/theory-answers")
    @PreAuthorize("hasAuthority('exam.test.conduct')")
    Ports.Result theory(@PathVariable UUID id, @Valid @RequestBody TheoryBody b) {
        return service.submitTheory(id, b.language(), b.answers(), access());
    }

    record PointBody(@Min(-90) @Max(90) double lat, @Min(-180) @Max(180) double lon, @NotNull Instant at) {
    }

    record PracticalBody(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{8,80}$") String clientResultId,
                         @NotNull @Size(max = 100) List<@Pattern(regexp = CODE) String> faults,
                         @Size(max = 5000) List<@Valid PointBody> route, @NotNull Instant recordedAt,
                         UUID videoDocumentId) {
    }

    @PostMapping("/v1/bookings/{id}/practical-results")
    @PreAuthorize("hasAuthority('exam.result.record')")
    Ports.Result practical(@PathVariable UUID id, @Valid @RequestBody PracticalBody b) {
        List<Exams.RoutePoint> route = b.route() == null ? List.of() : b.route().stream()
                .map(p -> new Exams.RoutePoint(p.lat(), p.lon(), p.at())).toList();
        return service.recordPractical(id, b.clientResultId(), b.faults(), route, b.recordedAt(),
                b.videoDocumentId(), access());
    }

    // ------------------------------------------------------------------ exceptions (DL-037)

    @GetMapping("/v1/result-exceptions")
    @PreAuthorize("hasAuthority('exam.exception.decide')")
    Items<Ports.ExceptionItem> exceptions(@RequestParam @Pattern(regexp = CODE) String region) {
        return new Items<>(service.openExceptions(region, access()));
    }

    record DecisionBody(boolean accept, @NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/v1/result-exceptions/{id}/decisions")
    @PreAuthorize("hasAuthority('exam.exception.decide')")
    Ports.ExceptionItem decide(@PathVariable UUID id, @Valid @RequestBody DecisionBody b) {
        return service.decide(id, b.accept(), b.reason(), access());
    }
}
