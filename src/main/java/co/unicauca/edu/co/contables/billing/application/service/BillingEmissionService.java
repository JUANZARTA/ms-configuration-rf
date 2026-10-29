package co.unicauca.edu.co.contables.billing.application.service;

import co.unicauca.edu.co.contables.billing.application.ports.input.EmitDocumentUseCase;
import co.unicauca.edu.co.contables.billing.application.ports.output.AccountingEntryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingDocumentRepositoryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingExecutionContextPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdGeneratorPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingOutboxPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.DocumentNumberSequencePort;
import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.domain.event.BillingDocumentEmitted;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.CounterpartySnapshot;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentLine;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentNumber;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.domain.model.IssuerSnapshot;
import co.unicauca.edu.co.contables.billing.domain.model.ProductSnapshot;
import co.unicauca.edu.co.contables.billing.domain.service.ReturnAvailabilityCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Emision de facturas (EMT-1..4) y devoluciones (RET-3..5). Java plano: la transaccion la aporta
 * el adaptador de entrada.
 *
 * <p>Facturas (el numero se toma AL FINAL, despues de toda validacion, para no consumir
 * consecutivos ni sostener el bloqueo de la secuencia mas de lo necesario, EMT-3):
 * cargar -> tercero -> productos -> refrescar catalogo -> emisor -> precondiciones del dominio ->
 * secuencia -> emitir -> guardar -> outbox. Todo lo referenciado se relee y se exige activo; el
 * dominio no valida la actividad de los productos, por eso se verifica aqui.
 *
 * <p>Devoluciones: cargar -> bloquear la original -> estado y disponibilidad -> emisor ->
 * precondiciones -> secuencia -> emitir -> guardar -> outbox con {@code ReturnEventContext}.
 *
 * <p>Idempotente: el documento se carga CON bloqueo de fila, asi que dos emisiones simultaneas se
 * serializan; si ya esta EMITIDA se devuelve tal cual, sin numero, puertos ni evento nuevos. Los
 * puertos de stock y asientos se invocan despues de guardar y antes del outbox, DENTRO de la
 * transaccion: si alguno falla se revierte todo (estado, numero y evento).
 */
public class BillingEmissionService implements EmitDocumentUseCase {

    private final BillingDocumentRepositoryPort repository;
    private final BillingDocumentLoader loader;
    private final BillingReferenceResolver resolver;
    private final DocumentNumberSequencePort sequence;
    private final BillingOutboxPort outbox;
    private final BillingExecutionContextPort context;
    private final BillingClockPort clock;
    private final BillingIdGeneratorPort ids;
    private final StockMovementPort stock;
    private final AccountingEntryPort accounting;
    private final ReturnAvailabilityCalculator calculator = new ReturnAvailabilityCalculator();
    private final ReturnEventContextFactory returnContexts = new ReturnEventContextFactory(calculator);

