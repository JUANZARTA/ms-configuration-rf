package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.integration;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.emittedSale;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.readySaleDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import co.unicauca.edu.co.contables.billing.application.ports.output.AccountingEntryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentStatus;
import org.junit.jupiter.api.Test;

/** INT-1: hoy los puertos de stock y asientos tienen adaptadores vacios que no alteran el documento ni fallan. */
class BillingNoopIntegrationAdaptersTest {

    @Test
    void INT_1_the_noop_stock_adapter_implements_the_port_and_does_nothing() {
        StockMovementPort port = new BillingNoopStockMovementAdapter();
        BillingDocument emitted = emittedSale();

        assertThatCode(() -> {
            port.onEmitted(emitted);
            port.onAnnulled(emitted);
        }).doesNotThrowAnyException();
        assertThat(emitted.status()).isEqualTo(DocumentStatus.EMITIDA);
    }

    @Test
    void INT_1_the_noop_accounting_adapter_implements_the_port_and_does_nothing() {
        AccountingEntryPort port = new BillingNoopAccountingEntryAdapter();
        BillingDocument draft = readySaleDraft();

        assertThatCode(() -> {
            port.onEmitted(draft);
            port.onAnnulled(draft);
        }).doesNotThrowAnyException();
        assertThat(draft.status()).isEqualTo(DocumentStatus.BORRADOR);
    }
}
