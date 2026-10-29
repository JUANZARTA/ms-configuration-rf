package co.unicauca.edu.co.contables.billing.application.ports.output;

import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;

/**
 * Movimientos de inventario (stock/kardex) que provocan la emision y la anulacion de un documento.
 * Se invoca DENTRO de la transaccion de emitir/anular, despues de guardar el documento y antes de
 * agregar el evento al outbox: si lanza una excepcion, la transaccion completa se revierte (el
 * documento conserva su estado, no se consume numero ni queda evento). El adaptador real llamara a
 * los puertos de ENTRADA del modulo de Inventario, nunca a sus repositorios. Hoy el adaptador es
 * un no-op; el contrato de movimientos por tipo de documento se cierra con Inventario.
 */
public interface StockMovementPort {

    /** Movimiento por la emision de {@code document} (ya EMITIDA, con numero). */
    void onEmitted(BillingDocument document);

    /** Reversa por la anulacion de {@code document} (ya ANULADA, conserva su numero). */
    void onAnnulled(BillingDocument document);
}