    public BillingEmissionService(BillingDocumentRepositoryPort repository, BillingDocumentLoader loader,
            BillingReferenceResolver resolver, DocumentNumberSequencePort sequence, BillingOutboxPort outbox,
            BillingExecutionContextPort context, BillingClockPort clock, BillingIdGeneratorPort ids,
            StockMovementPort stock, AccountingEntryPort accounting) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.context = Objects.requireNonNull(context, "context");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.accounting = Objects.requireNonNull(accounting, "accounting");
    }

    @Override
    public BillingDocument emit(String enterpriseId, DocumentType type, UUID id, Long version) {
        Objects.requireNonNull(type, "type");
        BillingDocument document = loader.loadForUpdate(enterpriseId, type, id, null);
        if (document.status() == DocumentStatus.EMITIDA) {
            return document; // repeticion: mismo resultado, sin numero ni evento nuevos
        }
        document.ensureVersion(version);
        requireDraft(document);
        return type.isReturn() ? emitReturn(enterpriseId, type, document) : emitInvoice(enterpriseId, type, document);
    }

    /**
     * Devolucion (RET-3, RET-5, D12): la original se BLOQUEA primero (serializa contra otras
     * emisiones de devoluciones de la misma factura y contra su anulacion) y luego, bajo ese
     * bloqueo, se revalida que siga EMITIDA y que las cantidades quepan en lo disponible; solo
     * despues se toma la secuencia propia del tipo (NC- / DC-). El orden de bloqueo es siempre
     * original -> secuencia. El tercero es el de la original (no se relee) y no se consultan
     * productos: las lineas estan congeladas.
     */
    private BillingDocument emitReturn(String enterpriseId, DocumentType type, BillingDocument document) {
        UUID originalId = document.original().id();
        BillingDocument original = repository.findByIdForUpdate(enterpriseId, originalId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND,
                        "La factura original no existe"));
        original.ensureType(type.originType());
        if (original.status() != DocumentStatus.EMITIDA) {
            throw new BillingException(BillingErrorCode.BILLING_ORIGINAL_NOT_EMITTED,
                    "Solo se puede devolver contra una factura emitida (estado actual: " + original.status() + ")");
        }

        List<BillingDocument> returns = repository.findReturnsOf(enterpriseId, originalId);
        List<BillingDocument> others = new ArrayList<>(returns.size());
        for (BillingDocument other : returns) {
            if (!other.id().equals(document.id())) {
                others.add(other);
            }
        }
        calculator.assertWithinAvailability(original, others, document.lines());
        IssuerSnapshot issuer = readActiveIssuer(enterpriseId);
        document.assertEmittable();

        long numberValue = sequence.nextValue(enterpriseId, type);
        Instant now = clock.now();
        document.emit(DocumentNumber.of(type, numberValue), issuer, null, now, context.username());

        BillingDocument saved = repository.save(document);
        integrate(saved);
        outbox.append(new BillingDocumentEmitted(ids.newId(), now, saved,
                returnContexts.forReturn(saved, original, returns)));
        return saved;
    }

    private BillingDocument emitInvoice(String enterpriseId, DocumentType type, BillingDocument document) {
        CounterpartySnapshot freshCounterparty = readCounterparty(enterpriseId, document);
        Map<Long, ProductSnapshot> catalog = readActiveProducts(enterpriseId, document);
        document.refreshCatalog(catalog);
        IssuerSnapshot issuer = readActiveIssuer(enterpriseId);
        document.assertEmittable();

        long numberValue = sequence.nextValue(enterpriseId, type);
        Instant now = clock.now();
        document.emit(DocumentNumber.of(type, numberValue), issuer, freshCounterparty, now, context.username());

        BillingDocument saved = repository.save(document);
        integrate(saved);
        outbox.append(new BillingDocumentEmitted(ids.newId(), now, saved, null));
        return saved;
    }

    /** Integraciones sincronas de la misma transaccion; cualquier excepcion revierte la emision completa. */
    private void integrate(BillingDocument emitted) {
        stock.onEmitted(emitted);
        accounting.onEmitted(emitted);
    }

    // ---------- internos ----------

    /** Tercero recien leido (solo si el borrador ya tiene uno); inexistente o inactivo se rechaza. */
    private CounterpartySnapshot readCounterparty(String enterpriseId, BillingDocument document) {
        if (document.counterparty() == null) {
            return null; // assertEmittable lo reporta como DRAFT_INCOMPLETE
        }
        CounterpartySnapshot fresh = resolver.requireCounterparty(enterpriseId,
                document.counterparty().thirdId(), document.type().kind());
        if (!fresh.active()) {
            throw new BillingException(BillingErrorCode.BILLING_INACTIVE_REFERENCE, "El tercero esta inactivo");
        }
        return fresh;
    }

    private Map<Long, ProductSnapshot> readActiveProducts(String enterpriseId, BillingDocument document) {
        List<Long> productIds = new ArrayList<>(document.lines().size());
        for (DocumentLine line : document.lines()) {
            productIds.add(line.productId());
        }
        Map<Long, ProductSnapshot> catalog = resolver.requireProducts(enterpriseId, productIds,
                document.type().kind());
        for (ProductSnapshot product : catalog.values()) {
            if (!product.active()) {
                throw new BillingException(BillingErrorCode.BILLING_INACTIVE_REFERENCE,
                        "El producto " + product.productId() + " esta inactivo");
            }
        }
        return catalog;
    }

    private IssuerSnapshot readActiveIssuer(String enterpriseId) {
        IssuerSnapshot issuer = resolver.requireIssuer(enterpriseId);
        if (!issuer.active()) {
            throw new BillingException(BillingErrorCode.BILLING_INACTIVE_REFERENCE,
                    "La empresa emisora esta inactiva");
        }
        return issuer;
    }

    private static void requireDraft(BillingDocument document) {
        if (document.status() != DocumentStatus.BORRADOR) {
            throw new BillingException(BillingErrorCode.BILLING_INVALID_STATE,
                    "Solo se puede emitir un borrador (estado actual: " + document.status() + ")");
        }
    }

}
