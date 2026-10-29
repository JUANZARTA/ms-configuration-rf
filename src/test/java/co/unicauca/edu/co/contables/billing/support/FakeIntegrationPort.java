package co.unicauca.edu.co.contables.billing.support;

import co.unicauca.edu.co.contables.billing.application.ports.output.AccountingEntryPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.StockMovementPort;
import co.unicauca.edu.co.contables.billing.domain.model.BillingDocument;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puerto de integracion de prueba (sirve como {@link StockMovementPort} y como
 * {@link AccountingEntryPort}): cuenta las invocaciones y puede configurarse para fallar, y asi
 * probar que un puerto que falla revierte toda la transaccion de emitir/anular.
 */
public class FakeIntegrationPort implements StockMovementPort, AccountingEntryPort {

    private final AtomicInteger emitted = new AtomicInteger();
    private final AtomicInteger annulled = new AtomicInteger();
    private volatile RuntimeException failure;

    /** A partir de ahora toda invocacion lanza {@code failure}. */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void recover() {
        this.failure = null;
    }

    public int emittedCalls() {
        return emitted.get();
    }

    public int annulledCalls() {
        return annulled.get();
    }

    @Override
    public void onEmitted(BillingDocument document) {
        emitted.incrementAndGet();
        throwIfFailing();
    }

    @Override
    public void onAnnulled(BillingDocument document) {
        annulled.incrementAndGet();
        throwIfFailing();
    }

    private void throwIfFailing() {
        RuntimeException current = failure;
        if (current != null) {
            throw current;
        }
    }
}
