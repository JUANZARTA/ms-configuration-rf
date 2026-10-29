package co.unicauca.edu.co.contables.billing.application.ports.output;

import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;

/**
 * Asientos contables que provocan la emision y la anulacion de un documento. Mismo criterio que
 * {@link StockMovementPort}: se invoca DENTRO de la transaccion de emitir/anular y una excepcion
 * revierte todo; el adaptador real llamara a los puertos de ENTRADA del modulo de Contabilidad,
 * nunca a sus repositorios. Hoy el adaptador es un no-op; las cuentas y asientos por documento se
 * definen con Contabilidad.
 */
public interface AccountingEntryPort {

    /** Asiento por la emision de {@code document} (ya EMITIDA, con numero). */
    void onEmitted(BillingDocument document);

    /** Asiento de reversa por la anulacion de {@code document} (ya ANULADA, conserva su numero). */
    void onAnnulled(BillingDocument document);
}
