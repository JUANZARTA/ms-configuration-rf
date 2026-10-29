package co.unicauca.edu.co.contables.billing.application.ports.output;

import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyKeyCollisionException;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;

/**
 * Registro de claves de idempotencia de creacion (header {@code Idempotency-Key}). La unicidad es
 * por (tenant, empresa, clave) y la garantiza la base de datos, no una lectura previa: es lo que
 * hace seguro el registro bajo concurrencia.
 */
public interface BillingIdempotencyPort {

    /** Registro VIGENTE de la clave (los vencidos se ignoran), si existe. */
    Optional<IdempotencyRecord> find(String enterpriseId, String key, Instant now);

    /**
     * Registra la clave en la transaccion actual (junto con el documento). Si existe un registro
     * vencido con esa clave lo reemplaza. Si hay uno vigente, o una transaccion concurrente la
     * registra primero, lanza {@link IdempotencyKeyCollisionException}.
     */
    void register(IdempotencyRecord record);
}
