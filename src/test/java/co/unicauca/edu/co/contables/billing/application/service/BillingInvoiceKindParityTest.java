package co.unicauca.edu.co.contables.billing.application.service;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.NOW;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.SUPPLIER_INVOICE_NUMBER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TODAY;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.USER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.counterpartyFor;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emitted;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.issuer;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.product;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readyDraft;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceLineCommand;
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
import co.unicauca.edu.co.contables.billing.domain.event.BillingDocumentAnnulled;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDocumentEmitted;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDomainEvent;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.CounterpartyRole;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentKind;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PUR-1..3 (RN-24): el mismo motor de servicios atiende venta y compra. Cada caso corre para
 * SALE_INVOICE y PURCHASE_INVOICE y verifica que el tipo gobierne el rol del tercero, el tipo de
 * producto consultado, la secuencia (independiente por tipo), el prefijo, el evento y que un
 * documento del otro tipo se comporte como inexistente.
 */
@ExtendWith(MockitoExtension.class)
class BillingInvoiceKindParityTest {

    private static final UUID DOC_ID = uuid(1);
    private static final UUID NEW_ID = uuid(50);
    private static final UUID EVENT_ID = uuid(77);
    private static final String REASON = "error de digitacion";

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

    private BillingInvoiceDraftService draftService;
    private BillingEmissionService emissionService;
    private BillingAnnulmentService annulmentService;

    @BeforeEach
    void setUp() {
        lenient().when(context.username()).thenReturn(USER);
        lenient().when(clock.now()).thenReturn(NOW);
        lenient().when(clock.today()).thenReturn(TODAY);
        lenient().when(ids.newId()).thenReturn(NEW_ID, EVENT_ID);
        lenient().when(repository.save(any(BillingDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.existsReturnInStatus(any(String.class), any(UUID.class),
                any(DocumentStatus.class))).thenReturn(false);
        BillingDocumentLoader loader = new BillingDocumentLoader(repository);
        BillingReferenceResolver resolver = new BillingReferenceResolver(thirdParties, products, enterprises);
        draftService = new BillingInvoiceDraftService(repository, loader, resolver, context, clock, ids);
        emissionService = new BillingEmissionService(repository, loader, resolver, sequence, outbox, context,
                clock, ids, stock, accounting);
        annulmentService = new BillingAnnulmentService(repository, loader, outbox, context, clock, ids, stock,
                accounting);
    }

    // ---------- helpers ----------

    private static DocumentType otherKind(DocumentType type) {
        return type == DocumentType.SALE_INVOICE ? DocumentType.PURCHASE_INVOICE : DocumentType.SALE_INVOICE;
    }

    private void thirdPartyExistsFor(DocumentType type) {
        lenient().when(thirdParties.findCounterparty(ENTERPRISE_ID, counterpartyFor(type).thirdId(),
                type.kind().counterpartyRole())).thenReturn(Optional.of(counterpartyFor(type)));
    }

    private void productExistsFor(DocumentType type) {
        lenient().when(products.findProduct(ENTERPRISE_ID, 1L, type.kind())).thenReturn(Optional.of(product(1L)));
    }

    private static BillingErrorCode codeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BillingException.class);
        return ((BillingException) thrown).errorCode();
    }

