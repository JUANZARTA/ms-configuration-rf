package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.transaction;

import co.unicauca.edu.co.contables.billing.application.ports.input.AnnulDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.DeleteDocumentDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.DuplicateInvoiceDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.EmitDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.SaveInvoiceDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.SaveReturnDraftUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.output.AccountingEntryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingDocumentRepositoryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingExecutionContextPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdGeneratorPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingOutboxPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.DocumentNumberSequencePort;
import co.unicauca.edu.co.contables.billing.application.ports.output.EnterprisePort;
import co.unicauca.edu.co.contables.billing.application.ports.output.ProductPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.ThirdPartyPort;
import co.unicauca.edu.co.contables.billing.application.service.BillingAnnulmentService;
import co.unicauca.edu.co.contables.billing.application.service.BillingDocumentLoader;
import co.unicauca.edu.co.contables.billing.application.service.BillingDraftDeletionService;
import co.unicauca.edu.co.contables.billing.application.service.BillingEmissionService;
import co.unicauca.edu.co.contables.billing.application.service.BillingInvoiceDraftService;
import co.unicauca.edu.co.contables.billing.application.service.BillingReferenceResolver;
import co.unicauca.edu.co.contables.billing.application.service.BillingReturnDraftService;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de escritura (D3): los servicios de aplicacion son Java plano (no beans) y se
 * construyen aqui a partir de los puertos; cada metodo abre la transaccion y delega. Cubre
 * facturas y devoluciones (la emision, la anulacion y el borrado son comunes a ambas familias).
 */
@Component("billingTransactionalCommandUseCases")
public class BillingTransactionalCommandUseCases
        implements SaveInvoiceDraftUseCase, SaveReturnDraftUseCase, DuplicateInvoiceDraftUseCase,
        DeleteDocumentDraftUseCase, EmitDocumentUseCase, AnnulDocumentUseCase {

    private final BillingInvoiceDraftService draftService;
    private final BillingReturnDraftService returnDraftService;
    private final BillingDraftDeletionService deletionService;
    private final BillingEmissionService emissionService;
    private final BillingAnnulmentService annulmentService;

    public BillingTransactionalCommandUseCases(BillingDocumentRepositoryPort repository,
            ThirdPartyPort thirdParties, ProductPort products, EnterprisePort enterprises,
            DocumentNumberSequencePort sequence, BillingOutboxPort outbox,
            BillingExecutionContextPort context, BillingClockPort clock, BillingIdGeneratorPort ids,
            StockMovementPort stock, AccountingEntryPort accounting) {
        BillingDocumentLoader loader = new BillingDocumentLoader(repository);
        BillingReferenceResolver resolver = new BillingReferenceResolver(thirdParties, products, enterprises);
        this.draftService = new BillingInvoiceDraftService(repository, loader, resolver, context, clock, ids);
        this.returnDraftService = new BillingReturnDraftService(repository, loader, context, clock, ids);
        this.deletionService = new BillingDraftDeletionService(repository, loader);
        this.emissionService = new BillingEmissionService(repository, loader, resolver, sequence, outbox, context,
                clock, ids, stock, accounting);
        this.annulmentService = new BillingAnnulmentService(repository, loader, outbox, context, clock, ids, stock,
                accounting);
    }

    @Override
    @Transactional
    public BillingDocument create(String enterpriseId, DocumentType type, InvoiceDraftCommand command) {
        return draftService.create(enterpriseId, type, command);
    }

    @Override
    @Transactional
    public BillingDocument update(String enterpriseId, DocumentType type, UUID id, InvoiceDraftCommand command) {
        return draftService.update(enterpriseId, type, id, command);
    }

    @Override
    @Transactional
    public BillingDocument create(String enterpriseId, DocumentType type, ReturnDraftCommand command) {
        return returnDraftService.create(enterpriseId, type, command);
    }

    @Override
    @Transactional
    public BillingDocument update(String enterpriseId, DocumentType type, UUID id, ReturnDraftCommand command) {
        return returnDraftService.update(enterpriseId, type, id, command);
    }

    @Override
    @Transactional
    public BillingDocument duplicate(String enterpriseId, DocumentType type, UUID id) {
        return draftService.duplicate(enterpriseId, type, id);
    }

    @Override
    @Transactional
    public void delete(String enterpriseId, DocumentType type, UUID id, Long version) {
        deletionService.delete(enterpriseId, type, id, version);
    }

    /**
     * Documento, secuencia, integraciones (stock, asientos) y outbox comparten esta unica transaccion
     * (EMT-3, EVT-1): si cualquiera falla se revierte todo. El bloqueo de la secuencia se toma tarde
     * (despues de validar) y se libera con el commit o rollback. Repetir la emision de un documento
     * ya EMITIDA devuelve el mismo documento sin tocar nada.
     */
    @Override
    @Transactional
    public BillingDocument emit(String enterpriseId, DocumentType type, UUID id, Long version) {
        return emissionService.emit(enterpriseId, type, id, version);
    }

    /**
     * La fila del documento se bloquea al cargarlo y el cambio de estado, las integraciones (stock,
     * asientos), el evento del outbox y la liberacion del bloqueo comparten esta unica transaccion
     * (ANL-1, EVT-1, D12). Repetir la anulacion de un documento ya ANULADA devuelve el mismo
     * documento sin tocar nada.
     */
    @Override
    @Transactional
    public BillingDocument annul(String enterpriseId, DocumentType type, UUID id, String reason, Long version) {
        return annulmentService.annul(enterpriseId, type, id, reason, version);
    }
}
