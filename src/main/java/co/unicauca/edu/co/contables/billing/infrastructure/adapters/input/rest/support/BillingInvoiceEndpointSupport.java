package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support;

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
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.AnnulRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.BillingDocumentSummaryResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.BillingInvoiceResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.EmitRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.InvoiceDraftRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.InvoiceWithReturnsResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.ReturnAvailabilityResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.ReturnSummaryResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.mapper.BillingRestMapper;
import co.unicauca.edu.co.contables.billing.application.ports.input.view.BillingDocumentView;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Logica HTTP compartida por los controladores de facturas de venta y compra (D4): llama a los
 * casos de uso transaccionales por su puerto, arma status/Location y mapea a DTOs. Cada
 * controlador conserva su propio {@code @PreAuthorize} y solo aporta el {@link DocumentType} y su
 * ruta base. Incluye los sub-recursos de devoluciones de la factura (disponibilidad, devoluciones
 * y "facturas con devolucion"); las operaciones sobre la devolucion misma viven en
 * {@link BillingReturnEndpointSupport}.
 */
@Component("billingInvoiceEndpointSupport")
public class BillingInvoiceEndpointSupport {

    private final SaveInvoiceDraftUseCase saveUseCase;
    private final CreateDocumentIdempotentlyUseCase idempotentCreateUseCase;
    private final DuplicateInvoiceDraftUseCase duplicateUseCase;
    private final DeleteDocumentDraftUseCase deleteUseCase;
    private final GetDocumentUseCase getUseCase;
    private final ListDocumentsUseCase listUseCase;
    private final EmitDocumentUseCase emitUseCase;
    private final AnnulDocumentUseCase annulUseCase;
    private final GetReturnAvailabilityUseCase availabilityUseCase;
    private final ListDocumentReturnsUseCase returnsUseCase;
    private final ListInvoicesWithReturnsUseCase withReturnsUseCase;
    private final BillingRestMapper mapper;

    public BillingInvoiceEndpointSupport(SaveInvoiceDraftUseCase saveUseCase,
            CreateDocumentIdempotentlyUseCase idempotentCreateUseCase, DuplicateInvoiceDraftUseCase duplicateUseCase, DeleteDocumentDraftUseCase deleteUseCase,
            GetDocumentUseCase getUseCase, ListDocumentsUseCase listUseCase, EmitDocumentUseCase emitUseCase,
            AnnulDocumentUseCase annulUseCase, GetReturnAvailabilityUseCase availabilityUseCase,
            ListDocumentReturnsUseCase returnsUseCase, ListInvoicesWithReturnsUseCase withReturnsUseCase,
            BillingRestMapper mapper) {
        this.saveUseCase = saveUseCase;
        this.idempotentCreateUseCase = idempotentCreateUseCase;
        this.duplicateUseCase = duplicateUseCase;
        this.deleteUseCase = deleteUseCase;
        this.getUseCase = getUseCase;
        this.listUseCase = listUseCase;
        this.emitUseCase = emitUseCase;
        this.annulUseCase = annulUseCase;
        this.availabilityUseCase = availabilityUseCase;
        this.returnsUseCase = returnsUseCase;
        this.withReturnsUseCase = withReturnsUseCase;
        this.mapper = mapper;
    }

    /** GET /{id}/return-availability: vendido / devuelto / disponible por linea y estado derivado (RET-9). */
    public ResponseEntity<ReturnAvailabilityResponse> returnAvailability(DocumentType type, String enterpriseId,
            UUID id) {
        return ResponseEntity.ok(mapper.toAvailabilityResponse(availabilityUseCase.availability(enterpriseId, type, id)));
    }

    /** GET /{id}/returns: devoluciones de la factura con su ordinal, borradores al final (RET-10). */
    public ResponseEntity<List<ReturnSummaryResponse>> returnsOf(DocumentType type, String enterpriseId, UUID id) {
        List<ReturnSummaryResponse> body = returnsUseCase.returnsOf(enterpriseId, type, id).stream()
                .map(row -> mapper.toReturnSummaryResponse(row))
                .toList();
        return ResponseEntity.ok(body);
    }

