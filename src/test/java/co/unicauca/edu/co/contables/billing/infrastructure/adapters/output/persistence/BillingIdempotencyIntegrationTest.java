package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.CUSTOMER_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.OTHER_ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TENANT_A;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TENANT_B;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.customer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSaleWithLines;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.issuer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.lineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.product;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readySaleDraft;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnDraftOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnLineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceLineCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnLineCommand;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyRecord;
import co.unicauca.edu.co.contables.billing.application.service.BillingAnnulmentService;
import co.unicauca.edu.co.contables.billing.application.service.BillingDocumentLoader;
import co.unicauca.edu.co.contables.billing.application.service.BillingEmissionService;
import co.unicauca.edu.co.contables.billing.application.service.BillingIdempotentCreationService;
import co.unicauca.edu.co.contables.billing.application.service.BillingInvoiceDraftService;
import co.unicauca.edu.co.contables.billing.application.service.BillingReferenceResolver;
import co.unicauca.edu.co.contables.billing.application.service.BillingRequestFingerprint;
import co.unicauca.edu.co.contables.billing.application.service.BillingReturnDraftService;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.context.BillingClockAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.context.BillingExecutionContextAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.messaging.BillingOutboxAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.transaction.BillingTransactionRunnerAdapter;
import co.unicauca.edu.co.contables.billing.support.FakeIntegrationPort;
import co.unicauca.edu.co.contables.commons.multitenancy.utils.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Idempotencia y transaccion de punta a punta (IDM-1..5, INT-1..3) con bloqueos y restricciones
 * REALES (H2 en modo PostgreSQL) sobre los servicios reales de creacion idempotente, emision y
 * anulacion y los adaptadores reales de persistencia, secuencia y outbox. Cada operacion corre en su
 * propia transaccion (sin transaccion de prueba) y el tenant se fija ANTES de abrirla.
 *
 * <p>Lo que se demuestra: 20 repeticiones o 8 hilos simultaneos producen UN documento / UN numero /
 * UN evento; y que si un puerto de integracion (stock, asientos) falla, la base queda EXACTAMENTE como
 * estaba (estado, consecutivo y outbox).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing_idempotency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = BillingJpaTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BillingIdempotencyIntegrationTest {

    private static final DocumentType SALE = DocumentType.SALE_INVOICE;
    private static final DocumentType SALE_RETURN = DocumentType.SALE_RETURN;
    private static final String KEY = "6f1c1f3a-3e56-4c7a-9a0e-0d3a5c8d1b11";
    private static final int THREADS = 8;

    @Autowired
    private BillingDocumentPersistenceAdapter adapter;
    @Autowired
    private BillingDocumentSequenceAdapter sequence;
    @Autowired
    private BillingOutboxAdapter outbox;
    @Autowired
    private BillingIdempotencyAdapter idempotency;
    @Autowired
    private BillingTransactionRunnerAdapter transactionRunner;
    @Autowired
    private BillingExecutionContextAdapter executionContext;
    @Autowired
    private BillingClockAdapter clock;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final FakeIntegrationPort stock = new FakeIntegrationPort();
    private final FakeIntegrationPort accounting = new FakeIntegrationPort();

    private BillingIdempotentCreationService creation;
    private BillingInvoiceDraftService invoiceDrafts;
    private BillingReturnDraftService returnDrafts;
    private BillingEmissionService emission;
    private BillingAnnulmentService annulment;

    @BeforeEach
    void buildRealServices() {
        BillingDocumentLoader loader = new BillingDocumentLoader(adapter);
        BillingReferenceResolver resolver = new BillingReferenceResolver(
                (enterprise, thirdId, role) -> Optional.of(customer(thirdId)),
                (enterprise, productId, kind) -> Optional.of(product(productId)),
                enterprise -> Optional.of(issuer()));
        creation = new BillingIdempotentCreationService(idempotency, adapter, transactionRunner, clock);
        invoiceDrafts = new BillingInvoiceDraftService(adapter, loader, resolver, executionContext, clock,
                UUID::randomUUID);
        returnDrafts = new BillingReturnDraftService(adapter, loader, executionContext, clock, UUID::randomUUID);
        emission = new BillingEmissionService(adapter, loader, resolver, sequence, outbox, executionContext, clock,
                UUID::randomUUID, stock, accounting);
        annulment = new BillingAnnulmentService(adapter, loader, outbox, executionContext, clock, UUID::randomUUID,
                stock, accounting);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        jdbc.update("delete from billing_idempotency_keys");
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

    private Outcome attemptTx(String tenant, Supplier<BillingDocument> action) {
        try {
            return new Outcome(inTenantTx(tenant, action), null);
        } catch (Throwable thrown) {
            return new Outcome(null, thrown);
        }
    }

    /** Ejecuta sin transaccion propia: es el servicio idempotente quien abre las suyas. */
    private Outcome attemptOutsideTx(String tenant, Supplier<BillingDocument> action) {
        TenantContext.setTenantId(tenant);
        try {
            return new Outcome(action.get(), null);
        } catch (Throwable thrown) {
            return new Outcome(null, thrown);
        } finally {
            TenantContext.clear();
        }
    }

    private static InvoiceDraftCommand invoiceCommand(String quantity) {
        return new InvoiceDraftCommand(CUSTOMER_ID, null, null,
                List.of(new InvoiceLineCommand(1L, new BigDecimal(quantity), null, null)), null);
    }

    private BillingDocument createInvoice(String enterpriseId, String key, String quantity) {
        InvoiceDraftCommand command = invoiceCommand(quantity);
        return creation.create(enterpriseId, SALE, key, BillingRequestFingerprint.of(SALE, command),
                () -> invoiceDrafts.create(enterpriseId, SALE, command));
    }

    private Outcome createInvoiceAs(String tenant, String key, String quantity) {
        return attemptOutsideTx(tenant, () -> createInvoice(ENTERPRISE_ID, key, quantity));
    }

    private Outcome emit(String tenant, UUID id) {
        return attemptTx(tenant, () -> emission.emit(ENTERPRISE_ID, SALE, id, null));
    }

    private Outcome annul(String tenant, UUID id) {
        return attemptTx(tenant, () -> annulment.annul(ENTERPRISE_ID, SALE, id, "error de digitacion", null));
    }

    private void seed(String tenant, BillingDocument... documents) {
        inTenantTx(tenant, () -> {
            for (BillingDocument document : documents) {
                adapter.save(document);
            }
            return null;
        });
    }

    /** Lanza las tareas a la vez (compuerta comun) y devuelve sus resultados en orden. */
    private List<Outcome> race(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(90, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Callable<Outcome>> times(int n, Callable<Outcome> task) {
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(task);
        }
        return tasks;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private long documents() {
        return count("select count(*) from billing_documents");
    }

    private long keys() {
        return count("select count(*) from billing_idempotency_keys");
    }

    private long outboxRows() {
        return count("select count(*) from billing_outbox_events");
    }

    private long lastNumber(String documentType) {
        return count("select coalesce(max(last_number), 0) from billing_document_sequences where document_type = ?",
                documentType);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from billing_documents where cast(id as varchar) = ?",
                String.class, id.toString());
    }

    private static List<Outcome> successes(List<Outcome> outcomes) {
        return outcomes.stream().filter(Outcome::ok).toList();
    }

    private static Set<UUID> idsOf(List<Outcome> outcomes) {
        Set<UUID> ids = new HashSet<>();
        outcomes.forEach(outcome -> ids.add(outcome.document().id()));
        return ids;
    }

    // ---------- IDM-1: crear con la misma clave ----------

    @Test
    void IDM_1_twenty_sequential_creations_with_the_same_key_produce_one_document() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ids.add(createInvoiceAs(TENANT_A, KEY, "2").document().id());
        }

        assertThat(ids).hasSize(1);
        assertThat(documents()).isEqualTo(1L);
        assertThat(keys()).isEqualTo(1L);
    }

    @Test
    @Timeout(120)
    void IDM_3_eight_concurrent_creations_with_the_same_key_produce_exactly_one_document() throws Exception {
        List<Outcome> outcomes = race(times(THREADS, () -> createInvoiceAs(TENANT_A, KEY, "2")));

        assertThat(successes(outcomes)).as("todas responden el mismo resultado").hasSize(THREADS);
        assertThat(idsOf(outcomes)).hasSize(1);
        assertThat(documents()).isEqualTo(1L);
        assertThat(keys()).isEqualTo(1L);
    }

    @Test
    @Timeout(120)
    void IDM_3_forced_collision_both_create_but_the_loser_is_rolled_back_and_returns_the_winners_document()
            throws Exception {
        // Una barrera dentro del creador obliga a que las dos peticiones hayan creado su documento ANTES de
        // registrar la clave: la restriccion unica de la base decide quien gana y el perdedor se revierte.
        CyclicBarrier bothCreated = new CyclicBarrier(2);
        AtomicInteger creations = new AtomicInteger();
        InvoiceDraftCommand command = invoiceCommand("2");
        Callable<Outcome> task = () -> attemptOutsideTx(TENANT_A, () -> creation.create(ENTERPRISE_ID, SALE, KEY,
                BillingRequestFingerprint.of(SALE, command), () -> {
                    BillingDocument document = invoiceDrafts.create(ENTERPRISE_ID, SALE, command);
                    creations.incrementAndGet();
                    try {
                        bothCreated.await(20, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new IllegalStateException("la barrera fallo", ex);
                    }
                    return document;
                }));

        List<Outcome> outcomes = race(List.of(task, task));

        assertThat(successes(outcomes)).hasSize(2);
        assertThat(creations).as("las dos llegaron a crear su documento").hasValue(2);
        assertThat(idsOf(outcomes)).as("pero ambas responden el del ganador").hasSize(1);
        assertThat(documents()).as("el del perdedor se revirtio").isEqualTo(1L);
        assertThat(keys()).isEqualTo(1L);
    }

    @Test
    @Timeout(120)
    void IDM_3_concurrent_creations_with_different_keys_produce_one_document_each() throws Exception {
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String key = UUID.randomUUID().toString();
            tasks.add(() -> createInvoiceAs(TENANT_A, key, "2"));
            tasks.add(() -> createInvoiceAs(TENANT_A, key, "2"));
        }

        List<Outcome> outcomes = race(tasks);

        assertThat(successes(outcomes)).hasSize(8);
        assertThat(idsOf(outcomes)).hasSize(4);
        assertThat(documents()).isEqualTo(4L);
        assertThat(keys()).isEqualTo(4L);
    }

    @Test
    void IDM_2_the_same_key_with_a_different_body_is_an_idempotency_conflict_and_creates_nothing() {
        createInvoiceAs(TENANT_A, KEY, "2");

        Outcome conflict = createInvoiceAs(TENANT_A, KEY, "3");

        assertThat(conflict.ok()).isFalse();
        assertThat(conflict.code()).isEqualTo(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        assertThat(documents()).isEqualTo(1L);
    }

    @Test
    void IDM_2_a_concurrent_request_with_the_same_key_and_another_body_gets_the_conflict() throws Exception {
        List<Outcome> outcomes = race(List.of(
                () -> createInvoiceAs(TENANT_A, KEY, "2"), () -> createInvoiceAs(TENANT_A, KEY, "3")));

        assertThat(successes(outcomes)).hasSize(1);
        Outcome loser = outcomes.stream().filter(outcome -> !outcome.ok()).findFirst().orElseThrow();
        assertThat(loser.code()).isEqualTo(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        assertThat(documents()).isEqualTo(1L);
        assertThat(keys()).isEqualTo(1L);
    }

    @Test
    void IDM_1_without_idempotency_every_creation_makes_a_new_draft_as_before() {
        inTenantTx(TENANT_A, () -> invoiceDrafts.create(ENTERPRISE_ID, SALE, invoiceCommand("2")));
        inTenantTx(TENANT_A, () -> invoiceDrafts.create(ENTERPRISE_ID, SALE, invoiceCommand("2")));

        assertThat(documents()).isEqualTo(2L);
        assertThat(keys()).isZero();
    }

    @Test
    void IDM_5_a_creation_that_fails_after_saving_rolls_back_the_document_and_does_not_burn_the_key() {
        InvoiceDraftCommand command = invoiceCommand("2");
        Outcome failed = attemptOutsideTx(TENANT_A, () -> creation.create(ENTERPRISE_ID, SALE, KEY,
                BillingRequestFingerprint.of(SALE, command), () -> {
                    invoiceDrafts.create(ENTERPRISE_ID, SALE, command);
                    throw new IllegalStateException("fallo despues de guardar");
                }));

        assertThat(failed.error()).isInstanceOf(IllegalStateException.class);
        assertThat(documents()).isZero();
        assertThat(keys()).isZero();
        // la clave sigue libre: el reintento crea el documento
        assertThat(createInvoiceAs(TENANT_A, KEY, "2").ok()).isTrue();
        assertThat(documents()).isEqualTo(1L);
    }

    @Test
    void IDM_1_an_expired_key_is_ignored_and_can_be_reused_for_a_new_document() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(3));
        UUID staleDocument = uuid(99);
        inTenantTx(TENANT_A, () -> {
            idempotency.register(new IdempotencyRecord(ENTERPRISE_ID, KEY, "otra-huella", SALE, staleDocument,
                    longAgo, longAgo.plus(Duration.ofHours(24))));
            return null;
        });
        assertThat(keys()).isEqualTo(1L);

        Outcome outcome = createInvoiceAs(TENANT_A, KEY, "2");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.document().id()).isNotEqualTo(staleDocument);
        assertThat(keys()).isEqualTo(1L);
        // y ahora la clave nueva vuelve a ser idempotente
        assertThat(createInvoiceAs(TENANT_A, KEY, "2").document().id()).isEqualTo(outcome.document().id());
    }

    @Test
    void IDM_1_the_same_key_in_another_tenant_or_enterprise_is_an_independent_key() {
        Outcome tenantA = createInvoiceAs(TENANT_A, KEY, "2");
        Outcome tenantB = createInvoiceAs(TENANT_B, KEY, "2");
        Outcome otherEnterprise = attemptOutsideTx(TENANT_A, () -> createInvoice(OTHER_ENTERPRISE_ID, KEY, "2"));

        assertThat(tenantA.ok() && tenantB.ok() && otherEnterprise.ok()).isTrue();
        assertThat(Set.of(tenantA.document().id(), tenantB.document().id(), otherEnterprise.document().id()))
                .hasSize(3);
        assertThat(keys()).isEqualTo(3L);
    }

    @Test
    @Timeout(120)
    void IDM_1_returns_are_idempotent_too_twenty_creations_and_concurrent_ones_make_a_single_return()
            throws Exception {
        BillingDocument original = emittedSaleWithLines(uuid(1), 7L, lineOf(1, product(1L), "10"));
        seed(TENANT_A, original);
        ReturnDraftCommand command = new ReturnDraftCommand(uuid(1), null, "defectuoso",
                List.of(new ReturnLineCommand(1, new BigDecimal("2"))), null);
        Callable<Outcome> createReturn = () -> attemptOutsideTx(TENANT_A, () -> creation.create(ENTERPRISE_ID,
                SALE_RETURN, KEY, BillingRequestFingerprint.of(SALE_RETURN, command),
                () -> returnDrafts.create(ENTERPRISE_ID, SALE_RETURN, command)));

        List<Outcome> concurrent = race(times(THREADS, createReturn));
        Set<UUID> ids = idsOf(concurrent);
        for (int i = 0; i < 20; i++) {
            ids.add(createReturn.call().document().id());
        }

        assertThat(successes(concurrent)).hasSize(THREADS);
        assertThat(ids).hasSize(1);
        assertThat(count("select count(*) from billing_documents where document_type = 'SALE_RETURN'"))
                .isEqualTo(1L);
        assertThat(keys()).isEqualTo(1L);
    }

    // ---------- IDM-1: emitir / anular N veces ----------

    @Test
    @Timeout(120)
    void IDM_1_eight_concurrent_emits_of_the_same_invoice_consume_one_number_and_publish_one_event()
            throws Exception {
        seed(TENANT_A, readySaleDraft());

        List<Outcome> outcomes = race(times(THREADS, () -> emit(TENANT_A, uuid(1))));

        assertThat(successes(outcomes)).hasSize(THREADS);
        assertThat(successes(outcomes)).extracting(outcome -> outcome.document().number().display())
                .containsOnly("FV-000001");
        assertThat(statusOf(uuid(1))).isEqualTo("EMITIDA");
        assertThat(lastNumber("SALE_INVOICE")).isEqualTo(1L);
        assertThat(outboxRows()).isEqualTo(1L);
        assertThat(stock.emittedCalls()).isEqualTo(1);
        assertThat(accounting.emittedCalls()).isEqualTo(1);
    }

    @Test
    void IDM_1_twenty_sequential_emits_consume_one_number_and_publish_one_event() {
        seed(TENANT_A, readySaleDraft());

        for (int i = 0; i < 20; i++) {
            Outcome outcome = emit(TENANT_A, uuid(1));
            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.document().number().display()).isEqualTo("FV-000001");
        }

        assertThat(lastNumber("SALE_INVOICE")).isEqualTo(1L);
        assertThat(outboxRows()).isEqualTo(1L);
        assertThat(stock.emittedCalls()).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void IDM_1_eight_concurrent_annuls_of_the_same_invoice_make_one_annulment_and_publish_one_event()
            throws Exception {
        seed(TENANT_A, emittedSale());

        List<Outcome> outcomes = race(times(THREADS, () -> annul(TENANT_A, uuid(1))));

        assertThat(successes(outcomes)).hasSize(THREADS);
        assertThat(statusOf(uuid(1))).isEqualTo("ANULADA");
        assertThat(outboxRows()).isEqualTo(1L);
        assertThat(stock.annulledCalls()).isEqualTo(1);
        assertThat(accounting.annulledCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from billing_documents", Long.class)).isEqualTo(1L);
    }

    @Test
    void IDM_1_twenty_sequential_annuls_publish_one_event() {
        seed(TENANT_A, emittedSale());

        for (int i = 0; i < 20; i++) {
            assertThat(annul(TENANT_A, uuid(1)).ok()).isTrue();
        }

        assertThat(outboxRows()).isEqualTo(1L);
        assertThat(stock.annulledCalls()).isEqualTo(1);
    }

    @Test
    void EMT_4_emitting_an_annulled_invoice_is_still_invalid_state() {
        seed(TENANT_A, emittedSale());
        annul(TENANT_A, uuid(1));

        Outcome outcome = emit(TENANT_A, uuid(1));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.code()).isEqualTo(BillingErrorCode.BILLING_INVALID_STATE);
    }

    @Test
    void ANL_1_annulling_a_draft_is_still_invalid_state() {
        seed(TENANT_A, readySaleDraft());

        Outcome outcome = annul(TENANT_A, uuid(1));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.code()).isEqualTo(BillingErrorCode.BILLING_INVALID_STATE);
        assertThat(statusOf(uuid(1))).isEqualTo("BORRADOR");
    }

    // ---------- INT-2: si un puerto falla, todo queda como estaba ----------

    private void assertInvoiceStillDraft() {
        assertThat(statusOf(uuid(1))).isEqualTo("BORRADOR");
        assertThat(count("select count(*) from billing_documents where number_value is not null")).isZero();
        assertThat(lastNumber("SALE_INVOICE")).as("consecutivo consumido").isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void INT_2_when_the_stock_port_fails_the_emission_is_rolled_back_completely() {
        seed(TENANT_A, readySaleDraft());
        stock.failWith(new IllegalStateException("inventario no disponible"));

        Outcome outcome = emit(TENANT_A, uuid(1));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).isInstanceOf(IllegalStateException.class);
        assertInvoiceStillDraft();
    }

    @Test
    void INT_2_when_the_accounting_port_fails_the_emission_is_rolled_back_even_if_stock_had_already_run() {
        seed(TENANT_A, readySaleDraft());
        accounting.failWith(new IllegalStateException("contabilidad no disponible"));

        Outcome outcome = emit(TENANT_A, uuid(1));

        assertThat(outcome.ok()).isFalse();
        assertThat(stock.emittedCalls()).isEqualTo(1);
        assertInvoiceStillDraft();
    }

    @Test
    void INT_2_after_a_failed_emission_the_retry_gets_the_first_number_without_gaps() {
        seed(TENANT_A, readySaleDraft());
        stock.failWith(new IllegalStateException("inventario no disponible"));
        emit(TENANT_A, uuid(1));
        stock.recover();

        Outcome retry = emit(TENANT_A, uuid(1));

        assertThat(retry.ok()).isTrue();
        assertThat(retry.document().number().display()).isEqualTo("FV-000001");
        assertThat(outboxRows()).isEqualTo(1L);
    }

    @Test
    void INT_2_when_a_port_fails_the_annulment_is_rolled_back_and_the_invoice_stays_emitted() {
        seed(TENANT_A, emittedSale());
        accounting.failWith(new IllegalStateException("contabilidad no disponible"));

        Outcome outcome = annul(TENANT_A, uuid(1));

        assertThat(outcome.ok()).isFalse();
        assertThat(statusOf(uuid(1))).isEqualTo("EMITIDA");
        assertThat(count("select count(*) from billing_documents where annul_reason is not null")).isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void INT_2_when_the_stock_port_fails_the_return_is_not_emitted_and_consumes_no_number() {
        BillingDocument original = emittedSaleWithLines(uuid(1), 7L, lineOf(1, product(1L), "10"));
        seed(TENANT_A, original, returnDraftOf(original, uuid(2), "defectuoso", returnLineOf(original, 1, "3")));
        stock.failWith(new IllegalStateException("no se pudo reponer el inventario"));

        Outcome outcome = attemptTx(TENANT_A, () -> emission.emit(ENTERPRISE_ID, SALE_RETURN, uuid(2), null));

        assertThat(outcome.ok()).isFalse();
        assertThat(statusOf(uuid(2))).isEqualTo("BORRADOR");
        assertThat(lastNumber("SALE_RETURN")).isZero();
        assertThat(outboxRows()).isZero();

        stock.recover();
        Outcome retry = attemptTx(TENANT_A, () -> emission.emit(ENTERPRISE_ID, SALE_RETURN, uuid(2), null));
        assertThat(retry.document().number().display()).isEqualTo("NC-000001");
        assertThat(outboxRows()).isEqualTo(1L);
    }

    @Test
    void INT_2_when_a_port_fails_the_return_annulment_is_rolled_back() {
        BillingDocument original = emittedSaleWithLines(uuid(1), 7L, lineOf(1, product(1L), "10"));
        seed(TENANT_A, original, emittedReturnOf(original, uuid(2), 1L, returnLineOf(original, 1, "3")));
        stock.failWith(new IllegalStateException("no se pudo descontar el inventario"));

        Outcome outcome = attemptTx(TENANT_A,
                () -> annulment.annul(ENTERPRISE_ID, SALE_RETURN, uuid(2), "cliente desistio", null));

        assertThat(outcome.ok()).isFalse();
        assertThat(statusOf(uuid(2))).isEqualTo("EMITIDA");
        assertThat(outboxRows()).isZero();
    }
}
