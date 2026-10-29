package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller;

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
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingInvoiceEndpointSupport;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingIdempotencyHeader;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingPermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Facturas de compra (FC-), recurso de permisos BFC. Mismo motor que la venta: toda la logica
 * HTTP vive en {@link BillingInvoiceEndpointSupport}; este controlador solo fija el tipo
 * {@link DocumentType#PURCHASE_INVOICE}, la ruta y los permisos propios. En {@code clientId} viaja
 * el identificador del proveedor y en {@code supplierInvoiceNumber} el numero de la factura del
 * proveedor (obligatorio para emitir, PUR-2).
 */
@RestController("billingPurchaseInvoiceController")
@RequestMapping(BillingPurchaseInvoiceController.BASE_PATH)
public class BillingPurchaseInvoiceController {

    public static final String BASE_PATH = "/api/billing/purchase-invoices";
    private static final DocumentType TYPE = DocumentType.PURCHASE_INVOICE;

    private final BillingInvoiceEndpointSupport support;

    public BillingPurchaseInvoiceController(BillingInvoiceEndpointSupport support) {
        this.support = support;
    }

    @PostMapping
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_CREATE)
    public ResponseEntity<BillingInvoiceResponse> create(
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @Valid @RequestBody InvoiceDraftRequest request,
            @RequestHeader(name = BillingIdempotencyHeader.NAME, required = false) String idempotencyKey) {
        return support.create(TYPE, BASE_PATH, enterpriseId, request, idempotencyKey);
    }

    @PutMapping("/{id}")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_UPDATE)
    public ResponseEntity<BillingInvoiceResponse> update(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @Valid @RequestBody InvoiceDraftRequest request) {
        return support.update(TYPE, enterpriseId, id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_DELETE)
    public ResponseEntity<Void> delete(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestParam(name = "version", required = false) Long version) {
        return support.delete(TYPE, enterpriseId, id, version);
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_CREATE)
    public ResponseEntity<BillingInvoiceResponse> duplicate(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId) {
        return support.duplicate(TYPE, BASE_PATH, enterpriseId, id);
    }

    @PostMapping("/{id}/emit")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_EMIT)
    public ResponseEntity<BillingInvoiceResponse> emit(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestBody(required = false) EmitRequest request) {
        return support.emit(TYPE, enterpriseId, id, request);
    }

    @PostMapping("/{id}/annul")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_ANNUL)
    public ResponseEntity<BillingInvoiceResponse> annul(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestBody(required = false) AnnulRequest request) {
        return support.annul(TYPE, enterpriseId, id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_READ)
    public ResponseEntity<BillingInvoiceResponse> get(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId) {
        return support.get(TYPE, enterpriseId, id);
    }

    @GetMapping
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_READ)
    public ResponseEntity<List<BillingDocumentSummaryResponse>> list(
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestParam(name = "status", required = false) DocumentStatus status) {
        return support.list(TYPE, enterpriseId, status);
    }

    // Sub-recursos de devoluciones de la factura: permiso de consulta de la factura (BFC#R), D17.

    @GetMapping("/{id}/return-availability")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_READ)
    public ResponseEntity<ReturnAvailabilityResponse> returnAvailability(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId) {
        return support.returnAvailability(TYPE, enterpriseId, id);
    }

    @GetMapping("/{id}/returns")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_READ)
    public ResponseEntity<List<ReturnSummaryResponse>> returnsOf(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId) {
        return support.returnsOf(TYPE, enterpriseId, id);
    }

    @GetMapping("/with-returns")
    @PreAuthorize(BillingPermissions.PURCHASE_INVOICE_READ)
    public ResponseEntity<List<InvoiceWithReturnsResponse>> withReturns(
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId) {
        return support.withReturns(TYPE, enterpriseId);
    }
}
