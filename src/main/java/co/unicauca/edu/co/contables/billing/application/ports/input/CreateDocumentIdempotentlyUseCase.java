package co.unicauca.edu.co.contables.billing.application.ports.input;

import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;

/**
 * Creacion de borradores con clave de idempotencia (header {@code Idempotency-Key}): repetir la
 * misma peticion devuelve el MISMO documento sin crear otro; la misma clave con otro contenido
 * lanza {@code BILLING_IDEMPOTENCY_CONFLICT}. Sin clave se usa {@link SaveInvoiceDraftUseCase} /
 * {@link SaveReturnDraftUseCase} directamente (comportamiento anterior).
 */
public interface CreateDocumentIdempotentlyUseCase {

    BillingDocument createInvoice(String enterpriseId, DocumentType type, InvoiceDraftCommand command,
            String idempotencyKey);

    BillingDocument createReturn(String enterpriseId, DocumentType type, ReturnDraftCommand command,
            String idempotencyKey);
}
