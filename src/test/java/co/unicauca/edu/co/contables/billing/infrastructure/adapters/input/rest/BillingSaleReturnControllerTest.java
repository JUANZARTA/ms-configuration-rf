package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.TODAY;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSale;
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
import co.unicauca.edu.co.contables.billing.application.ports.input.EmitDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.GetDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.ListDocumentsUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.SaveReturnDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentSummary;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentView;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingSaleReturnController;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingReturnEndpointSupport;
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
 * Devoluciones de venta (NC) por REST (H8d, RET-1..11, SEC-1/2): crear/actualizar borrador sobre una
 * factura original, eliminar, emitir, anular, consultar, listar y mapeo de errores. Slice MVC con
 * configuracion minima de billing (D18); {@code BillingReturnEndpointSupport} exige los seis casos
 * de uso, por eso se declaran todos como mocks aunque cada test use solo algunos.
 */
@WebMvcTest(controllers = BillingSaleReturnController.class)
@ContextConfiguration(classes = {BillingSaleReturnController.class, BillingReturnEndpointSupport.class,
        BillingWebMvcTestConfig.class})
@ActiveProfiles("test")
@WithMockUser(authorities = {"BNC#R", "BNC#C", "BNC#U", "BNC#D", "BNC#EM", "BNC#AN"})
class BillingSaleReturnControllerTest {

    private static final String BASE = "/api/billing/sale-returns";
    private static final DocumentType TYPE = DocumentType.SALE_RETURN;
    private static final String VALID_BODY = """
            {"invoiceId":"%s","date":"2026-01-20","reason":"producto defectuoso",
             "lines":[{"sourceLineNo":1,"cantidad":1}],"version":3}
            """.formatted(uuid(1));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaveReturnDraftUseCase saveUseCase;
    @MockitoBean
    private CreateDocumentIdempotentlyUseCase idempotentCreateUseCase;
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

    private static BillingDocument original() {
        return emittedSale();
    }

    private static BillingDocument savedDraft() {
        BillingDocument original = original();
        return withVersion(returnDraftOf(original, uuid(2), "producto defectuoso",
                returnLineOf(original, 1, "1")), 0L);
    }

    private static BillingDocument emittedReturn() {
        BillingDocument original = original();
        return withVersion(emittedReturnOf(original, uuid(2), 3L, returnLineOf(original, 1, "1")), 1L);
    }

    private static String locationOf(BillingDocument doc) {
        return BASE + "/" + doc.id() + "?enterpriseId=" + ENTERPRISE_ID;
    }

    // ---------- POST ----------

