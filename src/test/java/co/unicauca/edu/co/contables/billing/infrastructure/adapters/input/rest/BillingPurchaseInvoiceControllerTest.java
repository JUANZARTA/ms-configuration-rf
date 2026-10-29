package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.SUPPLIER_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.SUPPLIER_INVOICE_NUMBER;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.draftPurchase;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedPurchase;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.money;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readyPurchaseDraft;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnLineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.withVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.unicauca.edu.co.contables.billing.application.ports.input.AnnulDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.CreateDocumentIdempotentlyUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.DeleteDocumentDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.DuplicateInvoiceDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.EmitDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.GetDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.GetReturnAvailabilityUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.ListDocumentReturnsUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.ListDocumentsUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.ListInvoicesWithReturnsUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.SaveInvoiceDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentSummary;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentView;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.InvoiceWithReturnsSummary;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.ReturnSummary;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.domain.model.ReturnStatus;
import co.unicauca.edu.co.contables.billing.domain.service.ReturnAvailabilityCalculator;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingPurchaseInvoiceController;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingInvoiceEndpointSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Facturas de compra por REST (PUR-1..3, RN-24, SEC-1/2): mismo soporte de endpoints que la
 * venta, ruta {@code /api/billing/purchase-invoices} y recurso de permisos BFC. Slice MVC con
 * configuracion minima de billing (D18); {@code BillingInvoiceEndpointSupport} exige TODOS los
 * casos de uso, por eso se declaran como mocks aunque cada test use solo algunos.
 */
@WebMvcTest(controllers = BillingPurchaseInvoiceController.class)
@ContextConfiguration(classes = {BillingPurchaseInvoiceController.class, BillingInvoiceEndpointSupport.class,
        BillingWebMvcTestConfig.class})
@ActiveProfiles("test")
@WithMockUser(authorities = {"BFC#R", "BFC#C", "BFC#U", "BFC#D", "BFC#EM", "BFC#AN"})
class BillingPurchaseInvoiceControllerTest {

