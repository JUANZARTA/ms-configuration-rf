package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller;

import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.AnnulRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.BillingReturnResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.EmitRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.ReturnDraftRequest;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.dto.ReturnSummaryResponse;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingIdempotencyHeader;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingPermissions;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingReturnEndpointSupport;
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
 * Devoluciones de compra (DC-) sobre una factura de compra emitida, recurso de permisos BDC. Toda
 * la logica HTTP vive en {@link BillingReturnEndpointSupport}; este controlador solo fija el tipo
 * {@link DocumentType#PURCHASE_RETURN}, la ruta y los permisos propios.
 */
@RestController("billingPurchaseReturnController")
@RequestMapping(BillingPurchaseReturnController.BASE_PATH)
public class BillingPurchaseReturnController {

    public static final String BASE_PATH = "/api/billing/purchase-returns";
    private static final DocumentType TYPE = DocumentType.PURCHASE_RETURN;

    private final BillingReturnEndpointSupport support;

    public BillingPurchaseReturnController(BillingReturnEndpointSupport support) {
        this.support = support;
    }

    @PostMapping
    @PreAuthorize(BillingPermissions.PURCHASE_RETURN_CREATE)
    public ResponseEntity<BillingReturnResponse> create(
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @Valid @RequestBody ReturnDraftRequest request,
            @RequestHeader(name = BillingIdempotencyHeader.NAME, required = false) String idempotencyKey) {
        return support.create(TYPE, BASE_PATH, enterpriseId, request, idempotencyKey);
    }

    @PutMapping("/{id}")
    @PreAuthorize(BillingPermissions.PURCHASE_RETURN_UPDATE)
    public ResponseEntity<BillingReturnResponse> update(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @Valid @RequestBody ReturnDraftRequest request) {
        return support.update(TYPE, enterpriseId, id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(BillingPermissions.PURCHASE_RETURN_DELETE)
    public ResponseEntity<Void> delete(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestParam(name = "version", required = false) Long version) {
        return support.delete(TYPE, enterpriseId, id, version);
    }

    @PostMapping("/{id}/emit")
    @PreAuthorize(BillingPermissions.PURCHASE_RETURN_EMIT)
    public ResponseEntity<BillingReturnResponse> emit(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestBody(required = false) EmitRequest request) {
        return support.emit(TYPE, enterpriseId, id, request);
    }

    @PostMapping("/{id}/annul")
    @PreAuthorize(BillingPermissions.PURCHASE_RETURN_ANNUL)
    public ResponseEntity<BillingReturnResponse> annul(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestBody(required = false) AnnulRequest request) {
        return support.annul(TYPE, enterpriseId, id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize(BillingPermissions.PURCHASE_RETURN_READ)
    public ResponseEntity<BillingReturnResponse> get(
            @PathVariable("id") UUID id,
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId) {
        return support.get(TYPE, enterpriseId, id);
    }

    @GetMapping
    @PreAuthorize(BillingPermissions.PURCHASE_RETURN_READ)
    public ResponseEntity<List<ReturnSummaryResponse>> list(
            @RequestParam("enterpriseId") @NotBlank @Size(max = 50) String enterpriseId,
            @RequestParam(name = "status", required = false) DocumentStatus status,
            @RequestParam(name = "invoiceId", required = false) UUID invoiceId) {
        return support.list(TYPE, enterpriseId, status, invoiceId);
    }
}
