package co.unicauca.edu.co.contables.billing.application.service;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.CUSTOMER_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.LATER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.NOW;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.OTHER_USER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.SUPPLIER_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TODAY;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.USER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.annulledReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.annulledSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.customer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.draftPurchase;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.draftSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedInvoice;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSaleWithLines;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnDraftOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnLineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.inactiveCustomer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.inactiveIssuer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.inactiveProduct;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.issuer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.lineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.money;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.product;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.qty;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readyPurchaseDraft;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readySaleDraft;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.supplier;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.withVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import co.unicauca.edu.co.contables.billing.application.ports.output.AccountingEntryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingDocumentRepositoryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingExecutionContextPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdGeneratorPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingOutboxPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.DocumentNumberSequencePort;
import co.unicauca.edu.co.contables.billing.application.ports.output.EnterprisePort;
import co.unicauca.edu.co.contables.billing.application.ports.output.ProductPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.ThirdPartyPort;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDocumentEmitted;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDomainEvent;
import co.unicauca.edu.co.contables.billing.domain.event.ReturnEventContext;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.CounterpartyRole;
import co.unicauca.edu.co.contables.billing.domain.model.CounterpartySnapshot;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentKind;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentLine;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.domain.model.IssuerSnapshot;
import co.unicauca.edu.co.contables.billing.domain.model.ReturnStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EMT-1..4, EVT-1 (RN-15, RN-16, HU-29): servicio de emision de facturas. Pruebas de orden de
 * llamadas (la secuencia se toma tarde, despues de toda validacion) y de que ninguna validacion
 * fallida consume numero ni escribe.
 */
@ExtendWith(MockitoExtension.class)
class BillingEmissionServiceTest {

    private static final UUID DOC_ID = uuid(1);
    private static final UUID EVENT_ID = uuid(77);

    @Mock
    private BillingDocumentRepositoryPort repository;
    @Mock
    private ThirdPartyPort thirdParties;
    @Mock
    private ProductPort products;
    @Mock
    private EnterprisePort enterprises;
    @Mock
    private DocumentNumberSequencePort sequence;
    @Mock
    private BillingOutboxPort outbox;
    @Mock
    private BillingExecutionContextPort context;
    @Mock
    private BillingClockPort clock;
    @Mock
    private BillingIdGeneratorPort ids;
    @Mock
    private StockMovementPort stock;
    @Mock
    private AccountingEntryPort accounting;

    private BillingEmissionService service;

    @BeforeEach
    void setUp() {
        lenient().when(context.username()).thenReturn(USER);
        lenient().when(clock.now()).thenReturn(NOW);
        lenient().when(ids.newId()).thenReturn(EVENT_ID);
        lenient().when(repository.save(any(BillingDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new BillingEmissionService(repository, new BillingDocumentLoader(repository),
                new BillingReferenceResolver(thirdParties, products, enterprises), sequence, outbox, context,
                clock, ids, stock, accounting);
    }

    // ---------- helpers ----------

    /** La emision carga el documento CON bloqueo de fila (serializa emisiones simultaneas, idempotencia). */
    private void stored(BillingDocument doc) {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, doc.id())).thenReturn(Optional.of(doc));
    }

    private void customerExists(CounterpartySnapshot snapshot) {
        when(thirdParties.findCounterparty(ENTERPRISE_ID, CUSTOMER_ID, CounterpartyRole.CUSTOMER))
                .thenReturn(Optional.of(snapshot));
    }

    private void supplierExists() {
        when(thirdParties.findCounterparty(ENTERPRISE_ID, SUPPLIER_ID, CounterpartyRole.SUPPLIER))
                .thenReturn(Optional.of(supplier()));
    }

    private void saleProductExists(long id) {
        when(products.findProduct(ENTERPRISE_ID, id, DocumentKind.SALE)).thenReturn(Optional.of(product(id)));
    }

    private void issuerExists() {
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.of(issuer()));
    }

    private void sequenceReturns(long value) {
        when(sequence.nextValue(ENTERPRISE_ID, DocumentType.SALE_INVOICE)).thenReturn(value);
    }

    private BillingDocument emitSale() {
        return service.emit(ENTERPRISE_ID, DocumentType.SALE_INVOICE, DOC_ID, null);
    }