    private static final String BASE = "/api/billing/purchase-invoices";
    private static final DocumentType TYPE = DocumentType.PURCHASE_INVOICE;
    private static final String VALID_BODY = """
            {"clientId":"20","date":"2026-01-15","supplierInvoiceNumber":"F-77",
             "lines":[{"productId":"1","cantidad":2}],"version":3}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaveInvoiceDraftUseCase saveUseCase;
    @MockitoBean
    private CreateDocumentIdempotentlyUseCase idempotentCreateUseCase;
    @MockitoBean
    private DuplicateInvoiceDraftUseCase duplicateUseCase;
    @MockitoBean
    private DeleteDocumentDraftUseCase deleteUseCase;
    @MockitoBean
    private GetDocumentUseCase getUseCase;
    @MockitoBean
    private ListDocumentsUseCase listUseCase;
    @MockitoBean
    private EmitDocumentUseCase emitUseCase;
    @MockitoBean
    private AnnulDocumentUseCase annulUseCase;
    // H8d: BillingInvoiceEndpointSupport tambien exige los tres casos de uso de consulta de devoluciones
    @MockitoBean
    private GetReturnAvailabilityUseCase availabilityUseCase;
    @MockitoBean
    private ListDocumentReturnsUseCase returnsUseCase;
    @MockitoBean
    private ListInvoicesWithReturnsUseCase withReturnsUseCase;

    private static BillingDocument saved() {
        return withVersion(readyPurchaseDraft(), 0L);
    }

    private static String locationOf(BillingDocument doc) {
        return BASE + "/" + doc.id() + "?enterpriseId=" + ENTERPRISE_ID;
    }

    // ---------- POST ----------

    @Test
    void PUR_2_post_creates_purchase_draft_with_201_location_and_echoes_supplier_invoice_number() throws Exception {
        BillingDocument doc = saved();
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", locationOf(doc)))
                .andExpect(jsonPath("$.id").value(doc.id().toString()))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.kind").value("PURCHASE"))
                .andExpect(jsonPath("$.documentType").value("PURCHASE_INVOICE"))
                .andExpect(jsonPath("$.supplierInvoiceNumber").value(SUPPLIER_INVOICE_NUMBER))
                .andExpect(jsonPath("$.clientId").value(String.valueOf(SUPPLIER_ID)))
                .andExpect(jsonPath("$.totals.totalCents").value(23800))
                .andExpect(jsonPath("$.lines[0].precioCatalogoCents").value(10000));
    }

    @Test
    void PUR_2_post_passes_the_supplier_third_id_and_supplier_invoice_number_to_the_use_case() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(saved());

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<InvoiceDraftCommand> captor = ArgumentCaptor.forClass(InvoiceDraftCommand.class);
        verify(saveUseCase).create(eq(ENTERPRISE_ID), eq(TYPE), captor.capture());
        assertThat(captor.getValue().counterpartyId()).isEqualTo(SUPPLIER_ID);
        assertThat(captor.getValue().supplierInvoiceNumber()).isEqualTo(SUPPLIER_INVOICE_NUMBER);
        assertThat(captor.getValue().lines()).hasSize(1);
    }

    @Test
    void DRF_1_post_partial_purchase_draft_without_supplier_or_number_is_accepted() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class)))
                .thenReturn(withVersion(draftPurchase(), 0L));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").isEmpty())
                .andExpect(jsonPath("$.supplierInvoiceNumber").isEmpty())
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    void PUR_2_post_supplier_invoice_number_longer_than_50_is_400_and_use_case_not_called() throws Exception {
        String body = "{\"clientId\":\"20\",\"supplierInvoiceNumber\":\"" + "X".repeat(51) + "\",\"lines\":[]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void PUR_1_post_unknown_or_non_supplier_third_party_is_422() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_COUNTERPARTY_NOT_FOUND, "Tercero 99"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_COUNTERPARTY_NOT_FOUND"));
    }

    @Test
    void VALIDATION_post_without_enterprise_id_is_400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    // ---------- PUT ----------

    @Test
    void PUR_2_put_replaces_draft_returns_200_with_supplier_invoice_number_and_expected_version() throws Exception {
        BillingDocument doc = withVersion(readyPurchaseDraft(), 4L);
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenReturn(doc);

        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.supplierInvoiceNumber").value(SUPPLIER_INVOICE_NUMBER));

        ArgumentCaptor<InvoiceDraftCommand> captor = ArgumentCaptor.forClass(InvoiceDraftCommand.class);
        verify(saveUseCase).update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(3L);
        assertThat(captor.getValue().supplierInvoiceNumber()).isEqualTo(SUPPLIER_INVOICE_NUMBER);
    }

    @Test
    void DRF_3_put_with_stale_version_is_409() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION, "version"));

        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_CONCURRENT_MODIFICATION"));
    }

    @Test
    void PUR_3_put_of_a_document_of_another_kind_is_404() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    // ---------- DELETE ----------

    @Test
    void DRF_5_delete_purchase_draft_is_204_and_uses_the_purchase_type() throws Exception {
        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID).param("version", "3"))
                .andExpect(status().isNoContent());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), 3L);
    }

    @Test
    void DRF_5_delete_non_draft_is_409() throws Exception {
        doThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"))
                .when(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), null);

        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    // ---------- DUPLICATE ----------

    @Test
    void PUR_2_duplicate_returns_201_and_the_copy_has_no_supplier_invoice_number() throws Exception {
        BillingDocument copy = withVersion(draftPurchase(), 0L);
        when(duplicateUseCase.duplicate(ENTERPRISE_ID, TYPE, uuid(5))).thenReturn(copy);

        mockMvc.perform(post(BASE + "/" + uuid(5) + "/duplicate").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", locationOf(copy)))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.supplierInvoiceNumber").isEmpty());
    }

    @Test
    void DRF_6_duplicate_non_draft_is_409() throws Exception {
        when(duplicateUseCase.duplicate(ENTERPRISE_ID, TYPE, uuid(5)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"));

        mockMvc.perform(post(BASE + "/" + uuid(5) + "/duplicate").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict());
    }

    // ---------- GET ----------

    @Test
    void QRY_1_get_returns_emitted_purchase_with_fc_number_and_supplier_data() throws Exception {
        BillingDocument doc = withVersion(emittedPurchase(), 1L);
        when(getUseCase.get(ENTERPRISE_ID, TYPE, doc.id())).thenReturn(BillingDocumentView.of(doc));

        mockMvc.perform(get(BASE + "/" + doc.id()).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("FC-000007"))
                .andExpect(jsonPath("$.status").value("EMITIDA"))
                .andExpect(jsonPath("$.kind").value("PURCHASE"))
                .andExpect(jsonPath("$.supplierInvoiceNumber").value(SUPPLIER_INVOICE_NUMBER))
                .andExpect(jsonPath("$.clientName").value("Proveedor 20"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void PUR_3_get_of_a_sale_document_through_the_purchase_resource_is_404() throws Exception {
        when(getUseCase.get(ENTERPRISE_ID, TYPE, uuid(9)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(get(BASE + "/" + uuid(9)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    // ---------- LIST ----------

    @Test
    void QRY_1_list_filters_by_status_and_summaries_carry_the_supplier_invoice_number() throws Exception {
        BillingDocument doc = withVersion(readyPurchaseDraft(), 2L);
        when(listUseCase.list(ENTERPRISE_ID, TYPE, DocumentStatus.BORRADOR, null))
                .thenReturn(List.of(BillingDocumentSummary.from(doc)));

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "BORRADOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentType").value("PURCHASE_INVOICE"))
                .andExpect(jsonPath("$[0].kind").value("PURCHASE"))
                .andExpect(jsonPath("$[0].supplierInvoiceNumber").value(SUPPLIER_INVOICE_NUMBER))
                .andExpect(jsonPath("$[0].totals.totalCents").value(23800))
                .andExpect(jsonPath("$[0].lines").doesNotExist());
    }

    @Test
    void QRY_1_list_with_unknown_status_is_400() throws Exception {
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(listUseCase);
    }

    // ---------- EMIT ----------

    @Test
    void EMT_1_emit_returns_200_with_fc_number_and_the_supplier_invoice_number() throws Exception {
        BillingDocument doc = withVersion(emittedPurchase(), 1L);
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), 3L)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("FC-000007"))
                .andExpect(jsonPath("$.status").value("EMITIDA"))
                .andExpect(jsonPath("$.supplierInvoiceNumber").value(SUPPLIER_INVOICE_NUMBER))
                .andExpect(jsonPath("$.totals.totalCents").value(23800));
    }

    @Test
    void EMT_4_emit_without_body_passes_null_version() throws Exception {
        BillingDocument doc = withVersion(emittedPurchase(), 1L);
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, doc.id(), null);
    }

    @Test
    void PUR_2_emit_without_supplier_invoice_number_is_422_draft_incomplete() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DRAFT_INCOMPLETE,
                        "La factura de compra requiere el numero de factura del proveedor"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_DRAFT_INCOMPLETE"));
    }

    @Test
    void EMT_4_emit_on_non_draft_is_409() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void EMT_1_emit_with_inactive_supplier_is_422() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INACTIVE_REFERENCE, "proveedor inactivo"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_INACTIVE_REFERENCE"));
    }

    @Test
    void EMT_3_emit_with_busy_sequence_is_503() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_SEQUENCE_BUSY, "ocupada"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BILLING_SEQUENCE_BUSY"));
    }

    @Test
    void PUR_3_emit_of_a_sale_document_through_the_purchase_resource_is_404() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(9), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE + "/" + uuid(9) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound());
    }

    // ---------- ANNUL ----------

    @Test
    void ANL_1_annul_returns_200_keeping_the_fc_number_and_reason() throws Exception {
        BillingDocument doc = emittedPurchase();
        doc.annul("factura duplicada", false, doc.emittedAt().plusSeconds(60), "approver");
        BillingDocument annulled = withVersion(doc, 2L);
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, annulled.id(), "factura duplicada", 1L)).thenReturn(annulled);

        mockMvc.perform(post(BASE + "/" + annulled.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"factura duplicada\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"))
                .andExpect(jsonPath("$.number").value("FC-000007"))
                .andExpect(jsonPath("$.annulReason").value("factura duplicada"))
                .andExpect(jsonPath("$.supplierInvoiceNumber").value(SUPPLIER_INVOICE_NUMBER))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void ANL_1_annul_without_reason_is_400_annul_reason_required() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), null, null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED, "motivo"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_ANNUL_REASON_REQUIRED"));
    }

    @Test
    void ANL_1_annul_on_non_emitted_document_is_409() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "BORRADOR"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void ANL_2_annul_with_emitted_returns_is_409() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS, "devoluciones"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_HAS_EMITTED_RETURNS"));
    }

    @Test
    void PUR_3_annul_of_a_sale_document_through_the_purchase_resource_is_404() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(9), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE + "/" + uuid(9) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- SEC-1 / SEC-2 (recurso BFC) ----------

    @Test
    @WithAnonymousUser
    void SEC_1_anonymous_is_401_on_every_verb() throws Exception {
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(saveUseCase, duplicateUseCase, deleteUseCase, getUseCase, listUseCase, emitUseCase,
                annulUseCase);
    }

    private static BillingDocument annulledDoc() {
        BillingDocument doc = emittedPurchase();
        doc.annul("motivo", false, doc.emittedAt().plusSeconds(60), "approver");
        return withVersion(doc, 2L);
    }

    @Test
    @WithMockUser(authorities = {"BFV#R", "BFV#C", "BFV#U", "BFV#D", "BFV#EM", "BFV#AN"})
    void SEC_2_sale_authorities_are_irrelevant_to_the_purchase_resource_while_permissions_are_open()
            throws Exception {
        BillingDocument emitted = withVersion(emittedPurchase(), 1L);
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(saved());
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, emitted.id(), null)).thenReturn(emitted);
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, emitted.id(), "motivo", null)).thenReturn(annulledDoc());

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isOk());
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/" + emitted.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/" + emitted.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk());

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, emitted.id(), null);
        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, emitted.id(), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = "BFC#R")
    void SEC_2_consult_only_can_also_create_update_delete_duplicate_emit_or_annul_while_permissions_are_open()
            throws Exception {
        BillingDocument emitted = withVersion(emittedPurchase(), 1L);
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(saved());
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenReturn(saved());
        when(duplicateUseCase.duplicate(ENTERPRISE_ID, TYPE, uuid(1))).thenReturn(saved());
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, emitted.id(), null)).thenReturn(emitted);
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, emitted.id(), "motivo", null)).thenReturn(annulledDoc());

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/duplicate").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/" + emitted.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/" + emitted.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), null);
        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, emitted.id(), null);
        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, emitted.id(), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = {"BFC#R", "BFC#C", "BFC#U", "BFC#D", "BFC#AN"})
    void SEC_2_user_without_emit_authority_is_not_forbidden_and_the_emission_is_invoked() throws Exception {
        BillingDocument emitted = withVersion(emittedPurchase(), 1L);
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, emitted.id(), null)).thenReturn(emitted);

        mockMvc.perform(post(BASE + "/" + emitted.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMITIDA"));

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, emitted.id(), null);
    }

    @Test
    @WithMockUser(authorities = {"BFC#R", "BFC#C", "BFC#U", "BFC#D", "BFC#EM"})
    void SEC_2_user_without_annul_authority_is_not_forbidden_and_the_annulment_is_invoked() throws Exception {
        BillingDocument annulled = annulledDoc();
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, annulled.id(), "motivo", null)).thenReturn(annulled);

        mockMvc.perform(post(BASE + "/" + annulled.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"));

        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, annulled.id(), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = "BFC#C")
    void SEC_2_create_only_can_also_consult_while_permissions_are_open() throws Exception {
        BillingDocument doc = withVersion(emittedPurchase(), 1L);
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());
        when(getUseCase.get(ENTERPRISE_ID, TYPE, uuid(1))).thenReturn(BillingDocumentView.of(doc));

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(listUseCase).list(ENTERPRISE_ID, TYPE, null, null);
        verify(getUseCase).get(ENTERPRISE_ID, TYPE, uuid(1));
    }

    // ---------- H8d: sub-recursos de devoluciones de la factura de compra (RET-8, RET-9..11) ----------

    @Test
    void RET_9_return_availability_of_a_purchase_invoice_uses_the_purchase_type() throws Exception {
        BillingDocument invoice = emittedPurchase(); // linea 1: 2 unidades a 100.00 + 19%
        BillingDocument emitted = emittedReturnOf(invoice, uuid(2), 4L, returnLineOf(invoice, 1, "2"));
        when(availabilityUseCase.availability(ENTERPRISE_ID, TYPE, uuid(1)))
                .thenReturn(new ReturnAvailabilityCalculator().availability(invoice, List.of(emitted)));

        mockMvc.perform(get(BASE + "/" + uuid(1) + "/return-availability").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$.state").value("TOTAL"))
                .andExpect(jsonPath("$.returnable").value(false))
                .andExpect(jsonPath("$.lines[0].sold").value(2.0))
                .andExpect(jsonPath("$.lines[0].available").value(0.0));
    }

    @Test
    void RET_8_return_availability_of_a_sale_invoice_through_the_purchase_resource_is_404() throws Exception {
        when(availabilityUseCase.availability(ENTERPRISE_ID, TYPE, uuid(9)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(get(BASE + "/" + uuid(9) + "/return-availability").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void RET_10_returns_of_a_purchase_invoice_lists_dc_documents_with_ordinal() throws Exception {
        BillingDocument invoice = emittedPurchase();
        BillingDocument emitted = emittedReturnOf(invoice, uuid(2), 4L, returnLineOf(invoice, 1, "1"));
        when(returnsUseCase.returnsOf(ENTERPRISE_ID, TYPE, uuid(1)))
                .thenReturn(List.of(new ReturnSummary(BillingDocumentSummary.from(emitted), 1)));

        mockMvc.perform(get(BASE + "/" + uuid(1) + "/returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value("DC-000004"))
                .andExpect(jsonPath("$[0].documentType").value("PURCHASE_RETURN"))
                .andExpect(jsonPath("$[0].kind").value("PURCHASE"))
                .andExpect(jsonPath("$[0].ordinal").value(1))
                .andExpect(jsonPath("$[0].originalNumber").value("FC-000007"));
    }

    @Test
    void RET_11_with_returns_of_purchase_invoices_carries_the_supplier_invoice_number() throws Exception {
        BillingDocument invoice = emittedPurchase();
        when(withReturnsUseCase.list(ENTERPRISE_ID, TYPE)).thenReturn(List.of(
                new InvoiceWithReturnsSummary(BillingDocumentSummary.from(invoice), 1, money("238.00"),
                        ReturnStatus.TOTAL)));

        mockMvc.perform(get(BASE + "/with-returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].invoiceNumber").value("FC-000007"))
                .andExpect(jsonPath("$[0].kind").value("PURCHASE"))
                .andExpect(jsonPath("$[0].supplierInvoiceNumber").value(SUPPLIER_INVOICE_NUMBER))
                .andExpect(jsonPath("$[0].clientName").value("Proveedor 20"))
                .andExpect(jsonPath("$[0].returnedTotalCents").value(23800))
                .andExpect(jsonPath("$[0].state").value("TOTAL"));

        verifyNoInteractions(getUseCase);
    }

    @Test
    @WithAnonymousUser
    void SEC_1_anonymous_is_401_on_the_return_sub_resources() throws Exception {
        mockMvc.perform(get(BASE + "/" + uuid(1) + "/return-availability").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + uuid(1) + "/returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/with-returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(availabilityUseCase, returnsUseCase, withReturnsUseCase);
    }

    @Test
    @WithMockUser(authorities = {"BDC#R", "BFV#R", "BFC#C", "BFC#EM"})
    void SEC_2_return_sub_resources_do_not_need_the_purchase_invoice_consult_authority_while_permissions_are_open()
            throws Exception {
        BillingDocument invoice = emittedPurchase();
        when(availabilityUseCase.availability(ENTERPRISE_ID, TYPE, uuid(1)))
                .thenReturn(new ReturnAvailabilityCalculator().availability(invoice, List.of()));
        when(returnsUseCase.returnsOf(ENTERPRISE_ID, TYPE, uuid(1))).thenReturn(List.of());
        when(withReturnsUseCase.list(ENTERPRISE_ID, TYPE)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/" + uuid(1) + "/return-availability").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + uuid(1) + "/returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/with-returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(availabilityUseCase).availability(ENTERPRISE_ID, TYPE, uuid(1));
    }

    // ---------- Idempotency-Key (IDM-1, IDM-2) ----------

    private static final String IDEMPOTENCY_KEY = "6f1c1f3a-3e56-4c7a-9a0e-0d3a5c8d1b11";

    private static String idempotentLocationOf(BillingDocument doc) {
        return BASE + "/" + doc.id() + "?enterpriseId=" + ENTERPRISE_ID;
    }

    @Test
    void IDM_1_post_with_idempotency_key_uses_the_idempotent_use_case_and_answers_201_with_location()
            throws Exception {
        BillingDocument doc = saved();
        when(idempotentCreateUseCase.createInvoice(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class), eq(IDEMPOTENCY_KEY)))
                .thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID).header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", idempotentLocationOf(doc)))
                .andExpect(jsonPath("$.id").value(doc.id().toString()));

        org.mockito.Mockito.verifyNoInteractions(saveUseCase);
    }

    @Test
    void IDM_1_repeating_the_post_with_the_same_key_answers_the_same_document_twice() throws Exception {
        BillingDocument doc = saved();
        when(idempotentCreateUseCase.createInvoice(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class), eq(IDEMPOTENCY_KEY)))
                .thenReturn(doc);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", idempotentLocationOf(doc)))
                    .andExpect(jsonPath("$.id").value(doc.id().toString()));
        }
    }

    @Test
    void IDM_1_the_idempotency_key_is_normalised_to_lower_case() throws Exception {
        BillingDocument doc = saved();
        when(idempotentCreateUseCase.createInvoice(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class), eq(IDEMPOTENCY_KEY)))
                .thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY.toUpperCase())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void IDM_2_same_key_with_another_body_is_409_idempotency_conflict() throws Exception {
        when(idempotentCreateUseCase.createInvoice(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class), eq(IDEMPOTENCY_KEY)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT, "otro contenido"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID).header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void IDM_1_a_header_that_is_not_a_uuid_is_400_validation_error_and_nothing_is_created() throws Exception {
        for (String invalid : new String[] {"no-es-un-uuid", "   ", "123"}) {
            mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID).header("Idempotency-Key", invalid)
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));
        }

        org.mockito.Mockito.verifyNoInteractions(saveUseCase, idempotentCreateUseCase);
    }

    @Test
    void IDM_1_without_the_header_the_creation_behaves_as_before() throws Exception {
        BillingDocument doc = saved();
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        org.mockito.Mockito.verifyNoInteractions(idempotentCreateUseCase);
    }
}
