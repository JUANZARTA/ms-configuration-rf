package co.unicauca.edu.co.contables.billing.application.service;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.NOW;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TENANT_A;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingEventPublisherPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingExecutionContextPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingOutboxRelayPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingTransactionRunnerPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.EventPublicationException;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.OutboxClaim;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.PendingOutboxMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EVT-4, RNF-05, TEN-5: relay del outbox. Reclama las filas vencidas (sin tenant), y por cada una
 * fija el tenant de la fila ANTES de abrir la transaccion nueva, la bloquea, publica y la marca. Un
 * fallo de una fila no afecta a las demas; el backoff y el agotamiento los decide la politica.
 */
@ExtendWith(MockitoExtension.class)
class BillingOutboxRelayServiceTest {

    private static final int BATCH = 50;

    @Mock
    private BillingOutboxRelayPort relayPort;
    @Mock
    private BillingEventPublisherPort publisher;
    @Mock
    private BillingClockPort clock;

    private FakeContext context;
    private FakeTransactions transactions;
    private BillingOutboxRelayService service;

    /** Contexto con tenant observable: registra el tenant vigente y lo restaura al terminar. */
    private static final class FakeContext implements BillingExecutionContextPort {
        String current;
        final List<String> switches = new ArrayList<>();

        @Override
        public String tenantId() {
            if (current == null) {
                throw new IllegalStateException("sin tenant");
            }
            return current;
        }

        @Override
        public String username() {
            return "system";
        }

        @Override
        public void runAsTenant(String tenantId, Runnable action) {
            String previous = current;
            current = tenantId;
            switches.add(tenantId);
            try {
                action.run();
            } finally {
                current = previous;
            }
        }
    }

    /** Transacciones observables: registra el tenant vigente al abrir cada una. */
    private static final class FakeTransactions implements BillingTransactionRunnerPort {
        private final FakeContext context;
        boolean active;
        final List<String> tenantAtOpen = new ArrayList<>();

        FakeTransactions(FakeContext context) {
            this.context = context;
        }

        @Override
        public void inNewTransaction(Runnable action) {
            tenantAtOpen.add(context.current);
            active = true;
            try {
                action.run();
            } finally {
                active = false;
            }
        }

        @Override
        public <T> T callInNewTransaction(java.util.function.Supplier<T> action) {
            java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
            inNewTransaction(() -> result.set(action.get()));
            return result.get();
        }
    }

    @BeforeEach
    void setUp() {
        lenient().when(clock.now()).thenReturn(NOW);
        context = new FakeContext();
        transactions = new FakeTransactions(context);
        service = new BillingOutboxRelayService(relayPort, publisher, context, transactions, clock,
                new OutboxRetryPolicy(10, Duration.ofSeconds(5), Duration.ofMinutes(10)), BATCH);
    }

    // ---------- helpers ----------

    private static PendingOutboxMessage message(long id, String tenant, int attempts) {
        return new PendingOutboxMessage(id, "event-" + id, "FacturaVentaEmitida", tenant,
                "billing.documents.exchange", "sale-invoice.emitted.v1", "{\"id\":" + id + "}", attempts, NOW);
    }

    private void dueClaims(OutboxClaim... claims) {
        when(relayPort.findDueClaims(NOW, BATCH)).thenReturn(List.of(claims));
    }

    private void lockable(PendingOutboxMessage message) {
        when(relayPort.lockPending(message.id())).thenReturn(Optional.of(message));
    }

    // ---------- camino feliz ----------

    @Test
    void EVT_4_without_due_rows_nothing_is_done() {
        dueClaims();

        int published = service.relayDue();

        assertThat(published).isZero();
        verify(relayPort).findDueClaims(NOW, BATCH);
        verifyNoInteractions(publisher);
        assertThat(context.switches).isEmpty();
    }