    // ---------- borrador ----------

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void PUR_1_create_resolves_the_counterparty_by_kind_role_and_the_product_as_cost_reference(DocumentType type) {
        thirdPartyExistsFor(type);
        productExistsFor(type);
        long thirdId = counterpartyFor(type).thirdId();
        String supplierNumber = type.kind() == DocumentKind.PURCHASE ? SUPPLIER_INVOICE_NUMBER : "IGNORADO";
        InvoiceDraftCommand command = new InvoiceDraftCommand(thirdId, TODAY, supplierNumber,
                List.of(new InvoiceLineCommand(1L, new BigDecimal("2"), null, null)), null);

        BillingDocument created = draftService.create(ENTERPRISE_ID, type, command);

        verify(thirdParties).findCounterparty(ENTERPRISE_ID, thirdId, type.kind().counterpartyRole());
        verify(products).findProduct(ENTERPRISE_ID, 1L, type.kind());
        assertThat(created.type()).isEqualTo(type);
        assertThat(created.status()).isEqualTo(DocumentStatus.BORRADOR);
        assertThat(created.counterparty().role()).isEqualTo(type.kind().counterpartyRole());
        assertThat(created.lines().get(0).unitPrice().value()).isEqualByComparingTo("100.00");
        assertThat(created.lines().get(0).priceOverridden()).isFalse();
        assertThat(created.supplierInvoiceNumber())
                .isEqualTo(type.kind() == DocumentKind.PURCHASE ? SUPPLIER_INVOICE_NUMBER : null);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void PUR_1_a_third_party_without_the_role_of_the_kind_is_counterparty_not_found(DocumentType type) {
        // el puerto no devuelve nada para el rol del tipo (p. ej. un cliente usado en una compra)
        lenient().when(thirdParties.findCounterparty(any(String.class), any(Long.class), any(CounterpartyRole.class)))
                .thenReturn(Optional.empty());
        InvoiceDraftCommand command = new InvoiceDraftCommand(99L, TODAY, null, List.of(), null);

        Throwable thrown = catchThrowable(() -> draftService.create(ENTERPRISE_ID, type, command));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_COUNTERPARTY_NOT_FOUND);
        verify(repository, never()).save(any(BillingDocument.class));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void DRF_6_duplicate_keeps_type_and_counterparty_and_never_copies_the_supplier_invoice_number(DocumentType type) {
        BillingDocument source = readyDraft(type);
        when(repository.findById(ENTERPRISE_ID, source.id())).thenReturn(Optional.of(source));

        BillingDocument copy = draftService.duplicate(ENTERPRISE_ID, type, source.id());

        assertThat(copy.id()).isEqualTo(NEW_ID);
        assertThat(copy.type()).isEqualTo(type);
        assertThat(copy.counterparty()).isEqualTo(source.counterparty());
        assertThat(copy.lines()).isEqualTo(source.lines());
        assertThat(copy.supplierInvoiceNumber()).isNull();
        assertThat(copy.number()).isNull();
    }

    // ---------- emision ----------

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void PUR_1_emit_takes_the_number_only_from_the_sequence_of_its_own_type_and_prefixes_it(DocumentType type) {
        BillingDocument draft = readyDraft(type);
        when(repository.findByIdForUpdate(ENTERPRISE_ID, draft.id())).thenReturn(Optional.of(draft));
        thirdPartyExistsFor(type);
        productExistsFor(type);
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.of(issuer()));
        when(sequence.nextValue(ENTERPRISE_ID, type)).thenReturn(5L);

        BillingDocument result = emissionService.emit(ENTERPRISE_ID, type, draft.id(), null);

        assertThat(result.status()).isEqualTo(DocumentStatus.EMITIDA);
        assertThat(result.number().display()).isEqualTo(type.prefix() + "-000005");
        verify(sequence).nextValue(ENTERPRISE_ID, type);
        verifyNoMoreInteractions(sequence);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void EVT_1_emit_appends_an_emitted_event_for_the_saved_document_of_its_type(DocumentType type) {
        BillingDocument draft = readyDraft(type);
        when(repository.findByIdForUpdate(ENTERPRISE_ID, draft.id())).thenReturn(Optional.of(draft));
        thirdPartyExistsFor(type);
        productExistsFor(type);
        when(enterprises.findIssuer(ENTERPRISE_ID)).thenReturn(Optional.of(issuer()));
        when(sequence.nextValue(ENTERPRISE_ID, type)).thenReturn(5L);

        BillingDocument result = emissionService.emit(ENTERPRISE_ID, type, draft.id(), null);

        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BillingDocumentEmitted.class);
        assertThat(captor.getValue().document()).isSameAs(result);
        assertThat(captor.getValue().document().type()).isEqualTo(type);
        assertThat(captor.getValue().returnContext()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void PUR_3_emit_through_the_resource_of_the_other_kind_is_not_found_and_consumes_no_number(DocumentType type) {
        BillingDocument draft = readyDraft(type);
        when(repository.findByIdForUpdate(ENTERPRISE_ID, draft.id())).thenReturn(Optional.of(draft));

        Throwable thrown = catchThrowable(
                () -> emissionService.emit(ENTERPRISE_ID, otherKind(type), draft.id(), null));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        verifyNoInteractions(sequence, outbox);
        verify(repository, never()).save(any(BillingDocument.class));
    }

    // ---------- anulacion ----------

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void ANL_1_annul_keeps_the_number_of_its_type_and_appends_an_annulled_event(DocumentType type) {
        BillingDocument doc = emitted(type, 7L);
        when(repository.findByIdForUpdate(ENTERPRISE_ID, doc.id())).thenReturn(Optional.of(doc));

        BillingDocument result = annulmentService.annul(ENTERPRISE_ID, type, doc.id(), REASON, null);

        assertThat(result.status()).isEqualTo(DocumentStatus.ANULADA);
        assertThat(result.number().display()).isEqualTo(type.prefix() + "-000007");
        ArgumentCaptor<BillingDomainEvent> captor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(outbox).append(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BillingDocumentAnnulled.class);
        assertThat(captor.getValue().document().type()).isEqualTo(type);
        verifyNoInteractions(sequence);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void PUR_3_annul_through_the_resource_of_the_other_kind_is_not_found_and_writes_nothing(DocumentType type) {
        BillingDocument doc = emitted(type, 7L);
        when(repository.findByIdForUpdate(ENTERPRISE_ID, doc.id())).thenReturn(Optional.of(doc));

        Throwable thrown = catchThrowable(
                () -> annulmentService.annul(ENTERPRISE_ID, otherKind(type), doc.id(), REASON, null));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        assertThat(doc.status()).isEqualTo(DocumentStatus.EMITIDA);
        verify(repository, never()).save(any(BillingDocument.class));
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = {"SALE_INVOICE", "PURCHASE_INVOICE"})
    void PUR_3_duplicate_through_the_resource_of_the_other_kind_is_not_found(DocumentType type) {
        BillingDocument source = readyDraft(type);
        when(repository.findById(ENTERPRISE_ID, source.id())).thenReturn(Optional.of(source));

        Throwable thrown = catchThrowable(
                () -> draftService.duplicate(ENTERPRISE_ID, otherKind(type), source.id()));

        assertThat(codeOf(thrown)).isEqualTo(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND);
        verify(repository, never()).save(any(BillingDocument.class));
    }
}
