package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.transaction;

import co.unicauca.edu.co.contables.billing.application.ports.output.BillingTransactionRunnerPort;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Ejecuta acciones en una transaccion nueva (REQUIRES_NEW). */
@Component("billingTransactionRunnerAdapter")
public class BillingTransactionRunnerAdapter implements BillingTransactionRunnerPort {

    private final TransactionTemplate transactions;

    public BillingTransactionRunnerAdapter(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void inNewTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
    }

    @Override
    public <T> T callInNewTransaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }
}
