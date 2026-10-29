package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support;

import co.unicauca.edu.co.contables.billing.application.ports.input.AnnulDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.CreateDocumentIdempotentlyUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.DeleteDocumentDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.EmitDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.GetDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.ListDocumentsUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.SaveReturnDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentView;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.AnnulRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.BillingReturnResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.EmitRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.ReturnDraftRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.ReturnSummaryResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.mapper.BillingRestMapper;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Logica HTTP compartida por los controladores de devolucion de venta (NC) y de compra (DC), D4.
 * Crear/actualizar usan {@link SaveReturnDraftUseCase}; eliminar, emitir, anular y consultar reutilizan
 * los casos de uso genericos con el tipo de devolucion. Cada controlador conserva su
 * {@code @PreAuthorize} (BNC / BDC) y solo aporta el {@link DocumentType} y su ruta base.
 */
@Component("billingReturnEndpointSupport")
public class BillingReturnEndpointSupport {

    private final SaveReturnDraftUseCase saveUseCase;
    private final CreateDocumentIdempotentlyUseCase idempotentCreateUseCase;
    private final DeleteDocumentDraftUseCase deleteUseCase;
    private final GetDocumentUseCase getUseCase;
    private final ListDocumentsUseCase listUseCase;
    private final EmitDocumentUseCase emitUseCase;
    private final AnnulDocumentUseCase annulUseCase;
    private final BillingRestMapper mapper;

    public BillingReturnEndpointSupport(SaveReturnDraftUseCase saveUseCase,
            CreateDocumentIdempotentlyUseCase idempotentCreateUseCase, DeleteDocumentDraftUseCase deleteUseCase, GetDocumentUseCase getUseCase,
            ListDocumentsUseCase listUseCase, EmitDocumentUseCase emitUseCase,
            AnnulDocumentUseCase annulUseCase, BillingRestMapper mapper) {
        this.saveUseCase = saveUseCase;
        this.idempotentCreateUseCase = idempotentCreateUseCase;
        this.deleteUseCase = deleteUseCase;
        this.getUseCase = getUseCase;
        this.listUseCase = listUseCase;
        this.emitUseCase = emitUseCase;
        this.annulUseCase = annulUseCase;
        this.mapper = mapper;
    }

    /**
     * POST: 201 con Location apuntando al nuevo borrador de devolucion (RET-4). Con el header opcional
     * {@code Idempotency-Key} (UUID) repetir la peticion devuelve la MISMA devolucion (tambien 201 y
     * mismo Location) y la misma clave con otro cuerpo es 409 BILLING_IDEMPOTENCY_CONFLICT.
     */
    public ResponseEntity<BillingReturnResponse> create(DocumentType type, String basePath, String enterpriseId,
            ReturnDraftRequest request, String idempotencyKeyHeader) {
        String idempotencyKey = BillingIdempotencyHeader.parse(idempotencyKeyHeader);
        BillingDocument doc = idempotencyKey == null
                ? saveUseCase.create(enterpriseId, type, mapper.toCommand(request))
                : idempotentCreateUseCase.createReturn(enterpriseId, type, mapper.toCommand(request), idempotencyKey);
        URI location = UriComponentsBuilder.fromPath(basePath)
                .pathSegment(doc.id().toString())
                .queryParam("enterpriseId", enterpriseId)
                .build()
                .encode()
                .toUri();
        return ResponseEntity.created(location).body(mapper.toReturnResponse(BillingDocumentView.of(doc)));
    }

    /** PUT: reemplazo completo e idempotente del borrador (RET-4). */
    public ResponseEntity<BillingReturnResponse> update(DocumentType type, String enterpriseId, UUID id,
            ReturnDraftRequest request) {
        BillingDocument doc = saveUseCase.update(enterpriseId, type, id, mapper.toCommand(request));
        return ResponseEntity.ok(mapper.toReturnResponse(BillingDocumentView.of(doc)));
    }

    /** DELETE: 204 solo si era borrador. */
    public ResponseEntity<Void> delete(DocumentType type, String enterpriseId, UUID id, Long version) {
        deleteUseCase.delete(enterpriseId, type, id, version);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /{id}/emit: 200 con la devolucion emitida (RET-5, RET-6); el cuerpo es opcional.
     * Idempotente: repetirlo sobre una devolucion ya EMITIDA devuelve 200 con la misma.
     */
    public ResponseEntity<BillingReturnResponse> emit(DocumentType type, String enterpriseId, UUID id,
            EmitRequest request) {
        Long version = request == null ? null : request.version();
        BillingDocument doc = emitUseCase.emit(enterpriseId, type, id, version);
        return ResponseEntity.ok(mapper.toReturnResponse(BillingDocumentView.of(doc)));
    }

    /**
     * POST /{id}/annul: 200 con la devolucion anulada (RET-7). Un cuerpo ausente equivale a motivo
     * nulo: el dominio responde 400 BILLING_ANNUL_REASON_REQUIRED. Idempotente: sobre una devolucion
     * ya ANULADA devuelve 200 con la misma.
     */
    public ResponseEntity<BillingReturnResponse> annul(DocumentType type, String enterpriseId, UUID id,
            AnnulRequest request) {
        String reason = request == null ? null : request.reason();
        Long version = request == null ? null : request.version();
        BillingDocument doc = annulUseCase.annul(enterpriseId, type, id, reason, version);
        return ResponseEntity.ok(mapper.toReturnResponse(BillingDocumentView.of(doc)));
    }

    /** GET /{id}: la devolucion con su ordinal (RET-10) y referencia a la original. */
    public ResponseEntity<BillingReturnResponse> get(DocumentType type, String enterpriseId, UUID id) {
        return ResponseEntity.ok(mapper.toReturnResponse(getUseCase.get(enterpriseId, type, id)));
    }

    /**
     * GET ?status=&amp;invoiceId=: resumenes sin lineas, mas recientes primero. Este listado no
     * calcula ordinales (nulos); para ellos se usa {@code GET /{invoices}/{id}/returns}.
     */
    public ResponseEntity<List<ReturnSummaryResponse>> list(DocumentType type, String enterpriseId,
            DocumentStatus status, UUID invoiceId) {
        List<ReturnSummaryResponse> body = listUseCase.list(enterpriseId, type, status, invoiceId).stream()
                .map(summary -> mapper.toReturnSummaryResponse(summary, null))
                .toList();
        return ResponseEntity.ok(body);
    }
}
