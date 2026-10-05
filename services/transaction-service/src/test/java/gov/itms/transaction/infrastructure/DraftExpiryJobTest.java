package gov.itms.transaction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.transaction.application.TransactionService;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class DraftExpiryJobTest {

    /** Answers each batch with the next count; records the period and limit asked for. */
    static final class FakeTransactions extends TransactionService {
        final Deque<Object> answers = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        FakeTransactions(Object... answers) {
            super(null, null, null, null, null, null);
            this.answers.addAll(List.of(answers));
        }

        @Override
        public int expireDrafts(Duration period, int limit) {
            calls.add(period + "/" + limit);
            Object next = answers.isEmpty() ? 0 : answers.pop();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (Integer) next;
        }
    }

    private static DraftExpiryProperties properties(int batchSize, int maxBatches) {
        return new DraftExpiryProperties(true, Duration.ofDays(30), Duration.ofMinutes(15), batchSize, maxBatches);
    }

    @Test
    @Tag("CC-024")
    @DisplayName("CC-024: a run expires in batches until a batch is not full, with the configured period")
    void batches() {
        FakeTransactions fake = new FakeTransactions(2, 2, 1, 2);
        assertThat(new DraftExpiryJob(fake, properties(2, 10)).run()).isEqualTo(5);
        assertThat(fake.calls).containsExactly("PT720H/2", "PT720H/2", "PT720H/2");
    }

    @Test
    @Tag("CC-024")
    @DisplayName("CC-024: a run stops after max-batches; the rest waits for the next run")
    void maxBatches() {
        FakeTransactions fake = new FakeTransactions(2, 2, 2, 2);
        assertThat(new DraftExpiryJob(fake, properties(2, 2)).run()).isEqualTo(4);
        assertThat(fake.calls).hasSize(2);
    }

    @Test
    @Tag("CC-024")
    @DisplayName("CC-024: a failed run is logged and does not stop the schedule")
    void failureDoesNotStopSchedule() {
        FakeTransactions fake = new FakeTransactions(new IllegalStateException("database down"), 0);
        DraftExpiryJob job = new DraftExpiryJob(fake, properties(2, 2));
        job.scheduled();
        job.scheduled();
        assertThat(fake.calls).hasSize(2);
    }

    @Test
    @Tag("CC-024")
    @DisplayName("CC-024: the period, interval and batch settings are refused when not positive")
    void configurationIsValidated() {
        Duration day = Duration.ofDays(1);
        assertThatThrownBy(() -> new DraftExpiryProperties(true, Duration.ZERO, day, 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("period");
        assertThatThrownBy(() -> new DraftExpiryProperties(true, null, day, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftExpiryProperties(true, day, day.negated(), 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interval");
        assertThatThrownBy(() -> new DraftExpiryProperties(true, day, day, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftExpiryProperties(true, day, day, 1001, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftExpiryProperties(true, day, day, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DraftExpiryProperties(true, day, day, 1000, 1).batchSize()).isEqualTo(1000);
    }
}
