package co.unicauca.edu.co.contables.billing.application.service;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.NOW;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.draftSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingDocumentRepositoryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdempotencyPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingTransactionRunnerPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyKeyCollisionException;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyRecord;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IDM-1..5: creacion idempotente (header Idempotency-Key). Misma clave + mismo cuerpo = el mismo
 * documento sin crear otro; misma clave + cuerpo distinto = conflicto; el perdedor de una carrera
 * relee al ganador en una transaccion nueva.
 */
@ExtendWith(MockitoExtension.class)
class BillingIdempotentCreationServiceTest {

    private static final String KEY = "6f1c1f3a-3e56-4c7a-9a0e-0d3a5c8d1b11";
    private static final String HASH = "hash-a";
    private static final DocumentType TYPE = DocumentType.SALE_INVOICE;

    @Mock
    private BillingIdempotencyPort idempotency;
    @Mock
    private BillingDocumentRepositoryPort repository;
    @Mock
    private BillingClockPort clock;

    private CountingTransactions transactions;
    private BillingIdempotentCreationService service;
    private AtomicInteger creations;
    private BillingDocument created;

    /** Cada bloque corre "en una transaccion nueva"; cuenta cuantas se abrieron. */
    private static final class CountingTransactions implements BillingTransactionRunnerPort {
        final AtomicInteger opened = new AtomicInteger();

        @Override
        public void inNewTransaction(Runnable action) {
            opened.incrementAndGet();
            action.run();
        }

        @Override
        public <T> T callInNewTransaction(Supplier<T> action) {
            opened.incrementAndGet();
            return action.get();
        }
    }

    @BeforeEach
    void setUp() {
        lenient().when(clock.now()).thenReturn(NOW);
        transactions = new CountingTransactions();
        service = new BillingIdempotentCreationService(idempotency, repository, transactions, clock);
        creations = new AtomicInteger();
        created = draftSale();
    }

    private Supplier<BillingDocument> creator() {
        return () -> {
            creations.incrementAndGet();
            return created;
        };
    }

    private IdempotencyRecord recordOf(String hash, DocumentType type) {
        return new IdempotencyRecord(ENTERPRISE_ID, KEY, hash, type, created.id(), NOW, NOW.plus(Duration.ofHours(24)));
    }

