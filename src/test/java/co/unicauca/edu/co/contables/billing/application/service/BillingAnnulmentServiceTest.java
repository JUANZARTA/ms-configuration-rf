package co.unicauca.edu.co.contables.billing.application.service;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.NOW;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.USER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.annulledReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.annulledSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedInvoice;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedPurchase;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSaleWithLines;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.lineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.product;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readySaleDraft;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnDraftOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnLineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.withVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDocumentAnnulled;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDomainEvent;
import co.unicauca.edu.co.contables.billing.domain.event.ReturnEventContext;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
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
 * ANL-1, ANL-2, EVT-1 (RN-08, HU-10): servicio de anulacion. El documento se carga CON BLOQUEO de
 * fila (serializa contra la emision de devoluciones, D12), se consulta si hay devoluciones emitidas,
 * se anula, se guarda y se agrega el evento al outbox en la misma transaccion.
 */
@ExtendWith(MockitoExtension.class)
class BillingAnnulmentServiceTest {

    private static final UUID DOC_ID = uuid(1);
    private static final UUID EVENT_ID = uuid(88);
    private static final String REASON = "error de digitacion";
    private static final DocumentType SALE = DocumentType.SALE_INVOICE;

    @Mock
    private BillingDocumentRepositoryPort repository;
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

    private BillingAnnulmentService service;

    @BeforeEach
    void setUp() {
        lenient().when(context.username()).thenReturn(USER);
        lenient().when(clock.now()).thenReturn(NOW);
        lenient().when(ids.newId()).thenReturn(EVENT_ID);
        lenient().when(repository.save(any(BillingDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.existsReturnInStatus(anyString(), any(UUID.class), any(DocumentStatus.class)))
                .thenReturn(false);
        service = new BillingAnnulmentService(repository, new BillingDocumentLoader(repository), outbox, context,
                clock, ids, stock, accounting);
    }

    // ---------- helpers ----------

    private void lockedStored(BillingDocument doc) {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, doc.id())).thenReturn(Optional.of(doc));
    }

    private BillingDocument annulSale(String reason) {
        return service.annul(ENTERPRISE_ID, SALE, DOC_ID, reason, null);
    }

