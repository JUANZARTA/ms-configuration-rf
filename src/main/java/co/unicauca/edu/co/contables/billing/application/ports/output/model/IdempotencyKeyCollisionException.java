package co.unicauca.edu.co.contables.billing.application.ports.output.model;

/**
 * La clave de idempotencia ya esta registrada y vigente (otra peticion con la misma clave gano la
 * carrera). Quien la recibe debe haber revertido su transaccion y releer el resultado ganador.
 */
public class IdempotencyKeyCollisionException extends RuntimeException {

    public IdempotencyKeyCollisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
