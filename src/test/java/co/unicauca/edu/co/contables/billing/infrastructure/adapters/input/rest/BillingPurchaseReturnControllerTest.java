package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedPurchase;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedReturnOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnDraftOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.returnLineOf;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.withVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingPurchaseReturnController;
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
 * Devoluciones de compra (DC) por REST (H8d, RET-8, PUR, SEC-1/2): mismo soporte de endpoints que la
 * devolucion de venta, ruta {@code /api/billing/purchase-returns} y recurso de permisos BDC.
 */
@WebMvcTest(controllers = BillingPurchaseReturnController.class)
@ContextConfiguration(classes = {BillingPurchaseReturnController.class, BillingReturnEndpointSupport.class,
        BillingWebMvcTestConfig.class})
@ActiveProfiles("test")
@WithMockUser(authorities = {"BDC#R", "BDC#C", "BDC#U", "BDC#D", "BDC#EM", "BDC#AN"})
class BillingPurchaseReturnControllerTest {

    private static final String BASE = "/api/billing/purchase-returns";
    private static final DocumentType TYPE = DocumentType.PURCHASE_RETURN;
    private static final String VALID_BODY = """
            {"invoiceId":"%s","date":"2026-01-20","reason":"mercancia danada",
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

    private static BillingDocument savedDraft() {
        BillingDocument original = emittedPurchase();
        return withVersion(returnDraftOf(original, uuid(2), "mercancia danada",
                returnLineOf(original, 1, "1")), 0L);
    }

    private static BillingDocument emittedReturn() {
        BillingDocument original = emittedPurchase();
        return withVersion(emittedReturnOf(original, uuid(2), 4L, returnLineOf(original, 1, "1")), 1L);
    }

    private static String locationOf(BillingDocument doc) {
        return BASE + "/" + doc.id() + "?enterpriseId=" + ENTERPRISE_ID;
    }

    // ---------- POST / PUT / DELETE ----------

    @Test
    void RET_8_post_creates_purchase_return_draft_with_201_location_and_purchase_kind() throws Exception {
        BillingDocument doc = savedDraft();
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class))).thenReturn(doc);

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", locationOf(doc)))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.kind").value("PURCHASE"))
                .andExpect(jsonPath("$.documentType").value("PURCHASE_RETURN"))
                .andExpect(jsonPath("$.invoiceId").value(uuid(1).toString()))
                .andExpect(jsonPath("$.originalNumber").value("FC-000007"))
                .andExpect(jsonPath("$.clientId").value("20"))
                .andExpect(jsonPath("$.reason").value("mercancia danada"))
                .andExpect(jsonPath("$.totals.totalCents").value(11900));
    }

    @Test
    void RET_8_post_passes_the_purchase_invoice_id_and_lines_to_the_use_case() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class))).thenReturn(savedDraft());

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReturnDraftCommand> captor = ArgumentCaptor.forClass(ReturnDraftCommand.class);
        verify(saveUseCase).create(eq(ENTERPRISE_ID), eq(TYPE), captor.capture());
        assertThat(captor.getValue().originalDocumentId()).isEqualTo(uuid(1));
        assertThat(captor.getValue().lines()).hasSize(1);
        assertThat(captor.getValue().expectedVersion()).isEqualTo(3L);
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
    void RET_5_post_quantity_above_available_is_422() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED, "linea 1"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_RETURN_QUANTITY_EXCEEDED"));
    }

    @Test
    void RET_8_post_over_a_sale_invoice_through_the_purchase_resource_is_404() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void RET_1_post_over_a_non_emitted_purchase_invoice_is_409() throws Exception {
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED, "ANULADA"));

        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_ORIGINAL_NOT_EMITTED"));
    }

    @Test
    void RET_4_put_replaces_the_draft_and_passes_the_purchase_type() throws Exception {
        BillingDocument doc = withVersion(savedDraft(), 4L);
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), any(ReturnDraftCommand.class)))
                .thenReturn(doc);

        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void RET_4_put_with_stale_version_is_409() throws Exception {
        when(saveUseCase.update(eq(ENTERPRISE_ID), eq(TYPE), eq(uuid(2)), any(ReturnDraftCommand.class)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION, "version"));

        mockMvc.perform(put(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void RET_4_delete_return_draft_is_204_and_uses_the_purchase_return_type() throws Exception {
        mockMvc.perform(delete(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID).param("version", "3"))
                .andExpect(status().isNoContent());

        verify(deleteUseCase).delete(ENTERPRISE_ID, TYPE, uuid(2), 3L);
    }

    // ---------- EMIT / ANNUL ----------

    @Test
    void RET_6_emit_returns_200_with_dc_number() throws Exception {
        BillingDocument doc = emittedReturn();
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, doc.id(), 3L)).thenReturn(doc);

        mockMvc.perform(post(BASE + "/" + doc.id() + "/emit").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("DC-000004"))
                .andExpect(jsonPath("$.status").value("EMITIDA"))
                .andExpect(jsonPath("$.originalNumber").value("FC-000007"));
    }

    @Test
    void RET_5_emit_losing_the_race_for_quantity_is_422() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_RETURN_QUANTITY_EXCEEDED, "linea 1"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_RETURN_QUANTITY_EXCEEDED"));
    }

    @Test
    void RET_1_emit_with_the_original_annulled_is_409() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED, "ANULADA"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_ORIGINAL_NOT_EMITTED"));
    }

    @Test
    void RET_7_annul_returns_200_keeping_the_dc_number() throws Exception {
        BillingDocument doc = emittedReturn();
        BillingDocument toAnnul = BillingDocument.restore(doc.state());
        toAnnul.annul("proveedor corrigio", false, toAnnul.emittedAt().plusSeconds(60), "approver");
        BillingDocument annulled = withVersion(toAnnul, 2L);
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, annulled.id(), "proveedor corrigio", 1L)).thenReturn(annulled);

        mockMvc.perform(post(BASE + "/" + annulled.id() + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"proveedor corrigio\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"))
                .andExpect(jsonPath("$.number").value("DC-000004"))
                .andExpect(jsonPath("$.annulReason").value("proveedor corrigio"));
    }

    @Test
    void RET_7_annul_without_reason_is_400() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(2), null, null))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_ANNUL_REASON_REQUIRED, "motivo"));

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_ANNUL_REASON_REQUIRED"));
    }

    // ---------- GET / LIST ----------

    @Test
    void RET_10_get_returns_the_return_with_its_ordinal() throws Exception {
        BillingDocument doc = emittedReturn();
        when(getUseCase.get(ENTERPRISE_ID, TYPE, doc.id())).thenReturn(new BillingDocumentView(doc, null, 1));

        mockMvc.perform(get(BASE + "/" + doc.id()).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("DC-000004"))
                .andExpect(jsonPath("$.ordinal").value(1))
                .andExpect(jsonPath("$.kind").value("PURCHASE"))
                .andExpect(jsonPath("$.clientName").value("Proveedor 20"));
    }

    @Test
    void RET_8_get_of_a_sale_return_through_the_purchase_resource_is_404() throws Exception {
        when(getUseCase.get(ENTERPRISE_ID, TYPE, uuid(9)))
                .thenThrow(new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe"));

        mockMvc.perform(get(BASE + "/" + uuid(9)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void RET_10_list_filters_by_status_and_invoice() throws Exception {
        BillingDocument doc = emittedReturn();
        when(listUseCase.list(ENTERPRISE_ID, TYPE, DocumentStatus.EMITIDA, uuid(1)))
                .thenReturn(List.of(BillingDocumentSummary.from(doc)));

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "EMITIDA")
                        .param("invoiceId", uuid(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentType").value("PURCHASE_RETURN"))
                .andExpect(jsonPath("$[0].number").value("DC-000004"))
                .andExpect(jsonPath("$[0].originalNumber").value("FC-000007"));
    }

    @Test
    void RET_10_list_with_unknown_status_is_400() throws Exception {
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID).param("status", "NOPE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(listUseCase);
    }

    // ---------- SEC-1 / SEC-2 (recurso BDC) ----------

    @Test
    @WithAnonymousUser
    void SEC_1_anonymous_is_401_on_every_verb() throws Exception {
        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/" + uuid(2)).param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(saveUseCase, deleteUseCase, getUseCase, listUseCase, emitUseCase, annulUseCase);
    }

    private static BillingDocument annulledReturn() {
        BillingDocument toAnnul = BillingDocument.restore(emittedReturn().state());
        toAnnul.annul("motivo", false, toAnnul.emittedAt().plusSeconds(60), "approver");
        return withVersion(toAnnul, 2L);
    }

    @Test
    @WithMockUser(authorities = {"BNC#R", "BNC#C", "BNC#U", "BNC#D", "BNC#EM", "BNC#AN",
            "BFC#R", "BFC#C", "BFC#U", "BFC#D", "BFC#EM", "BFC#AN"})
    void SEC_2_sale_return_and_purchase_invoice_authorities_are_irrelevant_to_the_purchase_return_resource_while_permissions_are_open()
            throws Exception {
        when(listUseCase.list(ENTERPRISE_ID, TYPE, null, null)).thenReturn(List.of());
        when(saveUseCase.create(eq(ENTERPRISE_ID), eq(TYPE), any(ReturnDraftCommand.class)))
                .thenReturn(savedDraft());
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null)).thenReturn(emittedReturn());

        mockMvc.perform(get(BASE).param("enterpriseId", ENTERPRISE_ID)).andExpect(status().isOk());
        mockMvc.perform(post(BASE).param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk());

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, uuid(2), null);
    }

    @Test
    @WithMockUser(authorities = "BDC#R")
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
    @WithMockUser(authorities = {"BDC#R", "BDC#C", "BDC#U", "BDC#D", "BDC#AN"})
    void SEC_2_user_without_emit_authority_is_not_forbidden_and_the_emission_is_invoked() throws Exception {
        when(emitUseCase.emit(ENTERPRISE_ID, TYPE, uuid(2), null)).thenReturn(emittedReturn());

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/emit").param("enterpriseId", ENTERPRISE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMITIDA"));

        verify(emitUseCase).emit(ENTERPRISE_ID, TYPE, uuid(2), null);
    }

    @Test
    @WithMockUser(authorities = {"BDC#R", "BDC#C", "BDC#U", "BDC#D", "BDC#EM"})
    void SEC_2_user_without_annul_authority_is_not_forbidden_and_the_annulment_is_invoked() throws Exception {
        when(annulUseCase.annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null)).thenReturn(annulledReturn());

        mockMvc.perform(post(BASE + "/" + uuid(2) + "/annul").param("enterpriseId", ENTERPRISE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"));

        verify(annulUseCase).annul(ENTERPRISE_ID, TYPE, uuid(2), "motivo", null);
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
