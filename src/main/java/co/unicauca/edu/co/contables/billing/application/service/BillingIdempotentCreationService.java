package co.unicauca.edu.co.contables.billing.application.service;

import co.unicauca.edu.co.contables.billing.application.ports.output.BillingClockPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingDocumentRepositoryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdempotencyPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.BillingTransactionRunnerPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyKeyCollisionException;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyRecord;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Creacion idempotente (header {@code Idempotency-Key}). Java plano y SIN transaccion propia: abre
 * las suyas con {@link BillingTransactionRunnerPort} porque necesita reintentar la lectura despues
 * de que una transaccion se revierte por colision de clave (en PostgreSQL una violacion de unicidad
 * deja la transaccion inutilizable, no se puede "capturar y seguir" dentro de ella).
 *
 * <p>Flujo: (1) consulta de la clave vigente; si existe, mismo cuerpo = se devuelve el documento
 * original, cuerpo distinto = {@code BILLING_IDEMPOTENCY_CONFLICT}. (2) Si no existe, UNA transaccion
 * crea el documento Y registra la clave (o ambos o ninguno). (3) Si otra peticion registro la clave
 * primero, la unicidad de la base de datos lo detecta: esta transaccion se revierte (su documento
 * desaparece) y se relee en una transaccion nueva al ganador, devolviendo SU documento. Nunca hay dos
 * documentos para una misma clave.
 */
public class BillingIdempotentCreationService {

    /** Vigencia de una clave: pasado este tiempo se ignora y puede reutilizarse. */
    public static final Duration TTL = Duration.ofHours(24);

    private final BillingIdempotencyPort idempotency;
    private final BillingDocumentRepositoryPort repository;
    private final BillingTransactionRunnerPort transactions;
    private final BillingClockPort clock;

    public BillingIdempotentCreationService(BillingIdempotencyPort idempotency,
            BillingDocumentRepositoryPort repository, BillingTransactionRunnerPort transactions,
            BillingClockPort clock) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param creator crea y persiste el documento (se ejecuta dentro de la transaccion que tambien
     *                registra la clave)
     */
    public BillingDocument create(String enterpriseId, DocumentType type, String key, String requestHash,
            Supplier<BillingDocument> creator) {
        Objects.requireNonNull(creator, "creator");
        Optional<BillingDocument> replay = transactions.callInNewTransaction(
                () -> replay(enterpriseId, type, key, requestHash));
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transactions.callInNewTransaction(() -> {
                BillingDocument document = creator.get();
                Instant now = clock.now();
                idempotency.register(new IdempotencyRecord(enterpriseId, key, requestHash, type, document.id(), now,
                        now.plus(TTL)));
                return document;
            });
        } catch (IdempotencyKeyCollisionException collision) {
            return transactions.callInNewTransaction(() -> replay(enterpriseId, type, key, requestHash))
                    .orElseThrow(() -> new BillingException(BillingErrorCode.BILLING_CONCURRENT_MODIFICATION,
                            "La clave de idempotencia cambio durante la creacion, reintente"));
        }
    }

    /** Documento original si la clave vigente existe y es "la misma" peticion; vacio si la clave es nueva. */
    private Optional<BillingDocument> replay(String enterpriseId, DocumentType type, String key,
            String requestHash) {
        Optional<IdempotencyRecord> existing = idempotency.find(enterpriseId, key, clock.now());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = existing.get();
        if (!record.requestHash().equals(requestHash) || record.documentType() != type) {
            throw new BillingException(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT,
                    "La clave de idempotencia ya se uso con un contenido distinto");
        }
        BillingDocument document = repository.findById(enterpriseId, record.documentId())
                .orElseThrow(() -> new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND,
                        "El documento creado con esta clave de idempotencia ya no existe"));
        return Optional.of(document);
    }
}