    /** GET /with-returns: facturas con devoluciones emitidas y su estado derivado (RET-11). */
    public ResponseEntity<List<InvoiceWithReturnsResponse>> withReturns(DocumentType type, String enterpriseId) {
        List<InvoiceWithReturnsResponse> body = withReturnsUseCase.list(enterpriseId, type).stream()
                .map(mapper::toWithReturnsResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * POST /{id}/emit: 200 con el documento emitido; el cuerpo es opcional (EMT-1..4). Idempotente:
     * repetirlo sobre un documento ya EMITIDA devuelve 200 con el mismo documento.
     */
    public ResponseEntity<BillingInvoiceResponse> emit(DocumentType type, String enterpriseId, UUID id,
            EmitRequest request) {
        Long version = request == null ? null : request.version();
        BillingDocument doc = emitUseCase.emit(enterpriseId, type, id, version);
        return ResponseEntity.ok(mapper.toResponse(BillingDocumentView.of(doc)));
    }

    /**
     * POST /{id}/annul: 200 con el documento anulado (ANL-1). Un cuerpo ausente equivale a motivo
     * nulo: el dominio responde 400 BILLING_ANNUL_REASON_REQUIRED. Idempotente: sobre un documento ya
     * ANULADA devuelve 200 con el mismo documento.
     */
    public ResponseEntity<BillingInvoiceResponse> annul(DocumentType type, String enterpriseId, UUID id,
            AnnulRequest request) {
        String reason = request == null ? null : request.reason();
        Long version = request == null ? null : request.version();
        BillingDocument doc = annulUseCase.annul(enterpriseId, type, id, reason, version);
        return ResponseEntity.ok(mapper.toResponse(BillingDocumentView.of(doc)));
    }

    /**
     * POST: 201 con Location apuntando al nuevo borrador (DRF-1). Con el header opcional
     * {@code Idempotency-Key} (UUID) repetir la peticion devuelve el MISMO documento (tambien 201 y
     * mismo Location) y la misma clave con otro cuerpo es 409 BILLING_IDEMPOTENCY_CONFLICT.
     */
    public ResponseEntity<BillingInvoiceResponse> create(DocumentType type, String basePath,
            String enterpriseId, InvoiceDraftRequest request, String idempotencyKeyHeader) {
        String idempotencyKey = BillingIdempotencyHeader.parse(idempotencyKeyHeader);
        BillingDocument doc = idempotencyKey == null
                ? saveUseCase.create(enterpriseId, type, mapper.toCommand(request))
                : idempotentCreateUseCase.createInvoice(enterpriseId, type, mapper.toCommand(request), idempotencyKey);
        return created(basePath, enterpriseId, doc);
    }

    /** PUT: reemplazo completo e idempotente (DRF-2). */
    public ResponseEntity<BillingInvoiceResponse> update(DocumentType type, String enterpriseId, UUID id,
            InvoiceDraftRequest request) {
        BillingDocument doc = saveUseCase.update(enterpriseId, type, id, mapper.toCommand(request));
        return ResponseEntity.ok(mapper.toResponse(BillingDocumentView.of(doc)));
    }

    /** DELETE: 204 solo si era borrador (DRF-5). */
    public ResponseEntity<Void> delete(DocumentType type, String enterpriseId, UUID id, Long version) {
        deleteUseCase.delete(enterpriseId, type, id, version);
        return ResponseEntity.noContent().build();
    }

    /** POST /{id}/duplicate: 201 con el nuevo borrador (DRF-6). */
    public ResponseEntity<BillingInvoiceResponse> duplicate(DocumentType type, String basePath,
            String enterpriseId, UUID id) {
        BillingDocument copy = duplicateUseCase.duplicate(enterpriseId, type, id);
        return created(basePath, enterpriseId, copy);
    }

    /** GET /{id} (QRY-1, DRF-8: el caso de uso refresca el catalogo de un borrador). */
    public ResponseEntity<BillingInvoiceResponse> get(DocumentType type, String enterpriseId, UUID id) {
        return ResponseEntity.ok(mapper.toResponse(getUseCase.get(enterpriseId, type, id)));
    }

    /** GET ?status=: resumenes sin lineas, mas recientes primero (DRF-7, QRY-1). */
    public ResponseEntity<List<BillingDocumentSummaryResponse>> list(DocumentType type, String enterpriseId,
            DocumentStatus status) {
        List<BillingDocumentSummaryResponse> body = listUseCase.list(enterpriseId, type, status, null).stream()
                .map(mapper::toSummaryResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<BillingInvoiceResponse> created(String basePath, String enterpriseId,
            BillingDocument doc) {
        URI location = UriComponentsBuilder.fromPath(basePath)
                .pathSegment(doc.id().toString())
                .queryParam("enterpriseId", enterpriseId)
                .build()
                .encode()
                .toUri();
        return ResponseEntity.created(location).body(mapper.toResponse(BillingDocumentView.of(doc)));
    }
}
