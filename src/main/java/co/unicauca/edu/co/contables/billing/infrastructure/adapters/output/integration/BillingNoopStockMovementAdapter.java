package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.integration;

import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import org.springframework.stereotype.Component;

/**
 * Adaptador vacio de stock/kardex: hoy Facturacion no mueve inventario. Existe para que la
 * transaccion de emitir/anular ya llame al puerto; cuando Inventario defina su contrato, solo se
 * reemplaza este adaptador por uno que invoque los puertos de ENTRADA del modulo de Inventario
 * (nunca sus repositorios) dentro de la misma transaccion.
 */
@Component("billingNoopStockMovementAdapter")
public class BillingNoopStockMovementAdapter implements StockMovementPort {

    @Override
    public void onEmitted(BillingDocument document) {
        // sin movimiento de inventario por ahora
    }

    @Override
    public void onAnnulled(BillingDocument document) {
        // sin reversa de inventario por ahora
    }
}
