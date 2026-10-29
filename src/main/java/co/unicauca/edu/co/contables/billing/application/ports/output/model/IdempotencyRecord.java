package co.unicauca.edu.co.contables.billing.application.ports.output.model;

import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.time.Instant;
import java.util.UUID;

/**
 * Clave de idempotencia registrada al crear un documento: quien la uso (empresa + clave), la huella
 * del cuerpo con que se creo, el documento resultante y su vigencia. Una clave vencida
 * ({@code expiresAt} <= ahora) se ignora y puede reutilizarse.
 */
public record IdempotencyRecord(
        String enterpriseId,
        String key,
        String requestHash,
        DocumentType documentType,
        UUID documentId,
        Instant createdAt,
        Instant expiresAt) {
}