    private static BillingErrorCode codeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BillingException.class);
        return ((BillingException) thrown).errorCode();
    }

    private void assertNothingWritten() {
        verify(repository, never()).save(any(BillingDocument.class));
        verify(outbox, never()).append(any(BillingDomainEvent.class));
    }

    // ---------- camino feliz ----------

    @Test
    void ANL_1_happy_path_locks_checks_returns_annuls_saves_then_appends_event_in_that_order() {
        lockedStored(emittedSale());

        BillingDocument result = annulSale(REASON);

        InOrder order = inOrder(repository, outbox);
        order.verify(repository).findByIdForUpdate(ENTERPRISE_ID, DOC_ID);
        order.verify(repository).existsReturnInStatus(ENTERPRISE_ID, DOC_ID, DocumentStatus.EMITIDA);
        order.verify(repository).save(result);
        order.verify(outbox).append(any(BillingDocumentAnnulled.class));
        assertThat(result.status()).isEqualTo(DocumentStatus.ANULADA);
    }

    @Test
    void ANL_1_annulled_document_keeps_its_number_and_records_reason_actor_and_time() {
        lockedStored(emittedSale());

        BillingDocument result = annulSale(REASON);

        assertThat(result.number().display()).isEqualTo("FV-000007");
        assertThat(result.annulReason()).isEqualTo(REASON);
        assertThat(result.annulledBy()).isEqualTo(USER);
        assertThat(result.annulledAt()).isEqualTo(NOW);
    }

    @Test
    void ANL_1_reason_is_trimmed_before_storing() {
        lockedStored(emittedSale());

        BillingDocument result = annulSale("   " + REASON + "  ");

        assertThat(result.annulReason()).isEqualTo(REASON);
    }

    @Test
    void ANL_1_saved_document_is_the_one_returned_to_the_caller() {
        lockedStored(emittedSale());
        BillingDocument persisted = withVersion(annulledSale(), 4L);
        when(repository.save(any(BillingDocument.class))).thenReturn(persisted);

        BillingDocument result = annulSale(REASON);

        assertThat(result).isSameAs(persisted);
    }

    @Test
    void EVT_1_event_is_BillingDocumentAnnulled_with_new_id_clock_time_and_the_saved_document() {
        lockedStored(emittedSale());

        BillingDocument result = annulSale(REASON);

        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BillingDocumentAnnulled.class);
        BillingDocumentAnnulled event = (BillingDocumentAnnulled) captor.getValue();
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.document()).isSameAs(result);
        assertThat(event.returnContext()).isNull();
    }

    @Test
    void EVT_1_exactly_one_event_is_appended() {
        lockedStored(emittedSale());

        annulSale(REASON);

        verify(outbox).append(any(BillingDomainEvent.class));
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void D12_the_document_is_read_only_through_the_locking_query() {
        lockedStored(emittedSale());

        annulSale(REASON);

        verify(repository, never()).findById(anyString(), any(UUID.class));
    }

    @Test
    void PUR_1_a_purchase_invoice_is_annulled_with_the_same_engine() {
        BillingDocument purchase = emittedPurchase();
        lockedStored(purchase);

        BillingDocument result = service.annul(ENTERPRISE_ID, DocumentType.PURCHASE_INVOICE, DOC_ID, REASON, null);

        assertThat(result.status()).isEqualTo(DocumentStatus.ANULADA);
        assertThat(result.number().display()).isEqualTo("FC-000007");
        verify(outbox).append(any(BillingDocumentAnnulled.class));
    }

    // ---------- motivo ----------

    @Test
    void ANL_1_blank_reason_is_rejected_without_writing() {
        lockedStored(emittedSale());

        Throwable thrown = catchThrowable(() -> annulSale("   "));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED);
        assertNothingWritten();
    }

    @Test
    void ANL_1_null_reason_is_rejected_without_writing() {
        lockedStored(emittedSale());

        Throwable thrown = catchThrowable(() -> annulSale(null));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED);
        assertNothingWritten();
    }

    @Test
    void ANL_1_reason_of_501_characters_is_rejected_and_500_is_accepted() {
        lockedStored(emittedSale());

        Throwable thrown = catchThrowable(() -> annulSale("x".repeat(501)));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED);
        assertNothingWritten();
        assertThat(annulSale("x".repeat(500)).annulReason()).hasSize(500);
    }

    // ---------- estado ----------

    @Test
    void STA_3_annulling_a_draft_is_409_invalid_state_and_writes_nothing() {
        lockedStored(readySaleDraft());

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_INVALID_STATE);
        assertNothingWritten();
        verify(repository, never()).existsReturnInStatus(anyString(), any(UUID.class), any(DocumentStatus.class));
    }

    @Test
    void IDM_3_annulling_an_already_annulled_document_returns_it_as_is_and_does_nothing_else() {
        BillingDocument annulled = annulledSale();
        lockedStored(annulled);

        BillingDocument result = annulSale(REASON);

        assertThat(result).isSameAs(annulled);
        assertNothingWritten();
        verify(repository, never()).existsReturnInStatus(anyString(), any(UUID.class), any(DocumentStatus.class));
        verifyNoInteractions(stock, accounting);
    }

    @Test
    void IDM_3_re_annulling_with_another_reason_or_a_stale_version_still_returns_the_annulled_document() {
        BillingDocument annulled = withVersion(annulledSale(), 5L);
        lockedStored(annulled);

        BillingDocument result = service.annul(ENTERPRISE_ID, SALE, DOC_ID, "otro motivo", 4L);

        assertThat(result).isSameAs(annulled);
        assertNothingWritten();
    }

    @Test
    void IDM_3_twenty_consecutive_annuls_append_a_single_event_and_call_the_ports_once() {
        BillingDocument document = emittedSale();
        lockedStored(document);

        BillingDocument first = annulSale(REASON);
        for (int i = 0; i < 19; i++) {
            lockedStored(first);
            assertThat(annulSale(REASON)).isSameAs(first);
        }

        verify(outbox, times(1)).append(any(BillingDomainEvent.class));
        verify(repository, times(1)).save(any(BillingDocument.class));
        verify(stock, times(1)).onAnnulled(any(BillingDocument.class));
        verify(accounting, times(1)).onAnnulled(any(BillingDocument.class));
    }

    @Test
    void IDM_3_annulling_a_draft_or_a_missing_state_other_than_the_target_is_still_invalid_state() {
        lockedStored(readySaleDraft());

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_INVALID_STATE);
        verifyNoInteractions(stock, accounting);
    }

    // ---------- puertos de integracion dentro de la transaccion (stock / asientos) ----------

    @Test
    void INT_1_annul_calls_stock_then_accounting_after_saving_and_before_the_outbox() {
        lockedStored(emittedSale());

        BillingDocument annulled = annulSale(REASON);

        InOrder order = inOrder(repository, stock, accounting, outbox);
        order.verify(repository).save(annulled);
        order.verify(stock).onAnnulled(annulled);
        order.verify(accounting).onAnnulled(annulled);
        order.verify(outbox).append(any(BillingDocumentAnnulled.class));
    }

    @Test
    void INT_2_a_failing_stock_port_propagates_so_no_event_is_appended_and_accounting_is_not_called() {
        lockedStored(emittedSale());
        doThrow(new IllegalStateException("inventario no disponible")).when(stock).onAnnulled(any());

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verify(outbox, never()).append(any(BillingDomainEvent.class));
        verifyNoInteractions(accounting);
    }

    @Test
    void INT_2_a_failing_accounting_port_propagates_so_no_event_is_appended() {
        lockedStored(emittedSale());
        doThrow(new IllegalStateException("contabilidad no disponible")).when(accounting).onAnnulled(any());

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verify(outbox, never()).append(any(BillingDomainEvent.class));
    }

    @Test
    void STA_2_a_rejected_annulment_leaves_the_loaded_document_untouched() {
        BillingDocument emitted = emittedSale();
        lockedStored(emitted);

        catchThrowable(() -> annulSale(" "));

        assertThat(emitted.status()).isEqualTo(DocumentStatus.EMITIDA);
        assertThat(emitted.annulReason()).isNull();
        assertThat(emitted.annulledAt()).isNull();
    }

    // ---------- devoluciones (ANL-2) ----------

    @Test
    void ANL_2_invoice_with_emitted_returns_is_409_has_emitted_returns_and_writes_nothing() {
        lockedStored(emittedSale());
        when(repository.existsReturnInStatus(ENTERPRISE_ID, DOC_ID, DocumentStatus.EMITIDA)).thenReturn(true);

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS);
        assertNothingWritten();
    }

    @Test
    void ANL_2_only_emitted_returns_block_so_the_check_asks_for_status_EMITIDA() {
        lockedStored(emittedSale());

        annulSale(REASON);

        verify(repository).existsReturnInStatus(ENTERPRISE_ID, DOC_ID, DocumentStatus.EMITIDA);
        verify(repository, never()).existsReturnInStatus(ENTERPRISE_ID, DOC_ID, DocumentStatus.ANULADA);
        verify(repository, never()).existsReturnInStatus(ENTERPRISE_ID, DOC_ID, DocumentStatus.BORRADOR);
    }

    @Test
    void ANL_2_after_the_returns_are_annulled_the_invoice_can_be_annulled() {
        lockedStored(emittedSale());
        when(repository.existsReturnInStatus(ENTERPRISE_ID, DOC_ID, DocumentStatus.EMITIDA))
                .thenReturn(true, false);

        Throwable first = catchThrowable(() -> annulSale(REASON));
        BillingDocument second = annulSale(REASON);

        assertThat(codeOf(first)).isEqualTo(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS);
        assertThat(second.status()).isEqualTo(DocumentStatus.ANULADA);
    }

    // ---------- guardas de carga ----------

    @Test
    void QRY_1_unknown_document_is_not_found_and_writes_nothing() {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, DOC_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertNothingWritten();
    }

    @Test
    void PUR_3_a_document_of_another_type_behaves_as_not_found() {
        lockedStored(emittedSale());

        Throwable thrown = catchThrowable(
                () -> service.annul(ENTERPRISE_ID, DocumentType.PURCHASE_INVOICE, DOC_ID, REASON, null));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertNothingWritten();
    }

    @Test
    void DRF_3_stale_expected_version_is_409_concurrent_modification_and_writes_nothing() {
        lockedStored(withVersion(emittedSale(), 5L));

        Throwable thrown = catchThrowable(() -> service.annul(ENTERPRISE_ID, SALE, DOC_ID, REASON, 4L));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION);
        assertNothingWritten();
    }

    @Test
    void DRF_3_matching_expected_version_annuls() {
        lockedStored(withVersion(emittedSale(), 5L));

        BillingDocument result = service.annul(ENTERPRISE_ID, SALE, DOC_ID, REASON, 5L);

        assertThat(result.status()).isEqualTo(DocumentStatus.ANULADA);
    }

    @Test
    void DRF_3_null_expected_version_skips_the_check() {
        lockedStored(withVersion(emittedSale(), 9L));

        BillingDocument result = annulSale(REASON);

        assertThat(result.status()).isEqualTo(DocumentStatus.ANULADA);
    }

    // ---------- atomicidad ----------

    @Test
    void EVT_1_when_save_fails_no_event_is_appended() {
        lockedStored(emittedSale());
        when(repository.save(any(BillingDocument.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION, "otro usuario"));

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION);
        verify(outbox, never()).append(any(BillingDomainEvent.class));
    }

    @Test
    void EVT_1_when_the_outbox_fails_the_error_propagates_so_the_transaction_rolls_back() {
        lockedStored(emittedSale());
        doThrow(new IllegalStateException("outbox caido")).when(outbox).append(any(BillingDomainEvent.class));

        Throwable thrown = catchThrowable(() -> annulSale(REASON));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("outbox caido");
    }

    // ---------- anulacion de devoluciones (RET-7, ANL-1, A1) ----------

    private static final UUID ORIGINAL_ID = uuid(10);
    private static final DocumentType SALE_RETURN = DocumentType.SALE_RETURN;
    private static final String RETURN_REASON = "cliente desistio";

    /** Venta emitida FV-000007, una linea de 10 unidades. */
    private static BillingDocument saleOriginal() {
        return emittedSaleWithLines(ORIGINAL_ID, 7L, lineOf(1, product(1L), "10"));
    }

    private void originalStored(BillingDocument original) {
        when(repository.findById(ENTERPRISE_ID, original.id())).thenReturn(Optional.of(original));
    }

    private void returnsOfOriginal(BillingDocument... returns) {
        when(repository.findReturnsOf(ENTERPRISE_ID, ORIGINAL_ID)).thenReturn(List.of(returns));
    }

    private BillingDocument annulReturn(String reason) {
        return service.annul(ENTERPRISE_ID, SALE_RETURN, DOC_ID, reason, null);
    }

    private BillingDocumentAnnulled capturedAnnulledEvent() {
        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BillingDocumentAnnulled.class);
        return (BillingDocumentAnnulled) captor.getValue();
    }

    @Test
    void RET_7_annulling_an_emitted_return_locks_it_saves_and_appends_the_event_in_that_order() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));
        originalStored(original);

        BillingDocument result = annulReturn(RETURN_REASON);

        InOrder order = inOrder(repository, outbox);
        order.verify(repository).findByIdForUpdate(ENTERPRISE_ID, DOC_ID);
        order.verify(repository).save(result);
        order.verify(outbox).append(any(BillingDocumentAnnulled.class));
        assertThat(result.status()).isEqualTo(DocumentStatus.ANULADA);
    }

    @Test
    void RET_7_the_annulled_return_keeps_its_NC_number_and_records_reason_actor_and_time() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 3L, returnLineOf(original, 1, "4")));
        originalStored(original);

        BillingDocument result = annulReturn("  " + RETURN_REASON + " ");

        assertThat(result.number().display()).isEqualTo("NC-000003");
        assertThat(result.annulReason()).isEqualTo(RETURN_REASON);
        assertThat(result.annulledBy()).isEqualTo(USER);
        assertThat(result.annulledAt()).isEqualTo(NOW);
    }

    @Test
    void RET_7_the_return_row_is_the_only_one_locked_and_returns_are_not_checked_against_invoices_rules() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));
        originalStored(original);

        annulReturn(RETURN_REASON);

        verify(repository, never()).findByIdForUpdate(ENTERPRISE_ID, ORIGINAL_ID);
        verify(repository, never()).existsReturnInStatus(anyString(), any(UUID.class), any(DocumentStatus.class));
    }

    @Test
    void A1_the_event_of_a_return_annulment_carries_ordinal_and_the_original_back_to_SIN_DEVOLUCION() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));
        originalStored(original);
        returnsOfOriginal();

        BillingDocument result = annulReturn(RETURN_REASON);

        BillingDocumentAnnulled event = capturedAnnulledEvent();
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.document()).isSameAs(result);
        ReturnEventContext returnContext = event.returnContext();
        assertThat(returnContext).isNotNull();
        assertThat(returnContext.ordinal()).isEqualTo(1);
        assertThat(returnContext.originalStatusAfter()).isEqualTo(ReturnStatus.SIN_DEVOLUCION);
    }

    @Test
    void RET_10_the_annulled_return_keeps_its_ordinal_among_the_returns_of_its_original() {
        BillingDocument original = saleOriginal();
        BillingDocument previous = emittedReturnOf(original, uuid(20), 1L, returnLineOf(original, 1, "4"));
        BillingDocument target = emittedReturnOf(original, DOC_ID, 2L, returnLineOf(original, 1, "3"));
        lockedStored(target);
        originalStored(original);
        returnsOfOriginal(previous, target);

        annulReturn(RETURN_REASON);

        ReturnEventContext returnContext = capturedAnnulledEvent().returnContext();
        assertThat(returnContext.ordinal()).isEqualTo(2);
        // la propia devolucion ya no cuenta: quedan 4 de 10 devueltas
        assertThat(returnContext.originalStatusAfter()).isEqualTo(ReturnStatus.PARCIAL);
    }

    @Test
    void RET_9_annulling_a_return_of_a_TOTAL_original_moves_it_to_PARCIAL() {
        BillingDocument original = saleOriginal();
        BillingDocument previous = emittedReturnOf(original, uuid(20), 1L, returnLineOf(original, 1, "6"));
        BillingDocument target = emittedReturnOf(original, DOC_ID, 2L, returnLineOf(original, 1, "4"));
        lockedStored(target);
        originalStored(original);
        returnsOfOriginal(previous, target);

        annulReturn(RETURN_REASON);

        assertThat(capturedAnnulledEvent().returnContext().originalStatusAfter()).isEqualTo(ReturnStatus.PARCIAL);
    }

    @Test
    void RET_7_the_released_quantity_is_visible_because_the_original_is_read_after_saving() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));
        originalStored(original);

        BillingDocument result = annulReturn(RETURN_REASON);

        InOrder order = inOrder(repository);
        order.verify(repository).save(result);
        order.verify(repository).findReturnsOf(ENTERPRISE_ID, ORIGINAL_ID);
    }

    @Test
    void RET_7_a_blank_reason_is_rejected_without_reading_the_original_or_writing() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));

        Throwable thrown = catchThrowable(() -> annulReturn("   "));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED);
        assertNothingWritten();
        verify(repository, never()).findById(anyString(), any(UUID.class));
    }

    @Test
    void RET_7_a_draft_return_cannot_be_annulled() {
        BillingDocument original = saleOriginal();
        lockedStored(returnDraftOf(original, DOC_ID, "motivo", returnLineOf(original, 1, "4")));

        Throwable thrown = catchThrowable(() -> annulReturn(RETURN_REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_INVALID_STATE);
        assertNothingWritten();
    }

    @Test
    void IDM_3_annulling_an_already_annulled_return_returns_it_as_is_without_ports_or_event() {
        BillingDocument original = saleOriginal();
        BillingDocument annulled = annulledReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4"));
        lockedStored(annulled);

        BillingDocument result = annulReturn(RETURN_REASON);

        assertThat(result).isSameAs(annulled);
        assertNothingWritten();
        verify(repository, never()).findById(anyString(), any(UUID.class));
        verifyNoInteractions(stock, accounting);
    }

    @Test
    void PUR_3_a_return_annulled_through_the_invoice_resource_is_not_found() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));

        Throwable thrown = catchThrowable(() -> service.annul(ENTERPRISE_ID, SALE, DOC_ID, REASON, null));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertNothingWritten();
    }

    @Test
    void PUR_3_an_invoice_annulled_through_the_return_resource_is_not_found() {
        lockedStored(emittedSale());

        Throwable thrown = catchThrowable(() -> annulReturn(RETURN_REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertNothingWritten();
    }

    @Test
    void PUR_1_a_purchase_return_is_annulled_with_the_same_engine_and_event_context() {
        BillingDocument original = emittedInvoice(ORIGINAL_ID, DocumentType.PURCHASE_INVOICE, 7L,
                List.of(lineOf(1, product(1L), "10")));
        lockedStored(emittedReturnOf(original, DOC_ID, 2L, returnLineOf(original, 1, "4")));
        originalStored(original);

        BillingDocument result = service.annul(ENTERPRISE_ID, DocumentType.PURCHASE_RETURN, DOC_ID,
                RETURN_REASON, null);

        assertThat(result.number().display()).isEqualTo("DC-000002");
        assertThat(capturedAnnulledEvent().returnContext().ordinal()).isEqualTo(1);
    }

    @Test
    void EVT_1_when_the_original_cannot_be_read_the_annulment_fails_so_the_transaction_rolls_back() {
        BillingDocument original = saleOriginal();
        lockedStored(emittedReturnOf(original, DOC_ID, 1L, returnLineOf(original, 1, "4")));
        when(repository.findById(ENTERPRISE_ID, ORIGINAL_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> annulReturn(RETURN_REASON));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        verify(outbox, never()).append(any(BillingDomainEvent.class));
    }

    @Test
    void ANL_2_the_invoice_event_keeps_a_null_return_context_and_never_reads_the_returns() {
        lockedStored(emittedSale());

        annulSale(REASON);

        assertThat(capturedAnnulledEvent().returnContext()).isNull();
        verify(repository, never()).findReturnsOf(anyString(), any(UUID.class));
    }

    @Test
    void ANL_2_once_the_only_emitted_return_is_annulled_the_invoice_can_be_annulled() {
        BillingDocument original = saleOriginal();
        BillingDocument target = emittedReturnOf(original, uuid(20), 1L, returnLineOf(original, 1, "4"));
        when(repository.findByIdForUpdate(ENTERPRISE_ID, ORIGINAL_ID)).thenReturn(Optional.of(original));
        when(repository.existsReturnInStatus(ENTERPRISE_ID, ORIGINAL_ID, DocumentStatus.EMITIDA))
                .thenReturn(true, false);
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(20))).thenReturn(Optional.of(target));
        when(repository.findById(ENTERPRISE_ID, ORIGINAL_ID)).thenReturn(Optional.of(original));

        Throwable blocked = catchThrowable(() -> service.annul(ENTERPRISE_ID, SALE, ORIGINAL_ID, REASON, null));
        service.annul(ENTERPRISE_ID, SALE_RETURN, uuid(20), RETURN_REASON, null);
        BillingDocument annulledInvoice = service.annul(ENTERPRISE_ID, SALE, ORIGINAL_ID, REASON, null);

        assertThat(codeOf(blocked)).isEqualTo(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS);
        assertThat(annulledInvoice.status()).isEqualTo(DocumentStatus.ANULADA);
    }
}