    private void assertNothingConsumedOrWritten() {
        verifyNoInteractions(sequence, outbox);
        verify(repository, never()).save(any(BillingDocument.class));
    }

    private static void assertCode(Throwable thrown, BillingErrorCode code) {
        assertThat(thrown).isInstanceOf(BillingException.class);
        assertThat(((BillingException) thrown).errorCode()).isEqualTo(code);
    }

    // ---------- camino feliz y orden ----------

    @Test
    void EMT_3_emit_runs_in_order_load_third_product_enterprise_sequence_save_outbox() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);

        emitSale();

        InOrder order = inOrder(repository, thirdParties, products, enterprises, sequence, outbox);
        order.verify(repository).findByIdForUpdate(ENTERPRISE_ID, DOC_ID);
        order.verify(thirdParties).findCounterparty(ENTERPRISE_ID, CUSTOMER_ID, CounterpartyRole.CUSTOMER);
        order.verify(products).findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE);
        order.verify(enterprises).findIssuer(ENTERPRISE_ID);
        order.verify(sequence).nextValue(ENTERPRISE_ID, DocumentType.SALE_INVOICE);
        order.verify(repository).save(any(BillingDocument.class));
        order.verify(outbox).append(any(BillingDomainEvent.class));
    }

    @Test
    void EMT_4_emit_sets_number_status_issuer_and_audit() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);

        BillingDocument emitted = emitSale();

        assertThat(emitted.status()).isEqualTo(DocumentStatus.EMITIDA);
        assertThat(emitted.number().display()).isEqualTo("FV-000008");
        assertThat(emitted.issuer()).isEqualTo(issuer());
        assertThat(emitted.emittedAt()).isEqualTo(NOW);
        assertThat(emitted.emittedBy()).isEqualTo(USER);
        assertThat(emitted.totals().total().value()).isEqualByComparingTo("238.00");
    }

    @Test
    void EMT_3_the_number_comes_from_the_sequence_of_the_document_type_and_is_requested_once() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(41L);

        BillingDocument emitted = emitSale();

        assertThat(emitted.number().value()).isEqualTo(41L);
        verify(sequence, times(1)).nextValue(ENTERPRISE_ID, DocumentType.SALE_INVOICE);
    }

    @Test
    void EVT_1_emit_appends_one_emitted_event_with_the_saved_document_after_saving() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);
        doAnswer(inv -> withVersion(inv.getArgument(0), 1L)).when(repository).save(any(BillingDocument.class));

        BillingDocument result = emitSale();

        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox, times(1)).append(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BillingDocumentEmitted.class);
        BillingDocumentEmitted event = (BillingDocumentEmitted) captor.getValue();
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.document()).isSameAs(result);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(event.returnContext()).isNull();
    }

    // ---------- congelado (EMT-2): se relee todo al emitir ----------

    @Test
    void EMT_2_emit_refreshes_the_catalog_price_before_totals_are_frozen() {
        stored(readySaleDraft()); // catalogo 100.00 x2
        customerExists(customer());
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE))
                .thenReturn(Optional.of(product(1L, "200.00", "19")));
        issuerExists();
        sequenceReturns(8L);

        BillingDocument emitted = emitSale();

        assertThat(emitted.lines().get(0).unitPrice().value()).isEqualByComparingTo("200.00");
        assertThat(emitted.totals().total().value()).isEqualByComparingTo("476.00");
    }

    @Test
    void RN_10_emit_keeps_the_manual_price_override_when_the_catalog_changes() {
        BillingDocument draft = draftSale();
        draft.replaceInvoiceDraft(customer(), TODAY, null,
                List.of(DocumentLine.forInvoice(1, product(1L), qty("2"), money("150.00"), null)), NOW, USER);
        stored(draft);
        customerExists(customer());
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE))
                .thenReturn(Optional.of(product(1L, "200.00", "19")));
        issuerExists();
        sequenceReturns(8L);

        BillingDocument emitted = emitSale();

        assertThat(emitted.lines().get(0).unitPrice().value()).isEqualByComparingTo("150.00");
        assertThat(emitted.lines().get(0).catalogUnitPrice().value()).isEqualByComparingTo("200.00");
        assertThat(emitted.lines().get(0).priceOverridden()).isTrue();
    }

    @Test
    void EMT_2_emit_freezes_the_freshly_read_counterparty_snapshot() {
        CounterpartySnapshot renamed = new CounterpartySnapshot(CUSTOMER_ID, CounterpartyRole.CUSTOMER,
                "Nombre Nuevo SAS", "NIT", "900999", "1", "Otra direccion", "nuevo@example.com", "3009999999", true);
        stored(readySaleDraft());
        customerExists(renamed);
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);

        BillingDocument emitted = emitSale();

        assertThat(emitted.counterparty().name()).isEqualTo("Nombre Nuevo SAS");
        assertThat(emitted.counterparty().idNumber()).isEqualTo("900999");
    }

    @Test
    void EMT_2_each_distinct_product_is_read_only_once() {
        BillingDocument draft = draftSale();
        draft.replaceInvoiceDraft(customer(), TODAY, null,
                List.of(lineOf(1, product(1L), "1"), lineOf(2, product(1L), "2"), lineOf(3, product(2L), "1")),
                NOW, USER);
        stored(draft);
        customerExists(customer());
        saleProductExists(1L);
        saleProductExists(2L);
        issuerExists();
        sequenceReturns(8L);

        emitSale();

        verify(products, times(1)).findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE);
        verify(products, times(1)).findProduct(ENTERPRISE_ID, 2L, DocumentKind.SALE);
    }

    // ---------- compras (PUR-1, PUR-2): mismo motor ----------

    @Test
    void PUR_1_purchase_emit_uses_supplier_role_cost_reference_and_the_fc_sequence() {
        stored(readyPurchaseDraft());
        supplierExists();
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.PURCHASE)).thenReturn(Optional.of(product(1L)));
        issuerExists();
        when(sequence.nextValue(ENTERPRISE_ID, DocumentType.PURCHASE_INVOICE)).thenReturn(3L);

        BillingDocument emitted = service.emit(ENTERPRISE_ID, DocumentType.PURCHASE_INVOICE, DOC_ID, null);

        assertThat(emitted.number().display()).isEqualTo("FC-000003");
        assertThat(emitted.counterparty().role()).isEqualTo(CounterpartyRole.SUPPLIER);
        assertThat(emitted.supplierInvoiceNumber()).isEqualTo("F-77");
    }

    @Test
    void PUR_2_purchase_without_supplier_invoice_number_is_incomplete_and_consumes_no_number() {
        BillingDocument draft = draftPurchase();
        draft.replaceInvoiceDraft(supplier(), TODAY, null, List.of(lineOf(1, product(1L), "2")), NOW, USER);
        stored(draft);
        supplierExists();
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.PURCHASE)).thenReturn(Optional.of(product(1L)));
        issuerExists();

        Throwable thrown = catchThrowable(
                () -> service.emit(ENTERPRISE_ID, DocumentType.PURCHASE_INVOICE, DOC_ID, null));

        assertCode(thrown, BillingErrorCode.BILLING_DRAFT_INCOMPLETE);
        assertNothingConsumedOrWritten();
    }

    // ---------- EMT-1: validaciones sin consumir numero ----------

    @Test
    void EMT_1_draft_without_lines_is_incomplete_and_consumes_no_number() {
        BillingDocument draft = draftSale();
        draft.replaceInvoiceDraft(customer(), TODAY, null, List.of(), NOW, USER);
        stored(draft);
        customerExists(customer());
        issuerExists();

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_DRAFT_INCOMPLETE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_draft_without_counterparty_is_incomplete_and_never_queries_third_parties() {
        BillingDocument draft = draftSale();
        draft.replaceInvoiceDraft(null, TODAY, null, List.of(lineOf(1, product(1L), "2")), NOW, USER);
        stored(draft);
        saleProductExists(1L);
        issuerExists();

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_DRAFT_INCOMPLETE);
        verifyNoInteractions(thirdParties);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_zero_total_is_incomplete_and_consumes_no_number() {
        BillingDocument draft = draftSale();
        draft.replaceInvoiceDraft(customer(), TODAY, null,
                List.of(DocumentLine.forInvoice(1, product(1L), qty("2"), money("0.00"), null)), NOW, USER);
        stored(draft);
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_DRAFT_INCOMPLETE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_unknown_counterparty_is_unprocessable_and_consumes_no_number() {
        stored(readySaleDraft());
        when(thirdParties.findCounterparty(ENTERPRISE_ID, CUSTOMER_ID, CounterpartyRole.CUSTOMER))
                .thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_COUNTERPARTY_NOT_FOUND);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_inactive_counterparty_is_rejected_with_inactive_reference_and_consumes_no_number() {
        stored(readySaleDraft());
        customerExists(inactiveCustomer());

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_INACTIVE_REFERENCE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_unknown_product_is_unprocessable_and_consumes_no_number() {
        stored(readySaleDraft());
        customerExists(customer());
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_PRODUCT_NOT_FOUND);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_inactive_product_is_rejected_with_inactive_reference_and_consumes_no_number() {
        stored(readySaleDraft());
        customerExists(customer());
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE)).thenReturn(Optional.of(inactiveProduct(1L)));

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_INACTIVE_REFERENCE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_one_inactive_product_among_active_ones_rejects_the_whole_emission() {
        BillingDocument draft = draftSale();
        draft.replaceInvoiceDraft(customer(), TODAY, null,
                List.of(lineOf(1, product(1L), "1"), lineOf(2, product(2L), "1")), NOW, USER);
        stored(draft);
        customerExists(customer());
        saleProductExists(1L);
        when(products.findProduct(ENTERPRISE_ID, 2L, DocumentKind.SALE)).thenReturn(Optional.of(inactiveProduct(2L)));

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_INACTIVE_REFERENCE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_unknown_enterprise_is_unprocessable_and_consumes_no_number() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_ENTERPRISE_NOT_FOUND);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_inactive_issuer_is_rejected_with_inactive_reference_and_consumes_no_number() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        IssuerSnapshot inactive = inactiveIssuer();
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.of(inactive));

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_INACTIVE_REFERENCE);
        assertNothingConsumedOrWritten();
    }

    // ---------- estado, tipo, version ----------

    @Test
    void IDM_1_emitting_an_already_emitted_document_returns_it_as_is_and_does_nothing_else() {
        BillingDocument emitted = emittedSale();
        stored(emitted);

        BillingDocument result = emitSale();

        assertThat(result).isSameAs(emitted);
        assertThat(result.number().display()).isEqualTo("FV-000007");
        verifyNoInteractions(thirdParties, products, enterprises, stock, accounting);
        assertNothingConsumedOrWritten();
    }

    @Test
    void IDM_1_re_emitting_with_a_stale_version_still_returns_the_emitted_document() {
        BillingDocument emitted = withVersion(emittedSale(), 4L);
        stored(emitted);

        BillingDocument result = service.emit(ENTERPRISE_ID, DocumentType.SALE_INVOICE, DOC_ID, 3L);

        assertThat(result).isSameAs(emitted);
        assertNothingConsumedOrWritten();
        verifyNoInteractions(stock, accounting);
    }

    @Test
    void IDM_1_twenty_consecutive_emits_consume_a_single_number_and_append_a_single_event() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);

        BillingDocument first = emitSale();
        for (int i = 0; i < 19; i++) {
            // el repositorio devuelve el documento ya emitido, como lo haria la base de datos
            stored(first);
            assertThat(emitSale().number().display()).isEqualTo("FV-000008");
        }

        verify(sequence, times(1)).nextValue(ENTERPRISE_ID, DocumentType.SALE_INVOICE);
        verify(outbox, times(1)).append(any(BillingDomainEvent.class));
        verify(repository, times(1)).save(any(BillingDocument.class));
        verify(stock, times(1)).onEmitted(any(BillingDocument.class));
        verify(accounting, times(1)).onEmitted(any(BillingDocument.class));
    }

    // ---------- puertos de integracion dentro de la transaccion (stock / asientos) ----------

    @Test
    void INT_1_emit_calls_stock_then_accounting_after_saving_and_before_the_outbox() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);

        BillingDocument emitted = emitSale();

        InOrder order = inOrder(repository, stock, accounting, outbox);
        order.verify(repository).save(emitted);
        order.verify(stock).onEmitted(emitted);
        order.verify(accounting).onEmitted(emitted);
        order.verify(outbox).append(any(BillingDocumentEmitted.class));
    }

    @Test
    void INT_2_a_failing_stock_port_propagates_so_nothing_is_published_and_accounting_is_not_called() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);
        doThrow(new IllegalStateException("inventario no disponible")).when(stock).onEmitted(any());

        Throwable thrown = catchThrowable(this::emitSale);

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outbox, accounting);
    }

    @Test
    void INT_2_a_failing_accounting_port_propagates_so_nothing_is_published() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);
        doThrow(new IllegalStateException("contabilidad no disponible")).when(accounting).onEmitted(any());

        Throwable thrown = catchThrowable(this::emitSale);

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void EMT_4_an_annulled_document_cannot_be_emitted() {
        stored(annulledSale());

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_INVALID_STATE);
        verifyNoInteractions(thirdParties, products, enterprises);
        assertNothingConsumedOrWritten();
    }

    @Test
    void QRY_1_emitting_a_missing_document_is_not_found() {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, DOC_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertNothingConsumedOrWritten();
    }

    @Test
    void PUR_3_a_purchase_document_emitted_through_the_sale_resource_is_not_found() {
        stored(readyPurchaseDraft());

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        verifyNoInteractions(thirdParties, products, enterprises);
        assertNothingConsumedOrWritten();
    }

    @Test
    void DRF_3_a_stale_expected_version_is_a_concurrent_modification_and_reads_nothing() {
        stored(withVersion(readySaleDraft(), 3L));

        Throwable thrown = catchThrowable(
                () -> service.emit(ENTERPRISE_ID, DocumentType.SALE_INVOICE, DOC_ID, 2L));

        assertCode(thrown, BillingErrorCode.BILLING_CONCURRENT_MODIFICATION);
        verifyNoInteractions(thirdParties, products, enterprises);
        assertNothingConsumedOrWritten();
    }

    @Test
    void DRF_3_a_matching_expected_version_allows_the_emission() {
        stored(withVersion(readySaleDraft(), 3L));
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);

        BillingDocument emitted = service.emit(ENTERPRISE_ID, DocumentType.SALE_INVOICE, DOC_ID, 3L);

        assertThat(emitted.status()).isEqualTo(DocumentStatus.EMITIDA);
    }

    @Test
    void STA_3_emitting_a_missing_return_is_not_found_and_consumes_nothing() {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, DOC_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(
                () -> service.emit(ENTERPRISE_ID, DocumentType.SALE_RETURN, DOC_ID, null));

        assertCode(thrown, BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        verifyNoInteractions(thirdParties, products, enterprises);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EVT_3_the_event_of_an_invoice_carries_no_return_context_and_returns_are_never_consulted() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);

        emitSale();

        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue().returnContext()).isNull();
        verify(repository, times(1)).findByIdForUpdate(any(), any()); // solo la propia factura
        verify(repository, never()).findReturnsOf(any(), any());
    }

    // ---------- devoluciones: rama de emision (RET-3, RET-4, RET-5) ----------

    private static final UUID ORIGINAL_ID = uuid(10);

    /** Venta emitida FV-000007 con una linea de 10 unidades (100.00 + 19%). */
    private static BillingDocument saleOriginal() {
        return emittedSaleWithLines(ORIGINAL_ID, 7L, lineOf(1, product(1L), "10"));
    }

    private BillingDocument saleReturnDraft(BillingDocument original, String quantity) {
        return returnDraftOf(original, DOC_ID, "producto defectuoso", returnLineOf(original, 1, quantity));
    }

    private void originalLocked(BillingDocument original) {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, original.id())).thenReturn(Optional.of(original));
    }

    private void returnsOfOriginal(BillingDocument... returns) {
        when(repository.findReturnsOf(ENTERPRISE_ID, ORIGINAL_ID)).thenReturn(List.of(returns));
    }

    private void returnSequenceReturns(DocumentType type, long value) {
        when(sequence.nextValue(ENTERPRISE_ID, type)).thenReturn(value);
    }

    private BillingDocument emitReturn() {
        return service.emit(ENTERPRISE_ID, DocumentType.SALE_RETURN, DOC_ID, null);
    }

    private ReturnEventContext capturedReturnContext() {
        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BillingDocumentEmitted.class);
        return captor.getValue().returnContext();
    }

    @Test
    void RET_5_return_emit_runs_in_order_load_lock_original_returns_enterprise_sequence_save_outbox() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);

        BillingDocument emitted = emitReturn();

        InOrder order = inOrder(repository, enterprises, sequence, outbox);
        order.verify(repository).findByIdForUpdate(ENTERPRISE_ID, DOC_ID);
        order.verify(repository).findByIdForUpdate(ENTERPRISE_ID, ORIGINAL_ID);
        order.verify(repository).findReturnsOf(ENTERPRISE_ID, ORIGINAL_ID);
        order.verify(enterprises).findIssuer(ENTERPRISE_ID);
        order.verify(sequence).nextValue(ENTERPRISE_ID, DocumentType.SALE_RETURN);
        order.verify(repository).save(emitted);
        order.verify(outbox).append(any(BillingDocumentEmitted.class));
    }

    @Test
    void RET_1_a_sale_return_gets_an_NC_number_from_its_own_sequence_and_becomes_EMITIDA() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 3L);

        BillingDocument emitted = emitReturn();

        assertThat(emitted.status()).isEqualTo(DocumentStatus.EMITIDA);
        assertThat(emitted.number().display()).isEqualTo("NC-000003");
        assertThat(emitted.emittedAt()).isEqualTo(NOW);
        assertThat(emitted.emittedBy()).isEqualTo(USER);
        assertThat(emitted.issuer()).isEqualTo(issuer());
        verify(sequence).nextValue(ENTERPRISE_ID, DocumentType.SALE_RETURN);
        verifyNoMoreInteractions(sequence);
    }

    @Test
    void RET_1_a_purchase_return_gets_a_DC_number_from_the_purchase_return_sequence() {
        BillingDocument original = emittedInvoice(ORIGINAL_ID, DocumentType.PURCHASE_INVOICE, 7L,
                List.of(lineOf(1, product(1L), "10")));
        stored(returnDraftOf(original, DOC_ID, "mercancia danada", returnLineOf(original, 1, "2")));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.PURCHASE_RETURN, 5L);

        BillingDocument emitted = service.emit(ENTERPRISE_ID, DocumentType.PURCHASE_RETURN, DOC_ID, null);

        assertThat(emitted.number().display()).isEqualTo("DC-000005");
        verify(sequence).nextValue(ENTERPRISE_ID, DocumentType.PURCHASE_RETURN);
        verifyNoMoreInteractions(sequence);
    }

    @Test
    void RET_1_the_return_keeps_the_counterparty_of_the_original_without_consulting_thirds_or_products() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);

        BillingDocument emitted = emitReturn();

        assertThat(emitted.counterparty()).isEqualTo(original.counterparty());
        verifyNoInteractions(thirdParties, products);
    }

    @Test
    void RET_8_the_original_is_locked_but_never_saved() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);

        emitReturn();

        verify(repository, never()).save(original);
    }

    @Test
    void RET_10_the_event_carries_ordinal_1_and_PARCIAL_for_the_first_partial_return() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);

        emitReturn();

        ReturnEventContext returnContext = capturedReturnContext();
        assertThat(returnContext.ordinal()).isEqualTo(1);
        assertThat(returnContext.originalStatusAfter()).isEqualTo(ReturnStatus.PARCIAL);
    }

    @Test
    void RET_9_the_event_reports_TOTAL_when_the_return_completes_the_sold_quantity() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "6"));
        originalLocked(original);
        returnsOfOriginal(emittedReturnOf(original, uuid(20), 1L, returnLineOf(original, 1, "4")));
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 2L);

        emitReturn();

        ReturnEventContext returnContext = capturedReturnContext();
        assertThat(returnContext.ordinal()).isEqualTo(2);
        assertThat(returnContext.originalStatusAfter()).isEqualTo(ReturnStatus.TOTAL);
    }

    @Test
    void RET_10_an_earlier_annulled_return_keeps_its_ordinal() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal(annulledReturnOf(original, uuid(20), 1L, returnLineOf(original, 1, "3")));
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 2L);

        emitReturn();

        ReturnEventContext returnContext = capturedReturnContext();
        assertThat(returnContext.ordinal()).isEqualTo(2);
        assertThat(returnContext.originalStatusAfter()).isEqualTo(ReturnStatus.PARCIAL);
    }

    @Test
    void EVT_1_the_return_event_is_BillingDocumentEmitted_with_the_saved_document_and_a_new_id() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);

        BillingDocument emitted = emitReturn();

        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        BillingDocumentEmitted event = (BillingDocumentEmitted) captor.getValue();
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.document()).isSameAs(emitted);
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void RET_5_an_original_annulled_while_the_return_was_a_draft_is_ORIGINAL_NOT_EMITTED_and_consumes_nothing() {
        BillingDocument original = saleOriginal();
        BillingDocument draft = saleReturnDraft(original, "4");
        original.annul("error", false, LATER, OTHER_USER);
        stored(draft);
        originalLocked(original);

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED);
        verifyNoInteractions(enterprises);
        verify(repository, never()).findReturnsOf(any(), any());
        assertNothingConsumedOrWritten();
    }

    @Test
    void RET_5_a_missing_original_is_not_found_and_consumes_nothing() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        when(repository.findByIdForUpdate(ENTERPRISE_ID, ORIGINAL_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertNothingConsumedOrWritten();
    }

    @Test
    void RET_3_quantities_are_revalidated_under_the_lock_against_returns_emitted_meanwhile() {
        BillingDocument original = saleOriginal();
        BillingDocument draft = saleReturnDraft(original, "6");
        stored(draft);
        originalLocked(original);
        // otra devolucion de 6 se emitio mientras esta era borrador; el borrador mismo figura en la lista
        returnsOfOriginal(draft, emittedReturnOf(original, uuid(20), 1L, returnLineOf(original, 1, "6")));

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED);
        verifyNoInteractions(enterprises);
        assertNothingConsumedOrWritten();
    }

    @Test
    void RET_3_the_draft_itself_and_annulled_returns_do_not_count_against_the_availability() {
        BillingDocument original = saleOriginal();
        BillingDocument draft = saleReturnDraft(original, "10");
        stored(draft);
        originalLocked(original);
        returnsOfOriginal(draft, annulledReturnOf(original, uuid(20), 1L, returnLineOf(original, 1, "10")));
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);

        BillingDocument emitted = emitReturn();

        assertThat(emitted.status()).isEqualTo(DocumentStatus.EMITIDA);
        assertThat(capturedReturnContext().originalStatusAfter()).isEqualTo(ReturnStatus.TOTAL);
    }

    @Test
    void RET_4_a_return_without_reason_is_DRAFT_INCOMPLETE_and_consumes_no_number() {
        BillingDocument original = saleOriginal();
        stored(returnDraftOf(original, DOC_ID, null, returnLineOf(original, 1, "4")));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_DRAFT_INCOMPLETE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void RET_4_a_return_without_lines_is_DRAFT_INCOMPLETE_and_consumes_no_number() {
        BillingDocument original = saleOriginal();
        stored(returnDraftOf(original, DOC_ID, "motivo"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_DRAFT_INCOMPLETE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_1_an_inactive_issuer_rejects_the_return_emission_without_consuming_a_number() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.of(inactiveIssuer()));

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_INACTIVE_REFERENCE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void IDM_2_emitting_an_already_emitted_return_returns_it_as_is_without_locking_the_original() {
        BillingDocument original = saleOriginal();
        BillingDocument emittedReturn = emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4"));
        stored(emittedReturn);

        BillingDocument result = emitReturn();

        assertThat(result).isSameAs(emittedReturn);
        verify(repository, never()).findByIdForUpdate(ENTERPRISE_ID, ORIGINAL_ID);
        verifyNoInteractions(stock, accounting);
        assertNothingConsumedOrWritten();
    }

    @Test
    void INT_2_a_failing_stock_port_aborts_the_return_emission_before_the_event() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);
        doThrow(new IllegalStateException("no se pudo reponer el inventario")).when(stock).onEmitted(any());

        Throwable thrown = catchThrowable(this::emitReturn);

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outbox, accounting);
    }

    @Test
    void INT_1_return_emit_calls_stock_then_accounting_after_saving_and_before_the_outbox() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);

        BillingDocument emitted = emitReturn();

        InOrder order = inOrder(repository, stock, accounting, outbox);
        order.verify(repository).save(emitted);
        order.verify(stock).onEmitted(emitted);
        order.verify(accounting).onEmitted(emitted);
        order.verify(outbox).append(any(BillingDocumentEmitted.class));
    }

    @Test
    void EMT_4_an_annulled_return_cannot_be_emitted() {
        BillingDocument original = saleOriginal();
        stored(annulledReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_INVALID_STATE);
        assertNothingConsumedOrWritten();
    }

    @Test
    void PUR_3_an_invoice_emitted_through_the_return_resource_is_not_found() {
        stored(readySaleDraft());

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        verify(repository, times(1)).findByIdForUpdate(any(), any()); // solo la propia, nunca la original
        assertNothingConsumedOrWritten();
    }

    @Test
    void PUR_3_a_purchase_return_emitted_through_the_sale_return_resource_is_not_found() {
        BillingDocument original = emittedInvoice(ORIGINAL_ID, DocumentType.PURCHASE_INVOICE, 7L,
                List.of(lineOf(1, product(1L), "10")));
        stored(returnDraftOf(original, DOC_ID, "motivo", returnLineOf(original, 1, "2")));

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertNothingConsumedOrWritten();
    }

    @Test
    void DRF_3_a_stale_expected_version_rejects_the_return_emission_before_locking_the_original() {
        BillingDocument original = saleOriginal();
        stored(withVersion(saleReturnDraft(original, "4"), 3L));

        Throwable thrown = catchThrowable(
                () -> service.emit(ENTERPRISE_ID, DocumentType.SALE_RETURN, DOC_ID, 2L));

        assertCode(thrown, BillingErrorCode.BILLING_CONCURRENT_MODIFICATION);
        verify(repository, times(1)).findByIdForUpdate(any(), any()); // solo la propia, nunca la original
        assertNothingConsumedOrWritten();
    }

    @Test
    void EMT_3_a_failing_sequence_for_a_return_propagates_before_saving_or_publishing() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        when(sequence.nextValue(ENTERPRISE_ID, DocumentType.SALE_RETURN))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_SEQUENCE_BUSY));

        Throwable thrown = catchThrowable(this::emitReturn);

        assertCode(thrown, BillingErrorCode.BILLING_SEQUENCE_BUSY);
        verify(repository, never()).save(any(BillingDocument.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void EVT_1_a_failing_outbox_append_for_a_return_propagates_so_the_transaction_rolls_back() {
        BillingDocument original = saleOriginal();
        stored(saleReturnDraft(original, "4"));
        originalLocked(original);
        returnsOfOriginal();
        issuerExists();
        returnSequenceReturns(DocumentType.SALE_RETURN, 1L);
        IllegalStateException failure = new IllegalStateException("outbox down");
        doThrow(failure).when(outbox).append(any(BillingDomainEvent.class));

        Throwable thrown = catchThrowable(this::emitReturn);

        assertThat(thrown).isSameAs(failure);
    }

    // ---------- atomicidad: los fallos de escritura se propagan ----------

    @Test
    void EVT_1_a_failing_save_propagates_and_no_event_is_appended() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);
        doThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION))
                .when(repository).save(any(BillingDocument.class));

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_CONCURRENT_MODIFICATION);
        verify(outbox, never()).append(any(BillingDomainEvent.class));
    }

    @Test
    void EVT_1_a_failing_outbox_append_propagates_so_the_transaction_rolls_back() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        sequenceReturns(8L);
        IllegalStateException failure = new IllegalStateException("outbox down");
        doThrow(failure).when(outbox).append(any(BillingDomainEvent.class));

        Throwable thrown = catchThrowable(this::emitSale);

        assertThat(thrown).isSameAs(failure);
    }

    @Test
    void EMT_3_a_failing_sequence_propagates_before_anything_is_saved() {
        stored(readySaleDraft());
        customerExists(customer());
        saleProductExists(1L);
        issuerExists();
        when(sequence.nextValue(ENTERPRISE_ID, DocumentType.SALE_INVOICE))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_SEQUENCE_BUSY));

        Throwable thrown = catchThrowable(this::emitSale);

        assertCode(thrown, BillingErrorCode.BILLING_SEQUENCE_BUSY);
        verify(repository, never()).save(any(BillingDocument.class));
        verifyNoInteractions(outbox);
    }
}
