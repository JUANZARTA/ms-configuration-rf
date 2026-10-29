package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.transaction;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.CUSTOMER_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.NOW;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TODAY;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.USER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.annulledSale;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceLineCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnLineCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentSummary;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentView;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.InvoiceWithReturnsSummary;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.ReturnSummary;
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
import co.unicauca.edu.co.contables.billing.domain.event.BillingDomainEvent;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.CounterpartyRole;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentKind;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.domain.model.ReturnAvailability;
import co.unicauca.edu.co.contables.billing.domain.model.ReturnStatus;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * D3: los servicios de aplicacion no son beans; estos adaptadores los construyen a partir de los
 * puertos y delegan dentro de una transaccion (comandos de escritura, consultas de solo lectura).
 */
@ExtendWith(MockitoExtension.class)
class BillingTransactionalUseCasesTest {

    @Mock
    private BillingDocumentRepositoryPort repository;
    @Mock
    private ThirdPartyPort thirdParties;
    @Mock
    private ProductPort products;
    @Mock
    private EnterprisePort enterprises;
    @Mock
    private BillingExecutionContextPort context;
    @Mock
    private BillingClockPort clock;
    @Mock
    private BillingIdGeneratorPort ids;
    @Mock
    private DocumentNumberSequencePort sequence;
    @Mock
    private BillingOutboxPort outbox;
    @Mock
    private StockMovementPort stock;
    @Mock
    private AccountingEntryPort accounting;

    private BillingTransactionalCommandUseCases commands;
    private BillingTransactionalQueryUseCases queries;

    @BeforeEach
    void setUp() {
        commands = new BillingTransactionalCommandUseCases(repository, thirdParties, products, enterprises, sequence,
                outbox, context, clock, ids, stock, accounting);
        queries = new BillingTransactionalQueryUseCases(repository, thirdParties, products, enterprises);
    }