    @Test
    void EVT_4_a_due_row_is_locked_published_and_marked_in_that_order() {
        PendingOutboxMessage message = message(1, TENANT_A, 0);
        dueClaims(new OutboxClaim(1, TENANT_A));
        lockable(message);

        int published = service.relayDue();

        assertThat(published).isEqualTo(1);
        InOrder order = inOrder(relayPort, publisher);
        order.verify(relayPort).findDueClaims(NOW, BATCH);
        order.verify(relayPort).lockPending(1L);
        order.verify(publisher).publish(message);
        order.verify(relayPort).markPublished(1L, NOW);
        verify(relayPort, never()).markAttemptFailed(anyLong(), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    void TEN_5_the_tenant_of_the_row_is_set_before_the_transaction_opens_and_is_restored_afterwards() {
        PendingOutboxMessage message = message(1, TENANT_A, 0);
        dueClaims(new OutboxClaim(1, TENANT_A));
        List<String> tenantSeenWhenLocking = new ArrayList<>();
        List<Boolean> transactionActiveWhenLocking = new ArrayList<>();
        when(relayPort.lockPending(1L)).thenAnswer(inv -> {
            tenantSeenWhenLocking.add(context.current);
            transactionActiveWhenLocking.add(transactions.active);
            return Optional.of(message);
        });
        context.current = "previous";

        service.relayDue();

        assertThat(transactions.tenantAtOpen).containsExactly(TENANT_A);
        assertThat(tenantSeenWhenLocking).containsExactly(TENANT_A);
        assertThat(transactionActiveWhenLocking).containsExactly(true);
        assertThat(context.current).isEqualTo("previous");
    }

    @Test
    void TEN_5_rows_of_tenants_A_and_B_each_run_under_their_own_tenant() {
        dueClaims(new OutboxClaim(1, TENANT_A), new OutboxClaim(2, TENANT_B), new OutboxClaim(3, TENANT_A));
        lockable(message(1, TENANT_A, 0));
        lockable(message(2, TENANT_B, 0));
        lockable(message(3, TENANT_A, 0));
        List<String> tenantAtPublish = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            tenantAtPublish.add(context.current);
            return null;
        }).when(publisher).publish(any(PendingOutboxMessage.class));

        int published = service.relayDue();

        assertThat(published).isEqualTo(3);
        assertThat(transactions.tenantAtOpen).containsExactly(TENANT_A, TENANT_B, TENANT_A);
        assertThat(tenantAtPublish).containsExactly(TENANT_A, TENANT_B, TENANT_A);
        assertThat(context.current).isNull();
        verify(relayPort).markPublished(1L, NOW);
        verify(relayPort).markPublished(2L, NOW);
        verify(relayPort).markPublished(3L, NOW);
    }

    // ---------- fallo y reintento ----------

    @Test
    void EVT_4_publish_failure_increments_attempts_and_schedules_five_seconds_later() {
        PendingOutboxMessage message = message(1, TENANT_A, 0);
        dueClaims(new OutboxClaim(1, TENANT_A));
        lockable(message);
        doThrow(new EventPublicationException("nack del broker")).when(publisher).publish(message);

        int published = service.relayDue();

        assertThat(published).isZero();
        verify(relayPort).markAttemptFailed(1L, 1, NOW.plusSeconds(5), false, "nack del broker");
        verify(relayPort, never()).markPublished(anyLong(), any());
    }

    @Test
    void EVT_4_backoff_follows_the_attempt_counter_stored_in_the_row() {
        PendingOutboxMessage message = message(1, TENANT_A, 3);
        dueClaims(new OutboxClaim(1, TENANT_A));
        lockable(message);
        doThrow(new EventPublicationException("timeout")).when(publisher).publish(message);

        service.relayDue();

        // intentos previos 3 -> este es el 4o -> 5s * 2^3 = 40s
        verify(relayPort).markAttemptFailed(1L, 4, NOW.plusSeconds(40), false, "timeout");
    }

    @Test
    void EVT_4_the_tenth_failed_attempt_marks_the_row_as_definitively_failed() {
        PendingOutboxMessage message = message(1, TENANT_A, 9);
        dueClaims(new OutboxClaim(1, TENANT_A));
        lockable(message);
        doThrow(new EventPublicationException("sigue caido")).when(publisher).publish(message);

        service.relayDue();

        verify(relayPort).markAttemptFailed(1L, 10, NOW, true, "sigue caido");
    }

    @Test
    void EVT_4_a_non_publication_runtime_failure_is_also_an_attempt() {
        PendingOutboxMessage message = message(1, TENANT_A, 0);
        dueClaims(new OutboxClaim(1, TENANT_A));
        lockable(message);
        doThrow(new IllegalStateException("conexion perdida")).when(publisher).publish(message);

        service.relayDue();

        verify(relayPort).markAttemptFailed(1L, 1, NOW.plusSeconds(5), false, "conexion perdida");
    }

    @Test
    void EVT_4_an_exception_without_message_records_its_class_name() {
        PendingOutboxMessage message = message(1, TENANT_A, 0);
        dueClaims(new OutboxClaim(1, TENANT_A));
        lockable(message);
        doThrow(new NullPointerException()).when(publisher).publish(message);

        service.relayDue();

        verify(relayPort).markAttemptFailed(eq(1L), eq(1), eq(NOW.plusSeconds(5)), eq(false),
                eq("java.lang.NullPointerException"));
    }

    // ---------- aislamiento ----------

    @Test
    void EVT_4_a_failing_row_does_not_stop_the_following_rows() {
        PendingOutboxMessage failing = message(1, TENANT_A, 0);
        PendingOutboxMessage healthy = message(2, TENANT_B, 0);
        dueClaims(new OutboxClaim(1, TENANT_A), new OutboxClaim(2, TENANT_B));
        lockable(failing);
        lockable(healthy);
        doThrow(new EventPublicationException("nack")).when(publisher).publish(failing);

        int published = service.relayDue();

        assertThat(published).isEqualTo(1);
        verify(relayPort).markAttemptFailed(1L, 1, NOW.plusSeconds(5), false, "nack");
        verify(publisher).publish(healthy);
        verify(relayPort).markPublished(2L, NOW);
    }

