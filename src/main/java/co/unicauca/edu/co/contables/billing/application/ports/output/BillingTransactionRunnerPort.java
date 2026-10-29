package co.unicauca.edu.co.contables.billing.application.ports.output;

import java.util.function.Supplier;

/** Ejecuta una accion en una transaccion nueva e independiente (REQUIRES_NEW). */
public interface BillingTransactionRunnerPort {

    void inNewTransaction(Runnable action);

    /** Igual que {@link #inNewTransaction(Runnable)} pero devuelve el resultado; si lanza, revierte. */
    <T> T callInNewTransaction(Supplier<T> action);
}
