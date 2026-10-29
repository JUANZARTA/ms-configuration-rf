package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.annulledSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.draftSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.money;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readySaleDraft;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnDraftOf;
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
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingSaleInvoiceController;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingInvoiceEndpointSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Borradores de factura de venta por REST (DOC-4, DOC-6, DRF-1..8, SEC-1, ERR-1). Slice MVC con
 * configuracion minima de billing (D18); los casos de uso son mocks de los puertos de entrada.
 */
@WebMvcTest(controllers = BillingSaleInvoiceController.class)
@ContextConfiguration(classes = {BillingSaleInvoiceController.class, BillingInvoiceEndpointSupport.class,
        BillingWebMvcTestConfig.class})
@ActiveProfiles("test")
@WithMockUser(authorities = {"BFV#R", "BFV#C", "BFV#U", "BFV#D", "BFV#EM", "BFV#AN"})
class BillingSaleInvoiceControllerTest {

    private static final String BASE = "/api/billing/sale-invoices";
    private static final DocumentType TYPE = DocumentType.SALE_INVOICE;
    private static final String VALID_BODY = """
            {"clientId":"10","date":"2026-01-15","lines":[{"productId":"1","cantidad":2}],"version":3}
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
        return withVersion(readySaleDraft(), 0L);
    }

    private static String locationOf(BillingDocument doc) {
        return BASE + "/" + doc.id() + "?enterpriseId=" + ENTERPRISE_ID;
    }

    // ---------- POST ----------

    @Test
    void DRF_1_post_creates_draft_with_201_location_and_cents_body() throws Exception {
        BillingDocument doc = saved();
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", locationOf(doc)))
                .andExpect(jsonPath("$.id").value(doc.id().toString()))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.documentType").value("SALE_INVOICE"))
                .andExpect(jsonPath("$.clientId").value("10"))
                .andExpect(jsonPath("$.totals.subtotalCents").value(20000))
                .andExpect(jsonPath("$.totals.ivaCents").value(3800))
                .andExpect(jsonPath("$.totals.totalCents").value(23800))
                .andExpect(jsonPath("$.lines[0].productId").value("1"))
                .andExpect(jsonPath("$.lines[0].precioUnitarioCents").value(10000))
                .andExpect(jsonPath("$.lines[0].precioCatalogoCents").value(10000))
                .andExpect(jsonPath("$.lines[0].precioEditado").value(false))
                .andExpect(jsonPath("$.lines[0].cantidad").value(2.0));
    }

    @Test
    void DOC_4_post_ignores_client_totals_and_passes_only_editable_fields() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(saved());
        String body = """
                {"clientId":"10","totalCents":1,"totals":{"totalCents":1},
                 "lines":[{"productId":"1","cantidad":2,"precioUnitarioCents":15000,"ivaPct":5,
                           "productName":"hack","precioCatalogoCents":1,"totalCents":1}]}
                """;

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.totalCents").value(23800));

        ArgumentCaptor<InvoiceDraftCommand> captor = ArgumentCaptor.forClass(InvoiceDraftCommand.class);
        verify(saveUseCase).create(eq(ENTERPRISE_ID), eq(TYPE), captor.capture());
        InvoiceDraftCommand command = captor.getValue();
        assertThat(command.counterpartyId()).isEqualTo(10L);
        assertThat(command.lines()).hasSize(1);
        assertThat(command.lines().get(0).unitPriceOverride()).isEqualByComparingTo("150.00");
        assertThat(command.lines().get(0).taxRateOverride()).isEqualByComparingTo("5");
    }

    @Test
    void DRF_1_post_partial_draft_without_client_is_accepted() throws Exception {
        BillingDocument empty = withVersion(draftSale(), 0L);
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(empty);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").isEmpty())
                .andExpect(jsonPath("$.lines").isEmpty())
                .andExpect(jsonPath("$.totals.totalCents").value(0));
    }

    @Test
    void PUR_2_sale_response_never_carries_a_supplier_invoice_number_even_if_the_request_sends_one()
            throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(saved());
        String body = "{\"clientId\":\"10\",\"supplierInvoiceNumber\":\"F-77\",\"lines\":[]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SALE"))
                .andExpect(jsonPath("$.supplierInvoiceNumber").isEmpty());
    }

    @Test
    void DRF_4_post_unknown_product_is_422_with_code() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_PRODUCT_NOT_FOUND, "Producto 99"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_PRODUCT_NOT_FOUND"));
    }

    @Test
    void DOC_7_post_invalid_line_from_domain_is_400_with_code() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_LINE, "precio negativo"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_LINE"));
    }

    @Test
    void VALIDATION_post_blank_product_id_is_400_and_use_case_not_called() throws Exception {
        String body = "{\"clientId\":\"10\",\"lines\":[{\"productId\":\"\",\"cantidad\":2}]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_non_positive_quantity_is_400() throws Exception {
        String body = "{\"clientId\":\"10\",\"lines\":[{\"productId\":\"1\",\"cantidad\":0}]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_negative_price_cents_is_400() throws Exception {
        String body = "{\"clientId\":\"10\",\"lines\":[{\"productId\":\"1\",\"cantidad\":1,\"precioUnitarioCents\":-1}]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_iva_out_of_range_is_400() throws Exception {
        String body = "{\"clientId\":\"10\",\"lines\":[{\"productId\":\"1\",\"cantidad\":1,\"ivaPct\":101}]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_without_lines_is_400() throws Exception {
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_malformed_json_is_400() throws Exception {
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));
    }

    @Test
    void VALIDATION_post_without_enterprise_id_is_400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_non_numeric_client_id_is_400_from_mapper() throws Exception {
        String body = "{\"clientId\":\"abc\",\"lines\":[]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    // ---------- PUT ----------

    @Test
    void DRF_2_put_replaces_draft_returns_200_and_bumped_version() throws Exception {
        BillingDocument doc = withVersion(readySaleDraft(), 4L);
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenReturn(doc);

        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));

        ArgumentCaptor<InvoiceDraftCommand> captor = ArgumentCaptor.forClass(InvoiceDraftCommand.class);
        verify(saveUseCase).update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(3L);
    }

    @Test
    void DRF_3_put_with_stale_version_is_409_concurrent_modification() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION, "version"));

        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_CONCURRENT_MODIFICATION"));
    }

    @Test
    void STA_2_put_on_emitted_is_409_invalid_state() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"));

        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void QRY_1_put_unknown_document_is_404() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(put(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void VALIDATION_put_with_invalid_uuid_is_400() throws Exception {
        mockMvc.perform(put(BASE + "/not-a-uuid").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    // ---------- DELETE ----------

    @Test
    void DRF_5_delete_draft_is_204_and_passes_version() throws Exception {
        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID).param("version", "3"))
                .andExpect(status().isNoContent());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), 3L);
    }

    @Test
    void DRF_5_delete_without_version_passes_null() throws Exception {
        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNoContent());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), null);
    }

    @Test
    void DRF_5_delete_non_draft_is_409() throws Exception {
        doThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"))
                .when(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), null);

        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void QRY_1_delete_unknown_document_is_404() throws Exception {
        doThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"))
                .when(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), null);

        mockMvc.perform(delete(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound());
    }

    // ---------- DUPLICATE ----------

    @Test
    void DRF_6_duplicate_returns_201_with_location_of_the_new_draft() throws Exception {
        BillingDocument copy = withVersion(readySaleDraft(), 0L);
        when(duplicateUseCase.duplicate(ENTERPRISE_ID, TYPE, uuid(5))).thenReturn(copy);

        mockMvc.perform(post(BASE + "/" + uuid(5) + "/duplicate").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", locationOf(copy)))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.number").isEmpty());
    }

    @Test
    void DRF_6_duplicate_non_draft_is_409() throws Exception {
        when(duplicateUseCase.duplicate(ENTERPRISE_ID, TYPE, uuid(5)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"));

        mockMvc.perform(post(BASE + "/" + uuid(5) + "/duplicate").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    // ---------- GET ----------

    @Test
    void QRY_1_get_returns_200_with_full_document() throws Exception {
        BillingDocument doc = withVersion(emittedSale(), 1L);
        when(getUseCase.get(ENTERPRISE_ID, TYPE, doc.id())).thenReturn(BillingDocumentView.of(doc));

        mockMvc.perform(get(BASE + "/" + doc.id()).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("FV-000007"))
                .andExpect(jsonPath("$.status").value("EMITIDA"))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.totals.totalCents").value(23800))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void QRY_1_get_unknown_or_foreign_document_is_404() throws Exception {
        when(getUseCase.get(ENTERPRISE_ID, TYPE, uuid(9)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(get(BASE + "/" + uuid(9)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void VALIDATION_get_with_invalid_uuid_is_400() throws Exception {
        mockMvc.perform(get(BASE + "/xyz").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(getUseCase);
    }

    // ---------- LIST ----------

    @Test
    void DRF_7_list_filters_by_status_and_returns_summaries_array() throws Exception {
        BillingDocument doc = withVersion(readySaleDraft(), 2L);
        when(listUseCase.list(ENTERPRISE_ID, TYPE, DocumentStatus.BORRADOR, null))
                .thenReturn(List.of(BillingDocumentSummary.from(doc)));

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "BORRADOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(doc.id().toString()))
                .andExpect(jsonPath("$[0].status").value("BORRADOR"))
                .andExpect(jsonPath("$[0].documentType").value("SALE_INVOICE"))
                .andExpect(jsonPath("$[0].totals.totalCents").value(23800))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].lines").doesNotExist());
    }

    @Test
    void QRY_1_list_without_status_passes_null_filter() throws Exception {
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(listUseCase).list(ENTERPRISE_ID, TYPE, null, null);
    }

    @Test
    void QRY_1_list_with_unknown_status_is_400() throws Exception {
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(listUseCase);
    }

    // ---------- EMIT ----------

    private static BillingDocument emittedDoc() {
        return withVersion(emittedSale(), 1L);
    }

    @Test
    void EMT_1_emit_returns_200_with_number_status_and_frozen_totals() throws Exception {
        BillingDocument doc = emittedDoc();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), 3L)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doc.id().toString()))
                .andExpect(jsonPath("$.number").value("FV-000007"))
                .andExpect(jsonPath("$.status").value("EMITIDA"))
                .andExpect(jsonPath("$.totals.totalCents").value(23800))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void EMT_4_emit_without_body_passes_null_version() throws Exception {
        BillingDocument doc = emittedDoc();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMITIDA"));

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, doc.id(), null);
    }

    @Test
    void EMT_4_emit_with_empty_json_object_passes_null_version_and_ignores_unknown_fields() throws Exception {
        BillingDocument doc = emittedDoc();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"totalCents\":1,\"number\":\"FV-1\"}"))
                .andExpect(status().isOk());

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, doc.id(), null);
    }

    @Test
    void EMT_4_emit_on_non_draft_is_409_invalid_state() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void DRF_3_emit_with_stale_version_is_409_concurrent_modification() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), 2L))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION, "version"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_CONCURRENT_MODIFICATION"));
    }

    @Test
    void QRY_1_emit_unknown_document_is_404() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(9), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE + "/" + uuid(9) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void EMT_1_emit_incomplete_draft_is_422_draft_incomplete() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DRAFT_INCOMPLETE, "sin lineas"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_DRAFT_INCOMPLETE"));
    }

    @Test
    void EMT_1_emit_with_inactive_reference_is_422() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(1), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INACTIVE_REFERENCE, "tercero inactivo"));

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
    void VALIDATION_emit_with_invalid_uuid_is_400_and_use_case_not_called() throws Exception {
        mockMvc.perform(post(BASE + "/not-a-uuid/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(emitUseCase);
    }

    @Test
    void VALIDATION_emit_without_enterprise_id_is_400() throws Exception {
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(emitUseCase);
    }

    @Test
    void VALIDATION_emit_with_malformed_body_is_400() throws Exception {
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(emitUseCase);
    }

    @Test
    @WithAnonymousUser
    void SEC_1_anonymous_emit_is_401() throws Exception {
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(emitUseCase);
    }

    @Test
    @WithMockUser(authorities = "OTRO#R")
    void SEC_2_user_without_emit_authority_is_not_forbidden_and_the_emission_is_invoked() throws Exception {
        BillingDocument doc = emittedDoc();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMITIDA"));

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, doc.id(), null);
    }

    @Test
    @WithMockUser(authorities = {"BFV#AN", "BFC#EM"})
    void SEC_2_annul_or_purchase_emit_authority_is_irrelevant_to_sale_emit_while_permissions_are_open()
            throws Exception {
        BillingDocument doc = emittedDoc();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, doc.id(), null);
    }

    @Test
    @WithMockUser(authorities = "BFV#EM")
    void SEC_2_emit_only_authority_can_also_consult_while_permissions_are_open() throws Exception {
        BillingDocument doc = emittedDoc();
        when(getUseCase.get(ENTERPRISE_ID, TYPE, doc.id())).thenReturn(BillingDocumentView.of(doc));

        mockMvc.perform(get(BASE + "/" + doc.id()).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(getUseCase).get(ENTERPRISE_ID, TYPE, doc.id());
    }

    // ---------- ANNUL ----------

    private static BillingDocument annulledDoc() {
        return withVersion(annulledSale(), 2L);
    }

    @Test
    void ANL_1_annul_returns_200_with_status_number_kept_and_reason() throws Exception {
        BillingDocument doc = annulledDoc();
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, doc.id(), "error de digitacion", 1L)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"error de digitacion\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doc.id().toString()))
                .andExpect(jsonPath("$.status").value("ANULADA"))
                .andExpect(jsonPath("$.number").value("FV-000007"))
                .andExpect(jsonPath("$.annulReason").value("error de digitacion"))
                .andExpect(jsonPath("$.annulledAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void ANL_1_annul_without_version_passes_null_version() throws Exception {
        BillingDocument doc = annulledDoc();
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, doc.id(), "error de digitacion", null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"error de digitacion\"}"))
                .andExpect(status().isOk());

        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, doc.id(), "error de digitacion", null);
    }

    @Test
    void ANL_1_annul_ignores_unknown_fields_of_the_body() throws Exception {
        BillingDocument doc = annulledDoc();
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, doc.id(), "motivo", null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"motivo\",\"status\":\"EMITIDA\",\"totalCents\":1}"))
                .andExpect(status().isOk());

        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, doc.id(), "motivo", null);
    }

    @Test
    void ANL_1_annul_with_blank_reason_is_400_annul_reason_required() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), "   ", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED, "motivo"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_ANNUL_REASON_REQUIRED"));
    }

    @Test
    void ANL_1_annul_without_reason_field_is_400_annul_reason_required() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), null, null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED, "motivo"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_ANNUL_REASON_REQUIRED"));
    }

    @Test
    void ANL_1_annul_without_body_is_400_annul_reason_required() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), null, null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED, "motivo"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_ANNUL_REASON_REQUIRED"));
    }

    @Test
    void ANL_1_annul_on_non_emitted_document_is_409_invalid_state() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "BORRADOR"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void ANL_2_annul_with_emitted_returns_is_409_has_emitted_returns() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_HAS_EMITTED_RETURNS, "devoluciones"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_HAS_EMITTED_RETURNS"));
    }

    @Test
    void DRF_3_annul_with_stale_version_is_409_concurrent_modification() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), "motivo", 2L))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION, "version"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\",\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_CONCURRENT_MODIFICATION"));
    }

    @Test
    void QRY_1_annul_unknown_document_is_404() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(9), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE + "/" + uuid(9) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void D12_annul_when_the_row_lock_cannot_be_acquired_is_503_sequence_busy() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(1), "motivo", null))
                .thenThrow(new PessimisticLockingFailureException("lock timeout"));

        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BILLING_SEQUENCE_BUSY"));
    }

    @Test
    void VALIDATION_annul_with_invalid_uuid_is_400_and_use_case_not_called() throws Exception {
        mockMvc.perform(post(BASE + "/not-a-uuid/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(annulUseCase);
    }

    @Test
    void VALIDATION_annul_without_enterprise_id_is_400() throws Exception {
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(annulUseCase);
    }

    @Test
    void VALIDATION_annul_with_malformed_body_is_400() throws Exception {
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(annulUseCase);
    }

    @Test
    @WithAnonymousUser
    void SEC_1_anonymous_annul_is_401() throws Exception {
        mockMvc.perform(post(BASE + "/" + uuid(1) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(annulUseCase);
    }

    @Test
    @WithMockUser(authorities = {"BFV#R", "BFV#C", "BFV#U", "BFV#D", "BFV#EM"})
    void SEC_2_user_with_emit_but_without_annul_authority_is_not_forbidden_and_the_annulment_is_invoked()
            throws Exception {
        BillingDocument doc = annulledDoc();
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, doc.id(), "motivo", null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk());

        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, doc.id(), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = {"BFC#AN", "BNC#AN", "BDC#AN"})
    void SEC_2_annul_authority_of_other_resources_is_irrelevant_to_sale_annul_while_permissions_are_open()
            throws Exception {
        BillingDocument doc = annulledDoc();
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, doc.id(), "motivo", null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk());

        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, doc.id(), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = "BFV#AN")
    void SEC_2_annul_only_authority_can_annul_and_can_also_consult_while_permissions_are_open() throws Exception {
        BillingDocument doc = annulledDoc();
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, doc.id(), "motivo", null)).thenReturn(doc);
        when(getUseCase.get(ENTERPRISE_ID, TYPE, doc.id())).thenReturn(BillingDocumentView.of(doc));

        mockMvc.perform(post(BASE + "/" + doc.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + doc.id()).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(getUseCase).get(ENTERPRISE_ID, TYPE, doc.id());
    }

    // ---------- SEC-1 / SEC-2 basicos ----------

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

        verifyNoInteractions(saveUseCase, duplicateUseCase, deleteUseCase, getUseCase, listUseCase);
    }

    @Test
    @WithMockUser(authorities = "BFV#R")
    void SEC_2_consult_only_can_also_create_update_delete_or_duplicate_while_permissions_are_open()
            throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(InvoiceDraftCommand.class))).thenReturn(saved());
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(1)), any(InvoiceDraftCommand.class)))
                .thenReturn(saved());
        when(duplicateUseCase.duplicate(ENTERPRISE_ID, TYPE, uuid(1))).thenReturn(saved());

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

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(1), null);
    }

    @Test
    @WithMockUser(authorities = "BFV#C")
    void SEC_2_create_only_can_also_consult_while_permissions_are_open() throws Exception {
        BillingDocument doc = emittedDoc();
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());
        when(getUseCase.get(ENTERPRISE_ID, TYPE, uuid(1))).thenReturn(BillingDocumentView.of(doc));

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + uuid(1)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(listUseCase).list(ENTERPRISE_ID, TYPE, null, null);
        verify(getUseCase).get(ENTERPRISE_ID, TYPE, uuid(1));
    }

    @Test
    @WithMockUser(authorities = "BFC#R")
    void SEC_2_purchase_authority_is_irrelevant_to_the_sale_resource_while_permissions_are_open()
            throws Exception {
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isOk());

        verify(listUseCase).list(ENTERPRISE_ID, TYPE, null, null);
    }

    // ---------- H8d: sub-recursos de devoluciones de la factura (RET-9, RET-10, RET-11) ----------

    @Test
    void RET_9_return_availability_shows_sold_returned_available_and_derived_state() throws Exception {
        BillingDocument invoice = emittedSale(); // linea 1: 2 unidades a 100.00 + 19%
        BillingDocument emitted = emittedReturnOf(invoice, uuid(2), 3L, returnLineOf(invoice, 1, "1"));
        when(availabilityUseCase.availability(ENTERPRISE_ID, TYPE, uuid(1)))
                .thenReturn(new ReturnAvailabilityCalculator().availability(invoice, List.of(emitted)));

        mockMvc.perform(get(BASE + "/" + uuid(1) + "/return-availability").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$.state").value("PARCIAL"))
                .andExpect(jsonPath("$.returnable").value(true))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value("1"))
                .andExpect(jsonPath("$.lines[0].sold").value(2.0))
                .andExpect(jsonPath("$.lines[0].returned").value(1.0))
                .andExpect(jsonPath("$.lines[0].available").value(1.0))
                .andExpect(jsonPath("$.lines[0].precioUnitarioCents").value(10000))
                .andExpect(jsonPath("$.lines[0].ivaPct").value(19));
    }

    @Test
    void RET_9_return_availability_of_an_unknown_or_other_kind_invoice_is_404() throws Exception {
        when(availabilityUseCase.availability(ENTERPRISE_ID, TYPE, uuid(9)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(get(BASE + "/" + uuid(9) + "/return-availability").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void RET_9_return_availability_without_enterprise_id_is_400() throws Exception {
        mockMvc.perform(get(BASE + "/" + uuid(1) + "/return-availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(availabilityUseCase);
    }

    @Test
    void RET_10_returns_of_an_invoice_lists_each_return_with_its_ordinal_and_drafts_without_one() throws Exception {
        BillingDocument invoice = emittedSale();
        BillingDocument emitted = emittedReturnOf(invoice, uuid(2), 3L, returnLineOf(invoice, 1, "1"));
        BillingDocument draft = returnDraftOf(invoice, uuid(3), null, returnLineOf(invoice, 1, "1"));
        when(returnsUseCase.returnsOf(ENTERPRISE_ID, TYPE, uuid(1))).thenReturn(List.of(
                new ReturnSummary(BillingDocumentSummary.from(emitted), 1),
                new ReturnSummary(BillingDocumentSummary.from(draft), null)));

        mockMvc.perform(get(BASE + "/" + uuid(1) + "/returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].number").value("NC-000003"))
                .andExpect(jsonPath("$[0].ordinal").value(1))
                .andExpect(jsonPath("$[0].status").value("EMITIDA"))
                .andExpect(jsonPath("$[0].documentType").value("SALE_RETURN"))
                .andExpect(jsonPath("$[0].invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$[0].totals.totalCents").value(11900))
                .andExpect(jsonPath("$[1].status").value("BORRADOR"))
                .andExpect(jsonPath("$[1].number").isEmpty())
                .andExpect(jsonPath("$[1].ordinal").isEmpty())
                .andExpect(jsonPath("$[0].lines").doesNotExist());
    }

    @Test
    void RET_10_returns_of_an_unknown_invoice_is_404() throws Exception {
        when(returnsUseCase.returnsOf(ENTERPRISE_ID, TYPE, uuid(9)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(get(BASE + "/" + uuid(9) + "/returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void RET_11_with_returns_lists_invoices_with_count_returned_total_and_derived_state() throws Exception {
        BillingDocument invoice = emittedSale();
        when(withReturnsUseCase.list(ENTERPRISE_ID, TYPE)).thenReturn(List.of(
                new InvoiceWithReturnsSummary(BillingDocumentSummary.from(invoice), 2, money("119.00"),
                        ReturnStatus.PARCIAL)));

        mockMvc.perform(get(BASE + "/with-returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$[0].invoiceNumber").value("FV-000007"))
                .andExpect(jsonPath("$[0].kind").value("SALE"))
                .andExpect(jsonPath("$[0].clientName").value("Cliente 10"))
                .andExpect(jsonPath("$[0].invoiceTotalCents").value(23800))
                .andExpect(jsonPath("$[0].returnsCount").value(2))
                .andExpect(jsonPath("$[0].returnedTotalCents").value(11900))
                .andExpect(jsonPath("$[0].state").value("PARCIAL"));
    }

    @Test
    void RET_11_with_returns_is_not_captured_by_the_id_route() throws Exception {
        when(withReturnsUseCase.list(ENTERPRISE_ID, TYPE)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/with-returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(withReturnsUseCase).list(ENTERPRISE_ID, TYPE);
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
    @WithMockUser(authorities = {"BNC#R", "BNC#C", "BFV#C", "BFV#EM"})
    void SEC_2_return_sub_resources_do_not_need_the_invoice_consult_authority_while_permissions_are_open()
            throws Exception {
        BillingDocument invoice = emittedSale();
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

    @Test
    @WithMockUser(authorities = "BFV#R")
    void SEC_2_invoice_consult_authority_opens_the_return_sub_resources() throws Exception {
        when(withReturnsUseCase.list(ENTERPRISE_ID, TYPE)).thenReturn(List.of());
        when(returnsUseCase.returnsOf(ENTERPRISE_ID, TYPE, uuid(1))).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/with-returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + uuid(1) + "/returns").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
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