    @Test
    void EVT_4_a_failure_while_marking_published_is_isolated_and_the_row_is_not_counted() {
        PendingOutboxMessage first = message(1, TENANT_A, 0);
        PendingOutboxMessage second = message(2, TENANT_A, 0);
        dueClaims(new OutboxClaim(1, TENANT_A), new OutboxClaim(2, TENANT_A));
        lockable(first);
        lockable(second);
        doThrow(new IllegalStateException("db caida")).when(relayPort).markPublished(1L, NOW);

        int published = service.relayDue();

        // El envio ocurrio pero el marcado fallo: la transaccion revierte y la fila se reenviara
        // (entrega al menos una vez). No se cuenta como publicada ni como intento fallido.
        assertThat(published).isEqualTo(1);
        verify(relayPort, never()).markAttemptFailed(eq(1L), anyInt(), any(), anyBoolean(), anyString());
        verify(relayPort).markPublished(2L, NOW);
    }

    @Test
    void EVT_4_a_failure_locking_a_row_is_isolated() {
        PendingOutboxMessage second = message(2, TENANT_A, 0);
        dueClaims(new OutboxClaim(1, TENANT_A), new OutboxClaim(2, TENANT_A));
        when(relayPort.lockPending(1L)).thenThrow(new IllegalStateException("lock timeout"));
        lockable(second);

        int published = service.relayDue();

        assertThat(published).isEqualTo(1);
        verify(publisher, never()).publish(message(1, TENANT_A, 0));
        verify(relayPort).markPublished(2L, NOW);
        assertThat(context.current).isNull();
    }

    @Test
    void EVT_4_a_row_already_taken_by_another_instance_is_skipped_silently() {
        dueClaims(new OutboxClaim(1, TENANT_A));
        when(relayPort.lockPending(1L)).thenReturn(Optional.empty());

        int published = service.relayDue();

        assertThat(published).isZero();
        verifyNoInteractions(publisher);
        verify(relayPort, never()).markPublished(anyLong(), any());
        verify(relayPort, never()).markAttemptFailed(anyLong(), anyInt(), any(), anyBoolean(), any());
    }

    // ---------- no monopolizar el unico hilo del planificador ----------

    @Test
    void RNF_05_after_three_consecutive_failures_the_batch_is_cut_short() {
        List<OutboxClaim> claims = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            claims.add(new OutboxClaim(id, TENANT_A));
            // lenient: las filas 4..6 no se llegan a bloquear porque el lote se corta
            lenient().when(relayPort.lockPending(id)).thenReturn(Optional.of(message(id, TENANT_A, 0)));
        }
        when(relayPort.findDueClaims(NOW, BATCH)).thenReturn(claims);
        doThrow(new EventPublicationException("broker caido")).when(publisher).publish(any(PendingOutboxMessage.class));

        int published = service.relayDue();

        assertThat(published).isZero();
        verify(publisher, times(3)).publish(any(PendingOutboxMessage.class));
        verify(relayPort, times(3)).markAttemptFailed(anyLong(), eq(1), any(), eq(false), eq("broker caido"));
        // Las filas no intentadas siguen PENDING y vencidas: se reclaman en la proxima corrida.
        verify(relayPort, never()).lockPending(4L);
    }

    @Test
    void RNF_05_a_success_resets_the_consecutive_failure_counter() {
        List<OutboxClaim> claims = new ArrayList<>();
        List<PendingOutboxMessage> messages = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            claims.add(new OutboxClaim(id, TENANT_A));
            PendingOutboxMessage message = message(id, TENANT_A, 0);
            messages.add(message);
            lockable(message);
        }
        when(relayPort.findDueClaims(NOW, BATCH)).thenReturn(claims);
        for (long failing : new long[] {1, 2, 4, 5}) {
            // lenient: publicar una fila no stubeada (la 3 o la 6) no debe disparar PotentialStubbingProblem
            lenient().doThrow(new EventPublicationException("nack")).when(publisher)
                    .publish(messages.get((int) failing - 1));
        }

        int published = service.relayDue();

        assertThat(published).isEqualTo(2);
        verify(publisher, times(6)).publish(any(PendingOutboxMessage.class));
    }

    // ---------- configuracion ----------

    @Test
    void EVT_4_the_batch_size_is_passed_to_the_claim_query() {
        BillingOutboxRelayService small = new BillingOutboxRelayService(relayPort, publisher, context, transactions,
                clock, new OutboxRetryPolicy(10, Duration.ofSeconds(5), Duration.ofMinutes(10)), 7);
        when(relayPort.findDueClaims(NOW, 7)).thenReturn(List.of());

        small.relayDue();

        verify(relayPort).findDueClaims(NOW, 7);
    }

    @Test
    void EVT_4_a_non_positive_batch_size_is_rejected() {
        assertThatThrownBy(() -> new BillingOutboxRelayService(relayPort, publisher, context, transactions, clock,
                new OutboxRetryPolicy(10, Duration.ofSeconds(5), Duration.ofMinutes(10)), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
