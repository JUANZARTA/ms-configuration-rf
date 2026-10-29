package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TENANT_A;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TENANT_B;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSaleWithLines;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.issuer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.lineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.product;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnDraftOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnLineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import co.unicauca.edu.co.contables.billing.application.service.BillingAnnulmentService;
import co.unicauca.edu.co.contables.billing.application.service.BillingDocumentLoader;
import co.unicauca.edu.co.contables.billing.application.service.BillingEmissionService;
import co.unicauca.edu.co.contables.billing.application.service.BillingReferenceResolver;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.support.FakeIntegrationPort;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.context.BillingClockAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.context.BillingExecutionContextAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.messaging.BillingOutboxAdapter;
import co.unicauca.edu.co.contables.commons.multitenancy.utils.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RET-5 / ANL-2 / D11 / D12 con bloqueos reales (H2 en modo PostgreSQL) sobre los servicios REALES de
 * emision y anulacion y los adaptadores reales de persistencia, secuencia y outbox: dos devoluciones
 * simultaneas sobre la misma factura nunca superan lo facturado, la anulacion de la factura y la
 * emision de una devolucion se serializan por la fila de la original, y un tenant no ve al otro.
 *
 * <p>Para que los hilos choquen de verdad, el hilo del test toma primero el bloqueo de la fila de la
 * original y lo sostiene un rato mientras los competidores cargan su borrador y quedan esperando ese
 * mismo bloqueo; al soltarlo, la revalidacion bajo bloqueo es lo que se esta probando. Cada operacion
 * corre en su propia transaccion (sin transaccion de prueba) y el tenant se fija ANTES de abrirla.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing_ret_conc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = BillingJpaTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BillingReturnEmissionConcurrencyTest {

    private static final DocumentType RETURN = DocumentType.SALE_RETURN;
    private static final DocumentType INVOICE = DocumentType.SALE_INVOICE;
    /** Tiempo que el hilo de prueba retiene el bloqueo de la original (menor que lock.timeout = 3 s). */
    private static final long HOLD_MS = 1200;

    @Autowired
    private BillingDocumentPersistenceAdapter adapter;
    @Autowired
    private BillingDocumentSequenceAdapter sequence;
    @Autowired
    private BillingOutboxAdapter outbox;
    @Autowired
    private BillingExecutionContextAdapter executionContext;
    @Autowired
    private BillingClockAdapter clock;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private BillingEmissionService emission;
    private BillingAnnulmentService annulment;

    @BeforeEach
    void buildRealServices() {
        BillingDocumentLoader loader = new BillingDocumentLoader(adapter);
        BillingReferenceResolver resolver = new BillingReferenceResolver(
                (enterprise, thirdId, role) -> Optional.empty(),
                (enterprise, productId, kind) -> Optional.empty(),
                enterprise -> Optional.of(issuer()));
        FakeIntegrationPort integration = new FakeIntegrationPort();
        emission = new BillingEmissionService(adapter, loader, resolver, sequence, outbox, executionContext, clock,
                UUID::randomUUID, integration, integration);
        annulment = new BillingAnnulmentService(adapter, loader, outbox, executionContext, clock, UUID::randomUUID,
                integration, integration);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        jdbc.update("delete from billing_outbox_events");
        jdbc.update("delete from billing_document_lines");
        jdbc.update("delete from billing_documents");
        jdbc.update("delete from billing_document_sequences");
    }

    // ---------- helpers ----------

    private record Outcome(BillingDocument document, Throwable error) {
        boolean ok() {
            return error == null;
        }

        BillingErrorCode code() {
            return error instanceof BillingException billing ? billing.errorCode() : null;
        }
    }

    private <T> T inTenantTx(String tenant, Supplier<T> action) {
        TenantContext.setTenantId(tenant);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    private Outcome attempt(String tenant, Supplier<BillingDocument> action) {
        try {
            return new Outcome(inTenantTx(tenant, action), null);
        } catch (Throwable thrown) {
            return new Outcome(null, thrown);
        }
    }

    private Outcome emit(String tenant, UUID returnId) {
        return attempt(tenant, () -> emission.emit(ENTERPRISE_ID, RETURN, returnId, null));
    }

    private Outcome annulInvoice(String tenant, UUID invoiceId) {
        return attempt(tenant, () -> annulment.annul(ENTERPRISE_ID, INVOICE, invoiceId, "error de digitacion", null));
    }

    private Outcome annulReturn(String tenant, UUID returnId) {
        return attempt(tenant, () -> annulment.annul(ENTERPRISE_ID, RETURN, returnId, "cliente desistio", null));
    }

    private static BillingDocument invoiceWithQuantity(UUID id, long number, String quantity) {
        return emittedSaleWithLines(id, number, lineOf(1, product(1L), quantity));
    }

    private static BillingDocument draftReturn(BillingDocument original, UUID id, String quantity) {
        return returnDraftOf(original, id, "producto defectuoso", returnLineOf(original, 1, quantity));
    }

    private void seed(String tenant, BillingDocument... documents) {
        inTenantTx(tenant, () -> {
            for (BillingDocument document : documents) {
                adapter.save(document);
            }
            return null;
        });
    }

    /**
     * Toma el bloqueo de la fila de la original, lanza los competidores y lo sostiene {@link #HOLD_MS}
     * para que todos queden esperandolo; al confirmar, los competidores corren en el orden que decida
     * la base de datos. Devuelve los resultados en el orden de las tareas.
     */
    private List<Outcome> raceBehindLock(UUID lockedOriginal, List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            inTenantTx(TENANT_A, () -> {
                adapter.findByIdForUpdate(ENTERPRISE_ID, lockedOriginal).orElseThrow();
                for (Callable<Outcome> task : tasks) {
                    futures.add(pool.submit(task));
                }
                sleep(HOLD_MS);
                return null;
            });
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private long returnsInStatus(String status) {
        return count("select count(*) from billing_documents where document_type = 'SALE_RETURN' and status = ?",
                status);
    }

    private long lastReturnNumber() {
        return count("select coalesce(max(last_number), 0) from billing_document_sequences "
                + "where document_type = 'SALE_RETURN'");
    }

    private long outboxRows() {
        return count("select count(*) from billing_outbox_events");
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from billing_documents where cast(id as varchar) = ?",
                String.class, id.toString());
    }

    private static List<Outcome> successes(List<Outcome> outcomes) {
        return outcomes.stream().filter(Outcome::ok).toList();
    }

    private static List<Outcome> failures(List<Outcome> outcomes) {
        return outcomes.stream().filter(outcome -> !outcome.ok()).toList();
    }

    // ---------- RET-5: dos devoluciones simultaneas ----------

    @Test
    @Timeout(90)
    void RET_5_two_concurrent_emissions_of_six_over_ten_sold_exactly_one_succeeds() throws Exception {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "6"), draftReturn(original, uuid(3), "6"));

        List<Outcome> outcomes = raceBehindLock(uuid(1), List.of(
                () -> emit(TENANT_A, uuid(2)), () -> emit(TENANT_A, uuid(3))));

        assertThat(successes(outcomes)).hasSize(1);
        assertThat(failures(outcomes)).hasSize(1);
        assertThat(failures(outcomes).get(0).code()).isEqualTo(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED);
        assertThat(successes(outcomes).get(0).document().number().display()).isEqualTo("NC-000001");
        assertThat(returnsInStatus("EMITIDA")).isEqualTo(1L);
        assertThat(returnsInStatus("BORRADOR")).isEqualTo(1L);
        assertThat(count("select count(*) from billing_documents where status = 'BORRADOR' "
                + "and number_value is null")).isEqualTo(1L);
        assertThat(lastReturnNumber()).isEqualTo(1L);
        assertThat(outboxRows()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select event_type from billing_outbox_events", String.class))
                .isEqualTo("FacturaVentaDevuelta");
    }

    @Test
    @Timeout(90)
    void RET_5_the_original_is_never_modified_by_the_race() throws Exception {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "6"), draftReturn(original, uuid(3), "6"));

        raceBehindLock(uuid(1), List.of(() -> emit(TENANT_A, uuid(2)), () -> emit(TENANT_A, uuid(3))));

        assertThat(statusOf(uuid(1))).isEqualTo("EMITIDA");
        assertThat(count("select count(*) from billing_documents where document_type = 'SALE_INVOICE' "
                + "and version = 0")).isEqualTo(1L);
    }

    @Test
    @Timeout(90)
    void RET_5_two_concurrent_emissions_that_fit_together_both_succeed_with_consecutive_numbers() throws Exception {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "5"), draftReturn(original, uuid(3), "5"));

        List<Outcome> outcomes = raceBehindLock(uuid(1), List.of(
                () -> emit(TENANT_A, uuid(2)), () -> emit(TENANT_A, uuid(3))));

        assertThat(successes(outcomes)).hasSize(2);
        assertThat(successes(outcomes)).extracting(outcome -> outcome.document().number().display())
                .containsExactlyInAnyOrder("NC-000001", "NC-000002");
        assertThat(returnsInStatus("EMITIDA")).isEqualTo(2L);
        assertThat(lastReturnNumber()).isEqualTo(2L);
        assertThat(outboxRows()).isEqualTo(2L);
    }

    @Test
    @Timeout(120)
    void RET_5_three_concurrent_emissions_of_four_over_ten_sold_only_two_fit() throws Exception {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "4"), draftReturn(original, uuid(3), "4"),
                draftReturn(original, uuid(4), "4"));

        List<Outcome> outcomes = raceBehindLock(uuid(1), List.of(
                () -> emit(TENANT_A, uuid(2)), () -> emit(TENANT_A, uuid(3)), () -> emit(TENANT_A, uuid(4))));

        assertThat(successes(outcomes)).hasSize(2);
        assertThat(failures(outcomes)).hasSize(1);
        assertThat(failures(outcomes).get(0).code()).isEqualTo(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED);
        assertThat(successes(outcomes)).extracting(outcome -> outcome.document().number().display())
                .containsExactlyInAnyOrder("NC-000001", "NC-000002");
        assertThat(returnsInStatus("EMITIDA")).isEqualTo(2L);
        assertThat(returnsInStatus("BORRADOR")).isEqualTo(1L);
        assertThat(lastReturnNumber()).isEqualTo(2L);
        assertThat(outboxRows()).isEqualTo(2L);
    }

    @Test
    @Timeout(90)
    void DRF_3_emitting_the_same_return_draft_twice_at_once_gives_both_the_same_emitted_return_and_one_number()
            throws Exception {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "3"));

        List<Outcome> outcomes = raceBehindLock(uuid(1), List.of(
                () -> emit(TENANT_A, uuid(2)), () -> emit(TENANT_A, uuid(2))));

        // idempotente: la segunda espera el bloqueo del documento, ve EMITIDA y devuelve la misma devolucion
        assertThat(successes(outcomes)).hasSize(2);
        assertThat(successes(outcomes)).extracting(outcome -> outcome.document().number().display())
                .containsOnly("NC-000001");
        assertThat(returnsInStatus("EMITIDA")).isEqualTo(1L);
        assertThat(lastReturnNumber()).isEqualTo(1L);
        assertThat(outboxRows()).isEqualTo(1L);
    }

    // ---------- ANL-2: anular la factura vs emitir una devolucion ----------

    @Test
    @Timeout(90)
    void ANL_2_annulling_the_invoice_while_a_return_is_emitted_never_leaves_an_annulled_invoice_with_an_emitted_return()
            throws Exception {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "3"));

        List<Outcome> outcomes = raceBehindLock(uuid(1), List.of(
                () -> annulInvoice(TENANT_A, uuid(1)), () -> emit(TENANT_A, uuid(2))));
        Outcome annul = outcomes.get(0);
        Outcome emit = outcomes.get(1);

        assertThat(annul.ok() ^ emit.ok()).as("exactly one of annul / emit wins").isTrue();
        if (emit.ok()) {
            assertThat(annul.code()).isEqualTo(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS);
            assertThat(statusOf(uuid(1))).isEqualTo("EMITIDA");
            assertThat(statusOf(uuid(2))).isEqualTo("EMITIDA");
            assertThat(lastReturnNumber()).isEqualTo(1L);
        } else {
            assertThat(emit.code()).isEqualTo(BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED);
            assertThat(statusOf(uuid(1))).isEqualTo("ANULADA");
            assertThat(statusOf(uuid(2))).isEqualTo("BORRADOR");
            assertThat(lastReturnNumber()).isZero();
        }
        assertThat(outboxRows()).isEqualTo(1L);
    }

    @Test
    void ANL_2_when_the_return_is_emitted_first_the_invoice_cannot_be_annulled() {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "3"));

        Outcome emit = emit(TENANT_A, uuid(2));
        Outcome annul = annulInvoice(TENANT_A, uuid(1));

        assertThat(emit.ok()).isTrue();
        assertThat(annul.ok()).isFalse();
        assertThat(annul.code()).isEqualTo(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS);
        assertThat(statusOf(uuid(1))).isEqualTo("EMITIDA");
        assertThat(outboxRows()).isEqualTo(1L);
    }

    @Test
    void RET_5_when_the_invoice_is_annulled_first_the_return_cannot_be_emitted_and_consumes_no_number() {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "3"));

        Outcome annul = annulInvoice(TENANT_A, uuid(1));
        Outcome emit = emit(TENANT_A, uuid(2));

        assertThat(annul.ok()).isTrue();
        assertThat(emit.ok()).isFalse();
        assertThat(emit.code()).isEqualTo(BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED);
        assertThat(statusOf(uuid(2))).isEqualTo("BORRADOR");
        assertThat(lastReturnNumber()).isZero();
        assertThat(outboxRows()).isEqualTo(1L);
    }

    // ---------- RET-3 / RET-7: liberar cantidad ----------

    @Test
    void RET_7_annulling_an_emitted_return_releases_its_quantity_for_another_return() {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "6"), draftReturn(original, uuid(3), "6"));

        Outcome first = emit(TENANT_A, uuid(2));
        Outcome blocked = emit(TENANT_A, uuid(3));
        long numberAfterBlocked = lastReturnNumber();
        Outcome annulled = annulReturn(TENANT_A, uuid(2));
        Outcome second = emit(TENANT_A, uuid(3));

        assertThat(first.ok()).isTrue();
        assertThat(first.document().number().display()).isEqualTo("NC-000001");
        assertThat(blocked.code()).isEqualTo(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED);
        assertThat(numberAfterBlocked).isEqualTo(1L);
        assertThat(annulled.ok()).isTrue();
        assertThat(annulled.document().number().display()).isEqualTo("NC-000001");
        assertThat(second.ok()).isTrue();
        assertThat(second.document().number().display()).isEqualTo("NC-000002");
        assertThat(statusOf(uuid(2))).isEqualTo("ANULADA");
        assertThat(statusOf(uuid(3))).isEqualTo("EMITIDA");
        assertThat(jdbc.queryForList("select event_type from billing_outbox_events order by id", String.class))
                .containsExactly("FacturaVentaDevuelta", "DevolucionVentaAnulada", "FacturaVentaDevuelta");
    }

    // ---------- TEN-1 / TEN-3: aislamiento por tenant ----------

    @Test
    void TEN_1_another_tenant_cannot_emit_a_return_of_this_tenant() {
        BillingDocument original = invoiceWithQuantity(uuid(1), 7L, "10");
        seed(TENANT_A, original, draftReturn(original, uuid(2), "3"));

        Outcome foreign = emit(TENANT_B, uuid(2));

        assertThat(foreign.ok()).isFalse();
        assertThat(foreign.code()).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertThat(statusOf(uuid(2))).isEqualTo("BORRADOR");
        assertThat(lastReturnNumber()).isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    @Timeout(90)
    void TEN_3_returns_of_two_tenants_emit_in_parallel_with_independent_numbering() throws Exception {
        BillingDocument originalA = invoiceWithQuantity(uuid(1), 7L, "10");
        BillingDocument originalB = invoiceWithQuantity(uuid(11), 7L, "10");
        seed(TENANT_A, originalA, draftReturn(originalA, uuid(2), "6"));
        seed(TENANT_B, originalB, draftReturn(originalB, uuid(12), "6"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> forA = pool.submit(() -> emit(TENANT_A, uuid(2)));
            Future<Outcome> forB = pool.submit(() -> emit(TENANT_B, uuid(12)));

            Outcome outcomeA = forA.get(60, TimeUnit.SECONDS);
            Outcome outcomeB = forB.get(60, TimeUnit.SECONDS);

            assertThat(outcomeA.ok()).isTrue();
            assertThat(outcomeB.ok()).isTrue();
            assertThat(outcomeA.document().number().display()).isEqualTo("NC-000001");
            assertThat(outcomeB.document().number().display()).isEqualTo("NC-000001");
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from billing_document_sequences where document_type = 'SALE_RETURN'"))
                .isEqualTo(2L);
        assertThat(outboxRows()).isEqualTo(2L);
        assertThat(count("select count(distinct tenant_id) from billing_outbox_events")).isEqualTo(2L);
    }
}
