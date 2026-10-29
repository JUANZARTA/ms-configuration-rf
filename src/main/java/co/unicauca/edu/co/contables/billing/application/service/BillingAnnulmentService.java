package co.unicauca.edu.co.contables.billing.application.service;

import co.unicauca.edu.co.contables.billing.application.ports.input.AnnulDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.output.AccountingEntryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingDocumentRepositoryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingExecutionContextPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdGeneratorPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingOutboxPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDocumentAnnulled;
import co.unicauca.edu.co.contables.billing.domain.event.ReturnEventContext;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Anulacion de documentos (ANL-1, ANL-2) y de devoluciones (RET-7, A1). Java plano: la
 * transaccion la aporta el adaptador de entrada.
 *
 * <p>Orden: cargar CON BLOQUEO de fila (serializa contra la emision de devoluciones del mismo
 * original, D12; el orden de bloqueo es siempre documento -> secuencia) -> si es una factura
 * EMITIDA, consultar devoluciones EMITIDA -> anular (el dominio valida estado, motivo y
 * devoluciones) -> guardar -> agregar el evento al outbox. Todo en una sola transaccion: si algo
 * falla no queda ni cambio ni evento.
 */
public class BillingAnnulmentService implements AnnulDocumentUseCase {

    private final BillingDocumentRepositoryPort repository;
    private final BillingDocumentLoader loader;
    private final BillingOutboxPort outbox;
    private final BillingExecutionContextPort context;
    private final BillingClockPort clock;
    private final BillingIdGeneratorPort ids;
    private final StockMovementPort stock;
    private final AccountingEntryPort accounting;
    private final ReturnEventContextFactory returnContexts = new ReturnEventContextFactory();

    public BillingAnnulmentService(BillingDocumentRepositoryPort repository, BillingDocumentLoader loader,
            BillingOutboxPort outbox, BillingExecutionContextPort context, BillingClockPort clock,
            BillingIdGeneratorPort ids, StockMovementPort stock, AccountingEntryPort accounting) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.context = Objects.requireNonNull(context, "context");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.accounting = Objects.requireNonNull(accounting, "accounting");
    }

    @Override
    public BillingDocument annul(String enterpriseId, DocumentType type, UUID id, String reason, Long version) {
        BillingDocument document = loader.loadForUpdate(enterpriseId, type, id, null);
        if (document.status() == DocumentStatus.ANULADA) {
            return document; // repeticion: mismo resultado, sin puertos ni evento nuevos
        }
        document.ensureVersion(version);

        // Solo una factura EMITIDA puede tener devoluciones que bloqueen; en cualquier otro estado el
        // dominio rechaza con INVALID_STATE y se evita la consulta.
        boolean hasEmittedReturns = document.type().isInvoice()
                && document.status() == DocumentStatus.EMITIDA
                && repository.existsReturnInStatus(enterpriseId, document.id(), DocumentStatus.EMITIDA);

        Instant now = clock.now();
        document.annul(reason, hasEmittedReturns, now, context.username());

        BillingDocument saved = repository.save(document);
        stock.onAnnulled(saved);
        accounting.onAnnulled(saved);
        outbox.append(new BillingDocumentAnnulled(ids.newId(), now, saved,
                saved.type().isReturn() ? returnContext(enterpriseId, saved) : null));
        return saved;
    }

    /**
     * Contexto del evento de una devolucion anulada (A1, RET-10): su ordinal (conserva el que tenia)
     * y el estado de devolucion de la original sin esta devolucion. Se lee DESPUES de guardar para
     * que la cantidad liberada ya cuente; la original no se bloquea (anular solo libera cantidad, y
     * bloquearla invertiria el orden original -> devolucion usado al emitir).
     */
    private ReturnEventContext returnContext(String enterpriseId, BillingDocument annulledReturn) {
        UUID originalId = annulledReturn.original().id();
        BillingDocument original = repository.findById(enterpriseId, originalId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND,
                        "La factura original no existe"));
        return returnContexts.forReturn(annulledReturn, original, repository.findReturnsOf(enterpriseId, originalId));
    }
}
