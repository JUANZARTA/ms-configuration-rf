package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support;

import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import java.util.UUID;

/**
 * Header opcional {@code Idempotency-Key} de los POST de creacion. Debe ser un UUID; se normaliza a
 * su forma canonica en minusculas para que "A..." y "a..." sean la misma clave. Ausente = sin
 * idempotencia (comportamiento anterior). Presente pero invalido (incluido en blanco) =
 * 400 BILLING_VALIDATION_ERROR.
 */
public final class BillingIdempotencyHeader {

    public static final String NAME = "Idempotency-Key";

    private BillingIdempotencyHeader() {
    }

    /** @return la clave canonica, o {@code null} si el header no vino */
    public static String parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw new BillingException(BillingErrorCode.BILLING_VALIDATION_ERROR,
                    "El header " + NAME + " debe ser un UUID");
        }
    }
}