    private static BillingErrorCode codeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BillingException.class);
        return ((BillingException) thrown).errorCode();
    }

    // ---------- primera vez ----------

    @Test
    void IDM_1_first_use_creates_the_document_and_registers_the_key_in_the_same_transaction() {
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.empty());

        BillingDocument result = service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator());

        assertThat(result).isSameAs(created);
        assertThat(creations).hasValue(1);
        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotency).register(captor.capture());
        IdempotencyRecord record = captor.getValue();
        assertThat(record.enterpriseId()).isEqualTo(ENTERPRISE_ID);
        assertThat(record.key()).isEqualTo(KEY);
        assertThat(record.requestHash()).isEqualTo(HASH);
        assertThat(record.documentType()).isEqualTo(TYPE);
        assertThat(record.documentId()).isEqualTo(created.id());
        assertThat(record.createdAt()).isEqualTo(NOW);
        assertThat(record.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        // una transaccion para la consulta previa y otra para crear + registrar
        assertThat(transactions.opened).hasValue(2);
    }

    @Test
    void IDM_5_a_failing_creation_registers_nothing_and_propagates() {
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.empty());
        Supplier<BillingDocument> failing = () -> {
            throw new BillingException(BillingErrorCode.BILLING_PRODUCT_NOT_FOUND, "Producto 99");
        };

        Throwable thrown = catchThrowable(() -> service.create(ENTERPRISE_ID, TYPE, KEY, HASH, failing));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_PRODUCT_NOT_FOUND);
        verify(idempotency, never()).register(any());
    }

    // ---------- repeticion ----------

    @Test
    void IDM_1_the_same_key_and_body_returns_the_original_document_without_creating_another() {
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.of(recordOf(HASH, TYPE)));
        BillingDocument stored = draftSale();
        when(repository.findById(ENTERPRISE_ID, created.id())).thenReturn(Optional.of(stored));

        BillingDocument result = service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator());

        assertThat(result).isSameAs(stored);
        assertThat(creations).hasValue(0);
        verify(idempotency, never()).register(any());
    }

    @Test
    void IDM_1_twenty_calls_with_the_same_key_create_a_single_document() {
        AtomicReference<IdempotencyRecord> stored = new AtomicReference<>();
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        doThrowFree(stored);
        when(repository.findById(ENTERPRISE_ID, created.id())).thenReturn(Optional.of(created));

        for (int i = 0; i < 20; i++) {
            assertThat(service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator())).isSameAs(created);
        }

        assertThat(creations).hasValue(1);
        verify(idempotency, times(1)).register(any());
    }

    private void doThrowFree(AtomicReference<IdempotencyRecord> stored) {
        org.mockito.Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return null;
        }).when(idempotency).register(any());
    }

    @Test
    void IDM_2_the_same_key_with_a_different_body_is_an_idempotency_conflict() {
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.of(recordOf("hash-b", TYPE)));

        Throwable thrown = catchThrowable(() -> service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator()));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        assertThat(creations).hasValue(0);
        verify(idempotency, never()).register(any());
    }

    @Test
    void IDM_2_the_same_key_used_for_another_document_type_is_an_idempotency_conflict() {
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW))
                .thenReturn(Optional.of(recordOf(HASH, DocumentType.PURCHASE_INVOICE)));

        Throwable thrown = catchThrowable(() -> service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator()));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        assertThat(creations).hasValue(0);
    }

    @Test
    void IDM_1_replaying_a_key_whose_document_was_deleted_is_not_found_and_does_not_recreate() {
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.of(recordOf(HASH, TYPE)));
        when(repository.findById(ENTERPRISE_ID, created.id())).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator()));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertThat(creations).hasValue(0);
    }

    // ---------- carrera: gana otra peticion ----------

    @Test
    void IDM_3_the_loser_of_a_race_rereads_the_winner_in_a_new_transaction_and_returns_its_document() {
        BillingDocument winnerDocument = draftSale();
        IdempotencyRecord winner = new IdempotencyRecord(ENTERPRISE_ID, KEY, HASH, TYPE, winnerDocument.id(), NOW,
                NOW.plus(Duration.ofHours(24)));
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new IdempotencyKeyCollisionException("duplicada", null)).when(idempotency).register(any());
        when(repository.findById(ENTERPRISE_ID, winnerDocument.id())).thenReturn(Optional.of(winnerDocument));

        BillingDocument result = service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator());

        assertThat(result).isSameAs(winnerDocument);
        assertThat(creations).hasValue(1); // intento crear, pero su transaccion se revirtio al colisionar
        assertThat(transactions.opened.get()).isEqualTo(3); // consulta + crear/registrar (revertida) + releer
    }

    @Test
    void IDM_3_the_loser_of_a_race_with_a_different_body_gets_the_conflict() {
        IdempotencyRecord winner = recordOf("hash-b", TYPE);
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new IdempotencyKeyCollisionException("duplicada", null)).when(idempotency).register(any());

        Throwable thrown = catchThrowable(() -> service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator()));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void IDM_3_if_the_winner_cannot_be_found_after_a_collision_the_client_is_told_to_retry() {
        when(idempotency.find(ENTERPRISE_ID, KEY, NOW)).thenReturn(Optional.empty(), Optional.empty());
        doThrow(new IdempotencyKeyCollisionException("duplicada", null)).when(idempotency).register(any());

        Throwable thrown = catchThrowable(() -> service.create(ENTERPRISE_ID, TYPE, KEY, HASH, creator()));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION);
    }
}
