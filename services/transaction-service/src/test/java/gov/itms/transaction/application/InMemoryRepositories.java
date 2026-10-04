package gov.itms.transaction.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.AvailabilityRule;
import gov.itms.transaction.domain.CatalogueChange;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.ServiceDefinition;
import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.Subject;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** In-memory test doubles for the application ports. They mimic the database constraints that matter to the rules. */
final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    static final class Services implements ServiceDefinitionRepository {
        final Map<String, ServiceDefinition> rows = new TreeMap<>();
        final List<ServiceVersion> history = new ArrayList<>();

        @Override
        public List<ServiceDefinition> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<ServiceDefinition> find(String code) {
            return Optional.ofNullable(rows.get(code));
        }

        @Override
        public Optional<ServiceDefinition> findForUpdate(String code) {
            return find(code);
        }

        @Override
        public void insert(ServiceDefinition d) {
            if (rows.putIfAbsent(d.code(), d) != null) {
                throw new IllegalStateException("duplicate key");
            }
            capture(d);
        }

        @Override
        public void update(ServiceDefinition d) {
            ServiceDefinition current = rows.get(d.code());
            if (current == null || current.version() != d.version() - 1) {
                throw new IllegalStateException("version check failed");
            }
            rows.put(d.code(), d);
            capture(d);
        }

        private void capture(ServiceDefinition d) {
            for (int i = 0; i < history.size(); i++) {
                ServiceVersion v = history.get(i);
                if (v.spec().code().equals(d.code()) && v.validTo() == null) {
                    history.set(i, new ServiceVersion(v.spec(), v.version(), v.validFrom(), d.updatedAt(),
                            v.changedBy(), v.changeId()));
                }
            }
            history.add(new ServiceVersion(d.spec(), d.version(), d.updatedAt(), null, d.updatedBy().id(),
                    d.lastChangeId()));
        }

        @Override
        public List<ServiceVersion> history(String code) {
            return history.stream().filter(v -> v.spec().code().equals(code))
                    .sorted(Comparator.comparingLong(ServiceVersion::version)).toList();
        }
    }

    static final class Rules implements AvailabilityRuleRepository {
        final Map<UUID, AvailabilityRule> rows = new LinkedHashMap<>();

        @Override
        public List<AvailabilityRule> findByService(String serviceCode) {
            return rows.values().stream().filter(r -> r.serviceCode().equals(serviceCode)).toList();
        }

        @Override
        public List<AvailabilityRule> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<AvailabilityRule> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void insert(AvailabilityRule rule, Actor createdBy, Instant now) {
            boolean overlap = findByService(rule.serviceCode()).stream()
                    .anyMatch(r -> r.spec().overlaps(rule.spec()));
            if (overlap) {
                throw new IllegalStateException("availability_rule_overlap_check");
            }
            rows.put(rule.id(), rule);
        }

        @Override
        public void saveEnd(AvailabilityRule rule) {
            rows.put(rule.id(), rule);
        }
    }

    static final class Changes implements CatalogueChangeRepository {
        final Map<UUID, CatalogueChange> rows = new LinkedHashMap<>();

        @Override
        public void insert(CatalogueChange change) {
            if (pendingExistsFor(change.serviceCode())) {
                throw DomainException.conflict("CHANGE_ALREADY_PENDING", "pending", change.serviceCode());
            }
            rows.put(change.id(), change);
        }

        @Override
        public Optional<CatalogueChange> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<CatalogueChange> findForDecision(UUID id) {
            return find(id);
        }

        @Override
        public List<CatalogueChange> findByStatus(CatalogueChange.Status status) {
            return rows.values().stream().filter(c -> status == null || c.status() == status)
                    .sorted(Comparator.comparing(CatalogueChange::submittedAt).reversed()).toList();
        }

        @Override
        public boolean pendingExistsFor(String serviceCode) {
            return rows.values().stream().anyMatch(c -> c.serviceCode().equals(serviceCode)
                    && c.status() == CatalogueChange.Status.PENDING_APPROVAL);
        }

        @Override
        public void saveDecision(CatalogueChange change) {
            rows.put(change.id(), change);
        }
    }

    /** Keeps copies, as the database keeps only what was saved; mimics the sequence and the CC-027 unique index. */
    static final class Transactions implements TransactionRepository {
        final Map<String, Transaction> rows = new LinkedHashMap<>();
        final Map<UUID, List<StatusChange>> history = new LinkedHashMap<>();
        final Map<String, Long> sequences = new LinkedHashMap<>();

        @Override
        public long nextSequence(String serviceCode, String region, int year) {
            return sequences.merge(serviceCode + "|" + region + "|" + year, 1L, Long::sum);
        }

        @Override
        public Optional<Transaction> findOpen(String serviceCode, Subject subject) {
            return rows.values().stream().filter(t -> t.serviceCode().equals(serviceCode)
                    && t.subject().equals(subject) && t.status().isOpen()).findFirst().map(Transactions::copy);
        }

        @Override
        public void insert(Transaction t) {
            boolean duplicate = !t.allowsConcurrentOpen() && rows.values().stream().anyMatch(o ->
                    !o.allowsConcurrentOpen() && o.serviceCode().equals(t.serviceCode())
                            && o.subject().equals(t.subject()) && o.status().isOpen());
            if (duplicate) {
                throw new DomainException(DomainException.Kind.CONFLICT, "DUPLICATE_OPEN_TRANSACTION", "duplicate",
                        List.of("CC-027"), t.serviceCode(), "");
            }
            rows.put(t.reference().toString(), copy(t));
            history.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(t.lastChange());
        }

        @Override
        public Optional<Transaction> find(TransactionReference reference) {
            return Optional.ofNullable(rows.get(reference.toString())).map(Transactions::copy);
        }

        @Override
        public Optional<Transaction> findForUpdate(TransactionReference reference) {
            return find(reference);
        }

        @Override
        public void saveStatus(Transaction t) {
            Transaction stored = rows.get(t.reference().toString());
            StatusChange change = t.lastChange();
            if (stored == null || stored.version() != change.sequence() - 1
                    || stored.status() != change.previousStatus()) {
                throw new IllegalStateException("version check failed");
            }
            rows.put(t.reference().toString(), copy(t));
            history.get(t.id()).add(change);
        }

        @Override
        public List<StatusChange> history(UUID transactionId) {
            return List.copyOf(history.getOrDefault(transactionId, List.of()));
        }

        static Transaction copy(Transaction t) {
            return new Transaction(t.id(), t.reference(), t.serviceVersion(), t.module(), t.feeCode(),
                    t.allowsConcurrentOpen(), t.subject(), t.applicantFileNumber(), t.homeRegion(), t.channel(),
                    t.createdAt(), t.createdBy(), t.submittedAt(), t.lastChange());
        }
    }

    record AuditEntry(String action, String entityType, String entityId, Object oldValue, Object newValue,
                      String reason) {
    }

    static final class Audit implements AuditRecorder {
        final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            entries.add(new AuditEntry(action, entityType, entityId, oldValue, newValue, reason));
        }

        @Override
        public void recordAs(gov.itms.platform.security.CurrentActor actor, String action, String entityType,
                             String entityId, Object oldValue, Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }

        List<String> actions() {
            return entries.stream().map(AuditEntry::action).toList();
        }
    }
}
