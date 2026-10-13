package gov.itms.migration.application;

import gov.itms.migration.domain.MigrationModels;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class Ports {
    private Ports() { }

    public interface LegacySystemRepository {
        Optional<MigrationModels.LegacySystem> findSystem(String code);
        List<MigrationModels.LegacySystem> findAllSystems();
        void save(MigrationModels.LegacySystem system);
        void delete(String code);
    }

    public interface EntityMappingRepository {
        List<MigrationModels.EntityMapping> findByLegacySystem(String legacySystemCode);
        void save(MigrationModels.EntityMapping mapping);
        void delete(UUID id);
    }

    public interface MigrationJobRepository {
        Optional<MigrationModels.MigrationJob> findJob(String code);
        List<MigrationModels.MigrationJob> findAllJobs();
        void save(MigrationModels.MigrationJob job);
        void update(MigrationModels.MigrationJob job);
    }

    public interface MigrationExecutionRepository {
        void save(MigrationModels.MigrationExecution exec);
        List<MigrationModels.MigrationExecution> findExecutionsByJob(UUID jobId);
        Optional<MigrationModels.MigrationExecution> findById(UUID id);
    }

    public interface ReconciliationRepository {
        void save(MigrationModels.ReconciliationReport report);
        List<MigrationModels.ReconciliationReport> findReportsByJob(UUID jobId);
        Optional<MigrationModels.ReconciliationReport> findLatestByJobAndEntity(UUID jobId, String entityName);
    }

    public interface ExceptionRepository {
        void save(MigrationModels.ExceptionRecord exception);
        List<MigrationModels.ExceptionRecord> findExceptionsByJob(UUID jobId);
        List<MigrationModels.ExceptionRecord> findUnresolved(UUID jobId);
        void update(MigrationModels.ExceptionRecord exception);
    }

    public interface RollbackPlanRepository {
        Optional<MigrationModels.RollbackPlan> findByJobCode(String jobCode);
        void save(MigrationModels.RollbackPlan plan);
    }

    public interface AnonymizationRepository {
        List<MigrationModels.AnonymizationRule> findByEntity(String entityName);
        void save(MigrationModels.AnonymizationRule rule);
    }

    public interface DataAccessPort {
        List<Map<String, Object>> queryLegacy(String systemCode, String query, Object... params);
        long countLegacy(String systemCode, String table, String whereClause, Object... params);
        void executeTarget(String sql, Object... params);
        long countTarget(String table, String whereClause, Object... params);
    }
}