    private static List<Method> useCaseMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .toList();
    }

    // ---------- limites transaccionales ----------

    @Test
    void D3_every_command_method_is_transactional_and_writable() {
        List<Method> methods = useCaseMethods(BillingTransactionalCommandUseCases.class);

        assertThat(methods).isNotEmpty();
        for (Method method : methods) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("@Transactional en %s", method.getName()).isNotNull();
            assertThat(tx.readOnly()).as("readOnly en %s", method.getName()).isFalse();
        }
    }

    @Test
    void D3_every_query_method_is_transactional_and_read_only() {
        List<Method> methods = useCaseMethods(BillingTransactionalQueryUseCases.class);

        assertThat(methods).isNotEmpty();
        for (Method method : methods) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("@Transactional en %s", method.getName()).isNotNull();
            assertThat(tx.readOnly()).as("readOnly en %s", method.getName()).isTrue();
        }
    }

    // ---------- delegacion de comandos ----------

    @Test
    void DRF_1_create_builds_the_draft_service_from_the_ports_and_persists() {
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW);
        when(clock.today()).thenReturn(TODAY);
        when(ids.newId()).thenReturn(uuid(5));
        when(thirdParties.findCounterparty(ENTERPRISE_ID, CUSTOMER_ID, CounterpartyRole.CUSTOMER))
                .thenReturn(Optional.of(customer()));
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE)).thenReturn(Optional.of(product(1L)));
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument created = commands.create(ENTERPRISE_ID, DocumentType.SALE_INVOICE,
                new InvoiceDraftCommand(CUSTOMER_ID, null, null,
                        List.of(new InvoiceLineCommand(1L, new BigDecimal("2"), null, null)), null));

        assertThat(created.id()).isEqualTo(uuid(5));
        assertThat(created.status()).isEqualTo(DocumentStatus.BORRADOR);
        assertThat(created.lines()).hasSize(1);
        assertThat(created.totals().total().value()).isEqualByComparingTo("238.00");
    }

    @Test
    void DRF_2_update_loads_through_the_repository_and_saves_the_replacement() {
        BillingDocument stored = readySaleDraft();
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW.plusSeconds(60));
        when(repository.findById(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(stored));
        when(thirdParties.findCounterparty(ENTERPRISE_ID, CUSTOMER_ID, CounterpartyRole.CUSTOMER))
                .thenReturn(Optional.of(customer()));
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE)).thenReturn(Optional.of(product(1L)));
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument updated = commands.update(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1),
                new InvoiceDraftCommand(CUSTOMER_ID, null, null,
                        List.of(new InvoiceLineCommand(1L, new BigDecimal("5"), null, null)), null));

        assertThat(updated.totals().total().value()).isEqualByComparingTo("595.00");
        verify(repository).save(updated);
    }

    @Test
    void DRF_6_duplicate_delegates_and_saves_a_new_draft() {
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW);
        when(clock.today()).thenReturn(TODAY);
        when(ids.newId()).thenReturn(uuid(9));
        when(repository.findById(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(readySaleDraft()));
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument copy = commands.duplicate(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1));

        assertThat(copy.id()).isEqualTo(uuid(9));
        assertThat(copy.lines()).hasSize(1);
    }

    @Test
    void DRF_5_delete_removes_a_draft() {
        BillingDocument stored = readySaleDraft();
        when(repository.findById(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(stored));

        commands.delete(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1), null);

        verify(repository).delete(stored);
    }

    @Test
    void DRF_5_delete_of_an_emitted_document_is_rejected_without_touching_the_repository() {
        when(repository.findById(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(emittedSale()));

        assertThatThrownBy(() -> commands.delete(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1), null))
                .isInstanceOf(BillingException.class)
                .satisfies(thrown -> assertThat(((BillingException) thrown).errorCode())
                        .isEqualTo(BillingErrorCode.BILLING_INVALID_STATE));
        verify(repository, never()).delete(any(BillingDocument.class));
    }

    @Test
    void EMT_3_emit_builds_the_emission_service_from_the_ports_and_appends_the_event() {
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW);
        when(ids.newId()).thenReturn(uuid(77));
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(readySaleDraft()));
        when(thirdParties.findCounterparty(ENTERPRISE_ID, CUSTOMER_ID, CounterpartyRole.CUSTOMER))
                .thenReturn(Optional.of(customer()));
        when(products.findProduct(ENTERPRISE_ID, 1L, DocumentKind.SALE)).thenReturn(Optional.of(product(1L)));
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.of(issuer()));
        when(sequence.nextValue(ENTERPRISE_ID, DocumentType.SALE_INVOICE)).thenReturn(8L);
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument emitted = commands.emit(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1), null);

        assertThat(emitted.status()).isEqualTo(DocumentStatus.EMITIDA);
        assertThat(emitted.number().display()).isEqualTo("FV-000008");
        verify(outbox).append(any(BillingDomainEvent.class));
        verify(stock).onEmitted(emitted);
        verify(accounting).onEmitted(emitted);
    }

    @Test
    void IDM_1_emit_of_an_emitted_document_returns_it_without_consuming_a_number_or_publishing() {
        BillingDocument emitted = emittedSale();
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(emitted));

        BillingDocument result = commands.emit(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1), null);

        assertThat(result).isSameAs(emitted);
        verifyNoInteractions(sequence, outbox, stock, accounting);
    }

    @Test
    void EMT_4_emit_of_an_annulled_document_is_still_invalid_state() {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(annulledSale()));

        assertThatThrownBy(() -> commands.emit(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1), null))
                .isInstanceOf(BillingException.class)
                .satisfies(thrown -> assertThat(((BillingException) thrown).errorCode())
                        .isEqualTo(BillingErrorCode.BILLING_INVALID_STATE));
        verifyNoInteractions(sequence, outbox, stock, accounting);
    }

    @Test
    void ANL_1_annul_builds_the_annulment_service_from_the_ports_locks_the_row_and_appends_the_event() {
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW);
        when(ids.newId()).thenReturn(uuid(88));
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(emittedSale()));
        when(repository.existsReturnInStatus(ENTERPRISE_ID, uuid(1), DocumentStatus.EMITIDA)).thenReturn(false);
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument annulled = commands.annul(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1),
                "error de digitacion", null);

        assertThat(annulled.status()).isEqualTo(DocumentStatus.ANULADA);
        assertThat(annulled.number().display()).isEqualTo("FV-000007");
        verify(repository, never()).findById(any(), any());
        verify(outbox).append(any(BillingDomainEvent.class));
    }

    @Test
    void ANL_1_annul_of_a_draft_is_rejected_without_writing_or_publishing() {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(readySaleDraft()));

        assertThatThrownBy(() -> commands.annul(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1), "motivo", null))
                .isInstanceOf(BillingException.class)
                .satisfies(thrown -> assertThat(((BillingException) thrown).errorCode())
                        .isEqualTo(BillingErrorCode.BILLING_INVALID_STATE));
        verify(repository, never()).save(any(BillingDocument.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void ANL_2_annul_of_an_invoice_with_emitted_returns_is_rejected_without_writing_or_publishing() {
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(emittedSale()));
        when(repository.existsReturnInStatus(ENTERPRISE_ID, uuid(1), DocumentStatus.EMITIDA)).thenReturn(true);

        assertThatThrownBy(() -> commands.annul(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1), "motivo", null))
                .isInstanceOf(BillingException.class)
                .satisfies(thrown -> assertThat(((BillingException) thrown).errorCode())
                        .isEqualTo(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS));
        verify(repository, never()).save(any(BillingDocument.class));
        verifyNoInteractions(outbox);
    }

    // ---------- delegacion de consultas ----------

    @Test
    void QRY_1_get_returns_the_stored_document_as_a_view() {
        BillingDocument stored = emittedSale();
        when(repository.findById(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.of(stored));

        BillingDocumentView view = queries.get(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1));

        assertThat(view.doc()).isSameAs(stored);
    }

    @Test
    void QRY_1_get_of_a_missing_document_is_not_found() {
        when(repository.findById(ENTERPRISE_ID, uuid(1))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queries.get(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(1)))
                .isInstanceOf(BillingException.class)
                .satisfies(thrown -> assertThat(((BillingException) thrown).errorCode())
                        .isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND));
    }

    @Test
    void QRY_2_list_passes_the_filters_to_the_repository() {
        List<BillingDocumentSummary> summaries = List.of(BillingDocumentSummary.from(readySaleDraft()));
        when(repository.findSummaries(ENTERPRISE_ID, DocumentType.SALE_INVOICE, DocumentStatus.BORRADOR, null))
                .thenReturn(summaries);

        List<BillingDocumentSummary> result =
                queries.list(ENTERPRISE_ID, DocumentType.SALE_INVOICE, DocumentStatus.BORRADOR, null);

        assertThat(result).isSameAs(summaries);
    }

    // ---------- devoluciones (H8b) ----------

    private static BillingDocument saleOriginal() {
        return emittedSaleWithLines(uuid(10), 7L, lineOf(1, product(1L), "10"));
    }

    @Test
    void RET_6_create_return_draft_builds_the_return_service_and_persists_a_draft_of_the_original() {
        BillingDocument original = saleOriginal();
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW);
        when(clock.today()).thenReturn(TODAY);
        when(ids.newId()).thenReturn(uuid(2));
        when(repository.findById(ENTERPRISE_ID, uuid(10))).thenReturn(Optional.of(original));
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument created = commands.create(ENTERPRISE_ID, DocumentType.SALE_RETURN,
                new ReturnDraftCommand(uuid(10), null, "motivo",
                        List.of(new ReturnLineCommand(1, new BigDecimal("4"))), null));

        assertThat(created.type()).isEqualTo(DocumentType.SALE_RETURN);
        assertThat(created.status()).isEqualTo(DocumentStatus.BORRADOR);
        assertThat(created.original().id()).isEqualTo(uuid(10));
        assertThat(created.totals().total().value()).isEqualByComparingTo("476.00");
    }

    @Test
    void RET_6_update_return_draft_loads_the_draft_and_its_original_and_saves_the_replacement() {
        BillingDocument original = saleOriginal();
        BillingDocument draft = returnDraftOf(original, uuid(2), "viejo", returnLineOf(original, 1, "2"));
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW.plusSeconds(60));
        when(repository.findById(ENTERPRISE_ID, uuid(2))).thenReturn(Optional.of(draft));
        when(repository.findById(ENTERPRISE_ID, uuid(10))).thenReturn(Optional.of(original));
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument updated = commands.update(ENTERPRISE_ID, DocumentType.SALE_RETURN, uuid(2),
                new ReturnDraftCommand(null, null, "nuevo",
                        List.of(new ReturnLineCommand(1, new BigDecimal("5"))), null));

        assertThat(updated.returnReason()).isEqualTo("nuevo");
        assertThat(updated.totals().total().value()).isEqualByComparingTo("595.00");
    }

    @Test
    void RET_5_emit_of_a_return_locks_the_original_and_appends_the_event_with_return_context() {
        BillingDocument original = saleOriginal();
        BillingDocument draft = returnDraftOf(original, uuid(2), "motivo", returnLineOf(original, 1, "4"));
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW);
        when(ids.newId()).thenReturn(uuid(77));
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(2))).thenReturn(Optional.of(draft));
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(10))).thenReturn(Optional.of(original));
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.of(issuer()));
        when(sequence.nextValue(ENTERPRISE_ID, DocumentType.SALE_RETURN)).thenReturn(1L);
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument emitted = commands.emit(ENTERPRISE_ID, DocumentType.SALE_RETURN, uuid(2), null);

        assertThat(emitted.number().display()).isEqualTo("NC-000001");
        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue().returnContext()).isNotNull();
        assertThat(captor.getValue().returnContext().ordinal()).isEqualTo(1);
    }

    @Test
    void RET_7_annul_of_a_return_appends_the_event_with_return_context() {
        BillingDocument original = saleOriginal();
        BillingDocument emittedReturn = emittedReturnOf(original, uuid(2), 1L, returnLineOf(original, 1, "4"));
        when(context.username()).thenReturn(USER);
        when(clock.now()).thenReturn(NOW);
        when(ids.newId()).thenReturn(uuid(88));
        when(repository.findByIdForUpdate(ENTERPRISE_ID, uuid(2))).thenReturn(Optional.of(emittedReturn));
        when(repository.findById(ENTERPRISE_ID, uuid(10))).thenReturn(Optional.of(original));
        when(repository.save(any(BillingDocument.class))).thenAnswer(call -> call.getArgument(0));

        BillingDocument annulled = commands.annul(ENTERPRISE_ID, DocumentType.SALE_RETURN, uuid(2),
                "cliente desistio", null);

        assertThat(annulled.status()).isEqualTo(DocumentStatus.ANULADA);
        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue().returnContext()).isNotNull();
    }

    @Test
    void RET_6_delete_of_a_return_draft_removes_it() {
        BillingDocument original = saleOriginal();
        BillingDocument draft = returnDraftOf(original, uuid(2), "motivo", returnLineOf(original, 1, "4"));
        when(repository.findById(ENTERPRISE_ID, uuid(2))).thenReturn(Optional.of(draft));

        commands.delete(ENTERPRISE_ID, DocumentType.SALE_RETURN, uuid(2), null);

        verify(repository).delete(draft);
    }

    @Test
    void RET_9_availability_delegates_to_the_query_service() {
        BillingDocument invoice = saleOriginal();
        when(repository.findById(ENTERPRISE_ID, uuid(10))).thenReturn(Optional.of(invoice));
        when(repository.findReturnsOf(ENTERPRISE_ID, uuid(10))).thenReturn(List.of());

        ReturnAvailability availability = queries.availability(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(10));

        assertThat(availability.status()).isEqualTo(ReturnStatus.SIN_DEVOLUCION);
        assertThat(availability.returnable()).isTrue();
    }

    @Test
    void RET_10_returns_of_delegates_to_the_query_service() {
        BillingDocument invoice = saleOriginal();
        BillingDocument emittedReturn = emittedReturnOf(invoice, uuid(2), 1L, returnLineOf(invoice, 1, "4"));
        when(repository.findById(ENTERPRISE_ID, uuid(10))).thenReturn(Optional.of(invoice));
        when(repository.findReturnsOf(ENTERPRISE_ID, uuid(10))).thenReturn(List.of(emittedReturn));

        List<ReturnSummary> result = queries.returnsOf(ENTERPRISE_ID, DocumentType.SALE_INVOICE, uuid(10));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ordinal()).isEqualTo(1);
    }

    @Test
    void RET_11_list_with_returns_delegates_to_the_query_service() {
        when(repository.aggregateEmittedReturns(ENTERPRISE_ID, DocumentType.SALE_RETURN)).thenReturn(List.of());

        List<InvoiceWithReturnsSummary> result = queries.list(ENTERPRISE_ID, DocumentType.SALE_INVOICE);

        assertThat(result).isEmpty();
    }
}
