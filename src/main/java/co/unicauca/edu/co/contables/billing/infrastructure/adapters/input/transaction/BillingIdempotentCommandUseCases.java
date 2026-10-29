package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.transaction;

import co.unicauca.edu.co.contables.billing.application.ports.input.CreateDocumentIdempotentlyUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.SaveInvoiceDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.SaveReturnDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingDocumentRepositoryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdempotencyPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingTransactionRunnerPort;
import co.unicauca.edu.co.contables.billing.application.service.BillingIdempotentCreationService;
import co.unicauca.edu.co.contables.billing.application.service.BillingRequestFingerprint;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import org.springframework.stereotype.Component;

/**
 * Casos de uso de creacion con clave de idempotencia. A diferencia de
 * {@link BillingTransactionalCommandUseCases}, NO es {@code @Transactional}: el servicio abre sus
 * propias transacciones (consulta, crear + registrar la clave, releer al ganador de una carrera) y
 * la creacion real se delega al caso de uso transaccional, que se une a la transaccion que abre el
 * servicio.
 */
@Component("billingIdempotentCommandUseCases")
public class BillingIdempotentCommandUseCases implements CreateDocumentIdempotentlyUseCase {

    private final SaveInvoiceDraftUseCase invoiceDrafts;
    private final SaveReturnDraftUseCase returnDrafts;
    private final BillingIdempotentCreationService service;

    public BillingIdempotentCommandUseCases(SaveInvoiceDraftUseCase invoiceDrafts, SaveReturnDraftUseCase returnDrafts,
            BillingIdempotencyPort idempotency, BillingDocumentRepositoryPort repository,
            BillingTransactionRunnerPort transactions, BillingClockPort clock) {
        this.invoiceDrafts = invoiceDrafts;
        this.returnDrafts = returnDrafts;
        this.service = new BillingIdempotentCreationService(idempotency, repository, transactions, clock);
    }

    @Override
    public BillingDocument createInvoice(String enterpriseId, DocumentType type, InvoiceDraftCommand command,
            String idempotencyKey) {
        return service.create(enterpriseId, type, idempotencyKey, BillingRequestFingerprint.of(type, command),
                () -> invoiceDrafts.create(enterpriseId, type, command));
    }

    @Override
    public BillingDocument createReturn(String enterpriseId, DocumentType type, ReturnDraftCommand command,
            String idempotencyKey) {
        return service.create(enterpriseId, type, idempotencyKey, BillingRequestFingerprint.of(type, command),
                () -> returnDrafts.create(enterpriseId, type, command));
    }
}
