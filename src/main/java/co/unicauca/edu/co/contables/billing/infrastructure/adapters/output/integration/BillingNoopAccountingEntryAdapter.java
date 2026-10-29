package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.integration;

import co.unicauca.edu.co.contables.billing.application.ports.output.AccountingEntryPort;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import org.springframework.stereotype.Component;

/**
 * Adaptador vacio de asientos contables: hoy Facturacion no genera asientos. Cuando Contabilidad
 * defina cuentas y asientos por documento, solo se reemplaza este adaptador por uno que invoque los
 * puertos de ENTRADA del modulo de Contabilidad (nunca sus repositorios) dentro de la misma
 * transaccion.
 */
@Component("billingNoopAccountingEntryAdapter")
public class BillingNoopAccountingEntryAdapter implements AccountingEntryPort {

    @Override
    public void onEmitted(BillingDocument document) {
        // sin asiento contable por ahora
    }

    @Override
    public void onAnnulled(BillingDocument document) {
        // sin asiento de reversa por ahora
    }
}
