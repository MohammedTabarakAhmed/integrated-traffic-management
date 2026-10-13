package gov.itms.migration.infrastructure;

import gov.itms.migration.application.Ports;
import gov.itms.migration.domain.MigrationModels;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMigrationRepository implements
        Ports.LegacySystemRepository, Ports.EntityMappingRepository,
        Ports.MigrationJobRepository, Ports.MigrationExecutionRepository,
        Ports.ReconciliationRepository, Ports.ExceptionRepository,
        Ports.RollbackPlanRepository, Ports.AnonymizationRepository {
    private final JdbcClient jdbc;

    public JdbcMigrationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // LegacySystem
    @Override
    public Optional<MigrationModels.LegacySystem> findSystem(String code) {
        return jdbc.sql("select * from legacy_system where code = :code")
                .param("code", code)
                .query(this::mapLegacySystem)
                .optional();
    }

    @Override
    public List<MigrationModels.LegacySystem> findAllSystems() {
        return jdbc.sql("select * from legacy_system order by code")
                .query(this::mapLegacySystem)
                .list();
    }

    @Override
    public void save(MigrationModels.LegacySystem system) {
        jdbc.sql("""
            insert into legacy_system (id, code, name_en, name_ar, description_en, description_ar,
                connection_config, entity_mappings, active, created_at, updated_at)
            values (:id, :code, :nameEn, :nameAr, :descEn, :descAr, :config, :mappings, :active, :created, :updated)
            on conflict (code) do update set
                name_en = :nameEn, name_ar = :nameAr, description_en = :descEn, description_ar = :descAr,
                connection_config = :config, entity_mappings = :mappings, active = :active, updated_at = :updated
            """)
                .param("id", system.id())
                .param("code", system.code())
                .param("nameEn", system.nameEn())
                .param("nameAr", system.nameAr())
                .param("descEn", system.descriptionEn())
                .param("descAr", system.descriptionAr())
                .param("config", system.connectionConfig())
                .param("mappings", system.entityMappings())
                .param("active", system.active())
                .param("created", system.createdAt())
                .param("updated", system.updatedAt())
                .update();
    }

    @Override
    public void delete(String code) {
        jdbc.sql("delete from legacy_system where code = :code").param("code", code).update();
    }

    // EntityMapping
    @Override
    public List<MigrationModels.EntityMapping> findByLegacySystem(String legacySystemCode) {
        return jdbc.sql("select * from entity_mapping where legacy_system_code = :code order by legacy_entity_name")
                .param("code", legacySystemCode)
                .query(this::mapEntityMapping)
                .list();
    }

    @Override
    public void save(MigrationModels.EntityMapping mapping) {
        jdbc.sql("""
            insert into entity_mapping (id, legacy_system_code, legacy_entity_name, target_entity_name,
                field_mappings, transformation_rules, validation_rules, active, created_at, updated_at)
            values (:id, :system, :legacyEntity, :targetEntity, :fields, :transform, :validation, :active, :created, :updated)
            on conflict (id) do update set
                legacy_entity_name = :legacyEntity, target_entity_name = :targetEntity,
                field_mappings = :fields, transformation_rules = :transform, validation_rules = :validation,
                active = :active, updated_at = :updated
            """)
                .param("id", mapping.id())
                .param("system", mapping.legacySystemCode())
                .param("legacyEntity", mapping.legacyEntityName())
                .param("targetEntity", mapping.targetEntityName())
                .param("fields", mapping.fieldMappings())
                .param("transform", mapping.transformationRules())
                .param("validation", mapping.validationRules())
                .param("active", mapping.active())
                .param("created", mapping.createdAt())
                .param("updated", mapping.updatedAt())
                .update();
    }

    @Override
    public void delete(UUID id) {
        jdbc.sql("delete from entity_mapping where id = :id").param("id", id).update();
    }

    // MigrationJob
    @Override
    public Optional<MigrationModels.MigrationJob> findJob(String code) {
        return jdbc.sql("select * from migration_job where code = :code")
                .param("code", code)
                .query(this::mapMigrationJob)
                .optional();
    }

    @Override
    public List<MigrationModels.MigrationJob> findAllJobs() {
        return jdbc.sql("select * from migration_job order by code")
                .query(this::mapMigrationJob)
                .list();
    }

    @Override
    public void save(MigrationModels.MigrationJob job) {
        jdbc.sql("""
            insert into migration_job (id, code, name_en, name_ar, legacy_system_code, entity_mappings,
                status, scheduled_at, started_at, completed_at, total_records, processed_records,
                successful_records, failed_records, error_summary, created_at, updated_at)
            values (:id, :code, :nameEn, :nameAr, :system, :entities, :status, :scheduled, :started, :completed,
                :total, :processed, :successful, :failed, :error, :created, :updated)
            on conflict (code) do update set
                name_en = :nameEn, name_ar = :nameAr, legacy_system_code = :system, entity_mappings = :entities,
                status = :status, scheduled_at = :scheduled, started_at = :started, completed_at = :completed,
                total_records = :total, processed_records = :processed, successful_records = :successful,
                failed_records = :failed, error_summary = :error, updated_at = :updated
            """)
                .param("id", job.id())
                .param("code", job.code())
                .param("nameEn", job.nameEn())
                .param("nameAr", job.nameAr())
                .param("system", job.legacySystemCode())
                .param("entities", String.join(",", job.entityMappings()))
                .param("status", job.status())
                .param("scheduled", job.scheduledAt())
                .param("started", job.startedAt())
                .param("completed", job.completedAt())
                .param("total", job.totalRecords())
                .param("processed", job.processedRecords())
                .param("successful", job.successfulRecords())
                .param("failed", job.failedRecords())
                .param("error", job.errorSummary())
                .param("created", job.createdAt())
                .param("updated", job.updatedAt())
                .update();
    }

    @Override
    public void update(MigrationModels.MigrationJob job) {
        save(job);
    }

    // MigrationExecution
    @Override
    public void save(MigrationModels.MigrationExecution exec) {
        jdbc.sql("""
            insert into migration_execution (id, job_id, entity_name, status, total_records, processed_records,
                successful_records, failed_records, started_at, completed_at, error_details)
            values (:id, :jobId, :entity, :status, :total, :processed, :successful, :failed, :started, :completed, :error)
            on conflict (id) do update set
                status = :status, total_records = :total, processed_records = :processed,
                successful_records = :successful, failed_records = :failed,
                started_at = :started, completed_at = :completed, error_details = :error
            """)
                .param("id", exec.id())
                .param("jobId", exec.jobId())
                .param("entity", exec.entityName())
                .param("status", exec.status())
                .param("total", exec.totalRecords())
                .param("processed", exec.processedRecords())
                .param("successful", exec.successfulRecords())
                .param("failed", exec.failedRecords())
                .param("started", exec.startedAt())
                .param("completed", exec.completedAt())
                .param("error", exec.errorDetails())
                .update();
    }

    @Override
    public List<MigrationModels.MigrationExecution> findExecutionsByJob(UUID jobId) {
        return jdbc.sql("select * from migration_execution where job_id = :jobId order by entity_name")
                .param("jobId", jobId)
                .query(this::mapMigrationExecution)
                .list();
    }

    @Override
    public Optional<MigrationModels.MigrationExecution> findById(UUID id) {
        return jdbc.sql("select * from migration_execution where id = :id")
                .param("id", id)
                .query(this::mapMigrationExecution)
                .optional();
    }

    // ReconciliationReport
    @Override
    public void save(MigrationModels.ReconciliationReport report) {
        jdbc.sql("""
            insert into reconciliation_report (id, job_id, entity_name, source_count, target_count,
                matched_count, mismatched_count, missing_in_target, extra_in_target, match_percentage,
                financial_reconciliation, generated_at, status)
            values (:id, :jobId, :entity, :source, :target, :matched, :mismatched, :missing, :extra,
                :percentage, :financial, :generated, :status)
            on conflict (id) do update set
                source_count = :source, target_count = :target, matched_count = :matched,
                mismatched_count = :mismatched, missing_in_target = :missing, extra_in_target = :extra,
                match_percentage = :percentage, financial_reconciliation = :financial,
                generated_at = :generated, status = :status
            """)
                .param("id", report.id())
                .param("jobId", report.jobId())
                .param("entity", report.entityName())
                .param("source", report.sourceCount())
                .param("target", report.targetCount())
                .param("matched", report.matchedCount())
                .param("mismatched", report.mismatchedCount())
                .param("missing", report.missingInTarget())
                .param("extra", report.extraInTarget())
                .param("percentage", report.matchPercentage())
                .param("financial", report.financialReconciliation())
                .param("generated", report.generatedAt())
                .param("status", report.status())
                .update();
    }

    @Override
    public List<MigrationModels.ReconciliationReport> findReportsByJob(UUID jobId) {
        return jdbc.sql("select * from reconciliation_report where job_id = :jobId order by generated_at desc")
                .param("jobId", jobId)
                .query(this::mapReconciliationReport)
                .list();
    }

    @Override
    public Optional<MigrationModels.ReconciliationReport> findLatestByJobAndEntity(UUID jobId, String entityName) {
        return jdbc.sql("""
            select * from reconciliation_report
            where job_id = :jobId and entity_name = :entity
            order by generated_at desc limit 1
            """)
                .param("jobId", jobId)
                .param("entity", entityName)
                .query(this::mapReconciliationReport)
                .optional();
    }

    // ExceptionRecord
    @Override
    public void save(MigrationModels.ExceptionRecord exception) {
        jdbc.sql("""
            insert into exception_record (id, job_id, execution_id, entity_name, legacy_id, legacy_data,
                target_id, error_code, error_message, severity, assigned_to, resolution, created_at, resolved_at)
            values (:id, :jobId, :execId, :entity, :legacyId, :legacyData, :targetId, :errorCode,
                :errorMsg, :severity, :assigned, :resolution, :created, :resolved)
            on conflict (id) do update set
                target_id = :targetId, error_code = :errorCode, error_message = :errorMsg,
                severity = :severity, assigned_to = :assigned, resolution = :resolution, resolved_at = :resolved
            """)
                .param("id", exception.id())
                .param("jobId", exception.jobId())
                .param("execId", exception.executionId())
                .param("entity", exception.entityName())
                .param("legacyId", exception.legacyId())
                .param("legacyData", exception.legacyData())
                .param("targetId", exception.targetId())
                .param("errorCode", exception.errorCode())
                .param("errorMsg", exception.errorMessage())
                .param("severity", exception.severity())
                .param("assigned", exception.assignedTo())
                .param("resolution", exception.resolution())
                .param("created", exception.createdAt())
                .param("resolved", exception.resolvedAt())
                .update();
    }

    @Override
    public List<MigrationModels.ExceptionRecord> findExceptionsByJob(UUID jobId) {
        return jdbc.sql("select * from exception_record where job_id = :jobId order by created_at desc")
                .param("jobId", jobId)
                .query(this::mapExceptionRecord)
                .list();
    }

    @Override
    public List<MigrationModels.ExceptionRecord> findUnresolved(UUID jobId) {
        return jdbc.sql("""
            select * from exception_record where job_id = :jobId and resolved_at is null
            order by severity desc, created_at desc
            """)
                .param("jobId", jobId)
                .query(this::mapExceptionRecord)
                .list();
    }

    @Override
    public void update(MigrationModels.ExceptionRecord exception) {
        save(exception);
    }

    // RollbackPlan
    @Override
    public Optional<MigrationModels.RollbackPlan> findByJobCode(String jobCode) {
        return jdbc.sql("select * from rollback_plan where job_code = :code")
                .param("code", jobCode)
                .query(this::mapRollbackPlan)
                .optional();
    }

    @Override
    public void save(MigrationModels.RollbackPlan plan) {
        jdbc.sql("""
            insert into rollback_plan (id, job_code, name_en, name_ar, description_en, description_ar,
                rollback_steps, validation_queries, tested, tested_at, created_at, updated_at)
            values (:id, :jobCode, :nameEn, :nameAr, :descEn, :descAr, :steps, :queries, :tested, :testedAt, :created, :updated)
            on conflict (job_code) do update set
                name_en = :nameEn, name_ar = :nameAr, description_en = :descEn, description_ar = :descAr,
                rollback_steps = :steps, validation_queries = :queries, tested = :tested, tested_at = :testedAt,
                updated_at = :updated
            """)
                .param("id", plan.id())
                .param("jobCode", plan.jobCode())
                .param("nameEn", plan.nameEn())
                .param("nameAr", plan.nameAr())
                .param("descEn", plan.descriptionEn())
                .param("descAr", plan.descriptionAr())
                .param("steps", plan.rollbackSteps())
                .param("queries", plan.validationQueries())
                .param("tested", plan.tested())
                .param("testedAt", plan.testedAt())
                .param("created", plan.createdAt())
                .param("updated", plan.updatedAt())
                .update();
    }

    // AnonymizationRule
    @Override
    public List<MigrationModels.AnonymizationRule> findByEntity(String entityName) {
        return jdbc.sql("select * from anonymization_rule where entity_name = :entity and active = true")
                .param("entity", entityName)
                .query(this::mapAnonymizationRule)
                .list();
    }

    @Override
    public void save(MigrationModels.AnonymizationRule rule) {
        jdbc.sql("""
            insert into anonymization_rule (id, entity_name, field_name, rule_type, rule_config, active, created_at, updated_at)
            values (:id, :entity, :field, :type, :config, :active, :created, :updated)
            on conflict (id) do update set
                entity_name = :entity, field_name = :field, rule_type = :type, rule_config = :config,
                active = :active, updated_at = :updated
            """)
                .param("id", rule.id())
                .param("entity", rule.entityName())
                .param("field", rule.fieldName())
                .param("type", rule.ruleType())
                .param("config", rule.ruleConfig())
                .param("active", rule.active())
                .param("created", rule.createdAt())
                .param("updated", rule.updatedAt())
                .update();
    }

    // Mappers
    private MigrationModels.LegacySystem mapLegacySystem(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.LegacySystem(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name_en"),
                rs.getString("name_ar"),
                rs.getString("description_en"),
                rs.getString("description_ar"),
                rs.getString("connection_config"),
                rs.getString("entity_mappings"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private MigrationModels.EntityMapping mapEntityMapping(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.EntityMapping(
                rs.getObject("id", UUID.class),
                rs.getString("legacy_system_code"),
                rs.getString("legacy_entity_name"),
                rs.getString("target_entity_name"),
                rs.getString("field_mappings"),
                rs.getString("transformation_rules"),
                rs.getString("validation_rules"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private MigrationModels.MigrationJob mapMigrationJob(ResultSet rs, int row) throws SQLException {
        var entities = rs.getString("entity_mappings");
        var list = entities == null || entities.isBlank() ? List.<String>of() :
                Arrays.asList(entities.split("\\s*,\\s*"));
        return new MigrationModels.MigrationJob(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name_en"),
                rs.getString("name_ar"),
                rs.getString("legacy_system_code"),
                list,
                rs.getString("status"),
                rs.getTimestamp("scheduled_at") != null ? rs.getTimestamp("scheduled_at").toInstant() : null,
                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                rs.getLong("total_records"),
                rs.getLong("processed_records"),
                rs.getLong("successful_records"),
                rs.getLong("failed_records"),
                rs.getString("error_summary"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private MigrationModels.MigrationExecution mapMigrationExecution(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.MigrationExecution(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getString("entity_name"),
                rs.getString("status"),
                rs.getLong("total_records"),
                rs.getLong("processed_records"),
                rs.getLong("successful_records"),
                rs.getLong("failed_records"),
                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                rs.getString("error_details")
        );
    }

    private MigrationModels.ReconciliationReport mapReconciliationReport(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.ReconciliationReport(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getString("entity_name"),
                rs.getLong("source_count"),
                rs.getLong("target_count"),
                rs.getLong("matched_count"),
                rs.getLong("mismatched_count"),
                rs.getLong("missing_in_target"),
                rs.getLong("extra_in_target"),
                rs.getDouble("match_percentage"),
                rs.getString("financial_reconciliation"),
                rs.getTimestamp("generated_at").toInstant(),
                rs.getString("status")
        );
    }

    private MigrationModels.ExceptionRecord mapExceptionRecord(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.ExceptionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("execution_id", UUID.class),
                rs.getString("entity_name"),
                rs.getString("legacy_id"),
                rs.getString("legacy_data"),
                rs.getString("target_id"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("severity"),
                rs.getString("assigned_to"),
                rs.getString("resolution"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null
        );
    }

    private MigrationModels.RollbackPlan mapRollbackPlan(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.RollbackPlan(
                rs.getObject("id", UUID.class),
                rs.getString("job_code"),
                rs.getString("name_en"),
                rs.getString("name_ar"),
                rs.getString("description_en"),
                rs.getString("description_ar"),
                rs.getString("rollback_steps"),
                rs.getString("validation_queries"),
                rs.getBoolean("tested"),
                rs.getTimestamp("tested_at") != null ? rs.getTimestamp("tested_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private MigrationModels.AnonymizationRule mapAnonymizationRule(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.AnonymizationRule(
                rs.getObject("id", UUID.class),
                rs.getString("entity_name"),
                rs.getString("field_name"),
                rs.getString("rule_type"),
                rs.getString("rule_config"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}