    @Test
    void RET_9_post_creates_return_draft_with_201_location_and_cents_body() throws Exception {
        BillingDocument doc = savedDraft();
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class))).thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", locationOf(doc)))
                .andExpect(jsonPath("$.id").value(doc.id().toString()))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.documentType").value("SALE_RETURN"))
                .andExpect(jsonPath("$.kind").value("SALE"))
                .andExpect(jsonPath("$.invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$.originalNumber").value("FV-000007"))
                .andExpect(jsonPath("$.reason").value("producto defectuoso"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.lines[0].sourceLineNo").value(1))
                .andExpect(jsonPath("$.lines[0].cantidadDevuelta").value(1.0))
                .andExpect(jsonPath("$.lines[0].precioUnitarioCents").value(10000))
                .andExpect(jsonPath("$.lines[0].ivaPct").value(19))
                .andExpect(jsonPath("$.totals.subtotalCents").value(10000))
                .andExpect(jsonPath("$.totals.ivaCents").value(1900))
                .andExpect(jsonPath("$.totals.totalCents").value(11900));
    }

    @Test
    void RET_9_post_passes_the_original_reason_lines_and_version_to_the_use_case() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class))).thenReturn(savedDraft());

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReturnDraftCommand> captor = ArgumentCaptor.forClass(ReturnDraftCommand.class);
        verify(saveUseCase).create(eq(ENTERPRISE_ID), eq(TYPE), captor.capture());
        ReturnDraftCommand command = captor.getValue();
        assertThat(command.originalDocumentId()).isEqualTo(uuid(1));
        assertThat(command.reason()).isEqualTo("producto defectuoso");
        assertThat(command.issueDate()).isEqualTo(java.time.LocalDate.of(2026, 1, 20));
        assertThat(command.expectedVersion()).isEqualTo(3L);
        assertThat(command.lines()).hasSize(1);
        assertThat(command.lines().get(0).sourceLineNo()).isEqualTo(1);
        assertThat(command.lines().get(0).quantity()).isEqualByComparingTo("1");
    }

    @Test
    void RET_2_post_ignores_client_prices_totals_and_unknown_fields() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class))).thenReturn(savedDraft());
        String body = """
                {"invoiceId":"%s","totalCents":1,"totals":{"totalCents":1},
                 "lines":[{"sourceLineNo":1,"cantidad":1,"precioUnitarioCents":1,"ivaPct":0,
                           "productName":"hack","totalCents":1}]}
                """.formatted(uuid(1));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.totalCents").value(11900));

        ArgumentCaptor<ReturnDraftCommand> captor = ArgumentCaptor.forClass(ReturnDraftCommand.class);
        verify(saveUseCase).create(eq(ENTERPRISE_ID), eq(TYPE), captor.capture());
        assertThat(captor.getValue().lines()).hasSize(1);
        assertThat(captor.getValue().reason()).isNull();
        assertThat(captor.getValue().expectedVersion()).isNull();
    }

    @Test
    void RET_4_post_partial_draft_without_reason_or_lines_is_accepted() throws Exception {
        BillingDocument original = original();
        BillingDocument partial = withVersion(returnDraftOf(original, uuid(2), null), 0L);
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class))).thenReturn(partial);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + uuid(1) + "\",\"lines\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    void VALIDATION_post_without_invoice_id_is_400_and_use_case_not_called() throws Exception {
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_with_malformed_invoice_id_is_400_and_use_case_not_called() throws Exception {
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"no-es-uuid\",\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_without_lines_array_is_400() throws Exception {
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + uuid(1) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_line_without_source_line_is_400_before_unboxing() throws Exception {
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + uuid(1) + "\",\"lines\":[{\"cantidad\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_line_with_zero_or_negative_quantity_or_source_line_is_400() throws Exception {
        for (String line : List.of("{\"sourceLineNo\":1,\"cantidad\":0}",
                "{\"sourceLineNo\":1,\"cantidad\":-1}",
                "{\"sourceLineNo\":0,\"cantidad\":1}",
                "{\"sourceLineNo\":1}")) {
            mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"invoiceId\":\"" + uuid(1) + "\",\"lines\":[" + line + "]}"))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_reason_longer_than_500_is_400() throws Exception {
        String body = "{\"invoiceId\":\"" + uuid(1) + "\",\"reason\":\"" + "X".repeat(501) + "\",\"lines\":[]}";

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void VALIDATION_post_without_enterprise_id_is_400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(saveUseCase);
    }

    @Test
    void RET_5_post_quantity_above_available_is_422_return_quantity_exceeded() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED, "linea 1"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_RETURN_QUANTITY_EXCEEDED"));
    }

    @Test
    void RET_3_post_unknown_source_line_is_422_return_line_not_found() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_RETURN_LINE_NOT_FOUND, "linea 9"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_RETURN_LINE_NOT_FOUND"));
    }

    @Test
    void RET_1_post_over_a_non_emitted_invoice_is_409_original_not_emitted() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED, "BORRADOR"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_ORIGINAL_NOT_EMITTED"));
    }

    @Test
    void RET_1_post_over_an_unknown_or_cross_kind_invoice_is_404() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void RET_3_post_repeated_source_line_is_400_invalid_line() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_LINE, "linea repetida"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_LINE"));
    }

    // ---------- PUT ----------

    @Test
    void RET_4_put_replaces_the_draft_returns_200_and_passes_the_expected_version() throws Exception {
        BillingDocument original = original();
        BillingDocument doc = withVersion(returnDraftOf(original, uuid(2), "producto defectuoso",
                returnLineOf(original, 1, "2")), 4L);
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), any(ReturnDraftCommand.class)))
                .thenReturn(doc);

        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.totals.totalCents").value(23800));

        ArgumentCaptor<ReturnDraftCommand> captor = ArgumentCaptor.forClass(ReturnDraftCommand.class);
        verify(saveUseCase).update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(3L);
        assertThat(captor.getValue().originalDocumentId()).isEqualTo(uuid(1));
    }

    @Test
    void RET_4_put_with_stale_version_is_409() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION, "version"));

        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_CONCURRENT_MODIFICATION"));
    }

    @Test
    void RET_4_put_of_an_emitted_return_is_409_invalid_state() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"));

        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void RET_5_put_quantity_above_available_is_422() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED, "linea 1"));

        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_RETURN_QUANTITY_EXCEEDED"));
    }

    @Test
    void RET_1_put_of_a_document_of_another_type_is_404() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(9)), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(put(BASE + "/" + uuid(9)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void VALIDATION_put_with_malformed_id_in_path_is_400() throws Exception {
        mockMvc.perform(put(BASE + "/no-es-uuid").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(saveUseCase);
    }

    // ---------- DELETE ----------

    @Test
    void RET_4_delete_return_draft_is_204_with_the_return_type_and_version() throws Exception {
        mockMvc.perform(delete(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID).param("version", "3"))
                .andExpect(status().isNoContent());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(2), 3L);
    }

    @Test
    void RET_4_delete_without_version_passes_null() throws Exception {
        mockMvc.perform(delete(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNoContent());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(2), null);
    }

    @Test
    void RET_4_delete_of_an_emitted_return_is_409() throws Exception {
        doThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"))
                .when(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(2), null);

        mockMvc.perform(delete(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void RET_4_delete_unknown_is_404() throws Exception {
        doThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"))
                .when(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(9), null);

        mockMvc.perform(delete(BASE + "/" + uuid(9)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound());
    }

    // ---------- EMIT ----------

    @Test
    void RET_6_emit_returns_200_with_nc_number_and_the_original_reference() throws Exception {
        BillingDocument doc = emittedReturn();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), 3L)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("NC-000003"))
                .andExpect(jsonPath("$.status").value("EMITIDA"))
                .andExpect(jsonPath("$.documentType").value("SALE_RETURN"))
                .andExpect(jsonPath("$.invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$.originalNumber").value("FV-000007"))
                .andExpect(jsonPath("$.totals.totalCents").value(11900))
                .andExpect(jsonPath("$.emittedAt").isNotEmpty());
    }

    @Test
    void RET_6_emit_without_body_passes_null_version() throws Exception {
        BillingDocument doc = emittedReturn();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), null)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, doc.id(), null);
    }

    @Test
    void RET_1_emit_with_the_original_annulled_is_409_original_not_emitted() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED, "ANULADA"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_ORIGINAL_NOT_EMITTED"));
    }

    @Test
    void RET_5_emit_losing_the_race_for_quantity_is_422_return_quantity_exceeded() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED, "linea 1"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_RETURN_QUANTITY_EXCEEDED"));
    }

    @Test
    void RET_6_emit_without_reason_or_lines_is_422_draft_incomplete() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DRAFT_INCOMPLETE, "motivo"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_DRAFT_INCOMPLETE"));
    }

    @Test
    void RET_6_emit_on_non_draft_is_409_invalid_state() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "EMITIDA"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void RET_6_emit_with_busy_sequence_is_503() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_SEQUENCE_BUSY, "ocupada"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BILLING_SEQUENCE_BUSY"));
    }

    @Test
    void RET_6_emit_of_an_unknown_return_is_404() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(9), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE + "/" + uuid(9) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound());
    }

    // ---------- ANNUL ----------

    @Test
    void RET_7_annul_returns_200_keeping_the_nc_number_and_reason() throws Exception {
        BillingDocument doc = emittedReturnDocument();
        doc.annul("cliente desistio", false, doc.emittedAt().plusSeconds(60), "approver");
        BillingDocument annulled = withVersion(doc, 2L);
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, annulled.id(), "cliente desistio", 1L)).thenReturn(annulled);

        mockMvc.perform(post(BASE + "/" + annulled.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cliente desistio\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"))
                .andExpect(jsonPath("$.number").value("NC-000003"))
                .andExpect(jsonPath("$.annulReason").value("cliente desistio"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void RET_7_annul_without_reason_is_400_annul_reason_required() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(2), null, null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED, "motivo"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_ANNUL_REASON_REQUIRED"));
    }

    @Test
    void RET_7_annul_of_a_draft_is_409() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "BORRADOR"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"));
    }

    @Test
    void RET_7_annul_of_an_unknown_return_is_404() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(9), "motivo", null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE + "/" + uuid(9) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isNotFound());
    }

    private static BillingDocument emittedReturnDocument() {
        BillingDocument original = original();
        return emittedReturnOf(original, uuid(2), 3L, returnLineOf(original, 1, "1"));
    }

    // ---------- GET ----------

    @Test
    void RET_10_get_returns_the_return_with_its_ordinal_and_original_reference() throws Exception {
        BillingDocument doc = emittedReturn();
        when(getUseCase.get(ENTERPRISE_ID, TYPE, doc.id())).thenReturn(new BillingDocumentView(doc, null, 2));

        mockMvc.perform(get(BASE + "/" + doc.id()).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("NC-000003"))
                .andExpect(jsonPath("$.ordinal").value(2))
                .andExpect(jsonPath("$.invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$.originalNumber").value("FV-000007"))
                .andExpect(jsonPath("$.originalDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.reason").value("producto defectuoso"))
                .andExpect(jsonPath("$.clientName").value("Cliente 10"))
                .andExpect(jsonPath("$.lines[0].sourceLineNo").value(1))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void RET_10_get_of_a_draft_has_no_ordinal() throws Exception {
        BillingDocument doc = savedDraft();
        when(getUseCase.get(ENTERPRISE_ID, TYPE, doc.id())).thenReturn(BillingDocumentView.of(doc));

        mockMvc.perform(get(BASE + "/" + doc.id()).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.ordinal").isEmpty());
    }

    @Test
    void RET_1_get_of_a_purchase_return_through_the_sale_resource_is_404() throws Exception {
        when(getUseCase.get(ENTERPRISE_ID, TYPE, uuid(9)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(get(BASE + "/" + uuid(9)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    // ---------- LIST ----------

    @Test
    void RET_10_list_filters_by_status_and_invoice_and_returns_summaries_without_lines() throws Exception {
        BillingDocument doc = emittedReturn();
        when(listUseCase.list(ENTERPRISE_ID, TYPE, DocumentStatus.EMITIDA, uuid(1)))
                .thenReturn(List.of(BillingDocumentSummary.from(doc)));

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "EMITIDA")
                        .param("invoiceId", uuid(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentType").value("SALE_RETURN"))
                .andExpect(jsonPath("$[0].number").value("NC-000003"))
                .andExpect(jsonPath("$[0].invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$[0].originalNumber").value("FV-000007"))
                .andExpect(jsonPath("$[0].totals.totalCents").value(11900))
                .andExpect(jsonPath("$[0].lines").doesNotExist());
    }

    @Test
    void RET_10_list_without_filters_passes_nulls() throws Exception {
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(listUseCase).list(ENTERPRISE_ID, TYPE, null, null);
    }

    @Test
    void RET_10_list_with_unknown_status_or_malformed_invoice_id_is_400() throws Exception {
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("invoiceId", "no-es-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));

        verifyNoInteractions(listUseCase);
    }

    // ---------- SEC-1 / SEC-2 (recurso BNC) ----------

    @Test
    @WithAnonymousUser
    void SEC_1_anonymous_is_401_on_every_verb() throws Exception {
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(saveUseCase, deleteUseCase, getUseCase, listUseCase, emitUseCase, annulUseCase);
    }

    private static BillingDocument annulledReturn() {
        BillingDocument doc = emittedReturnDocument();
        doc.annul("motivo", false, doc.emittedAt().plusSeconds(60), "approver");
        return withVersion(doc, 2L);
    }

    @Test
    @WithMockUser(authorities = {"BFV#R", "BFV#C", "BFV#U", "BFV#D", "BFV#EM", "BFV#AN",
            "BDC#R", "BDC#C", "BDC#U", "BDC#D", "BDC#EM", "BDC#AN"})
    void SEC_2_invoice_and_purchase_return_authorities_are_irrelevant_to_the_sale_return_resource_while_permissions_are_open()
            throws Exception {
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenReturn(savedDraft());
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null)).thenReturn(emittedReturn());
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null)).thenReturn(annulledReturn());

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isOk());
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk());

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, uuid(2), null);
        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = "BNC#R")
    void SEC_2_consult_only_can_also_create_update_delete_emit_or_annul_while_permissions_are_open()
            throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenReturn(savedDraft());
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), any(ReturnDraftCommand.class)))
                .thenReturn(savedDraft());
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null)).thenReturn(emittedReturn());
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null)).thenReturn(annulledReturn());

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(2), null);
        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, uuid(2), null);
        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = {"BNC#R", "BNC#C", "BNC#U", "BNC#D", "BNC#AN"})
    void SEC_2_user_without_emit_authority_is_not_forbidden_and_the_emission_is_invoked() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null)).thenReturn(emittedReturn());

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMITIDA"));

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, uuid(2), null);
    }

    @Test
    @WithMockUser(authorities = {"BNC#R", "BNC#C", "BNC#U", "BNC#D", "BNC#EM"})
    void SEC_2_user_without_annul_authority_is_not_forbidden_and_the_annulment_is_invoked() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null)).thenReturn(annulledReturn());

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"));

        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null);
    }

    @Test
    @WithMockUser(authorities = "BNC#C")
    void SEC_2_create_only_can_also_consult_while_permissions_are_open() throws Exception {
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());
        when(getUseCase.get(ENTERPRISE_ID, TYPE, uuid(2)))
                .thenReturn(BillingDocumentView.of(emittedReturn()));

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(listUseCase).list(ENTERPRISE_ID, TYPE, null, null);
        verify(getUseCase).get(ENTERPRISE_ID, TYPE, uuid(2));
    }

    // ---------- Idempotency-Key (IDM-1, IDM-2) ----------

    private static final String IDEMPOTENCY_KEY = "6f1c1f3a-3e56-4c7a-9a0e-0d3a5c8d1b11";

    private static String idempotentLocationOf(BillingDocument doc) {
        return BASE + "/" + doc.id() + "?enterpriseId=" + ENTERPRISE_ID;
    }

    @Test
    void IDM_1_post_with_idempotency_key_uses_the_idempotent_use_case_and_answers_201_with_location()
            throws Exception {
        BillingDocument doc = savedDraft();
        when(idempotentCreateUseCase.createReturn(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class), eq(IDEMPOTENCY_KEY)))
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
        BillingDocument doc = savedDraft();
        when(idempotentCreateUseCase.createReturn(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class), eq(IDEMPOTENCY_KEY)))
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
        BillingDocument doc = savedDraft();
        when(idempotentCreateUseCase.createReturn(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class), eq(IDEMPOTENCY_KEY)))
                .thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY.toUpperCase())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void IDM_2_same_key_with_another_body_is_409_idempotency_conflict() throws Exception {
        when(idempotentCreateUseCase.createReturn(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class), eq(IDEMPOTENCY_KEY)))
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
        BillingDocument doc = savedDraft();
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class))).thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        org.mockito.Mockito.verifyNoInteractions(idempotentCreateUseCase);
    }
}
