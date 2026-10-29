package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence;

import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.context.BillingClockAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.context.BillingExecutionContextAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.messaging.BillingEventSerializer;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.messaging.BillingOutboxAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.entity.BillingDocumentEntity;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.transaction.BillingTransactionRunnerAdapter;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.mapper.BillingDocumentPersistenceMapper;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.repository.BillingDocumentJpaRepository;
import co.unicauca.edu.co.contables.commons.multitenancy.utils.TenantContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.util.ObjectUtils;

/**
 * Configuracion de prueba SOLO de billing para los {@code @DataJpaTest} (D18): evita que la busqueda
 * por defecto llegue a {@code ConfigurationApplication} y arrastre {@code WebConfiguration}, el
 * {@code data.sql} (el {@code ON CONFLICT} lo rechaza H2) y las entidades de todos los modulos.
 * Incluye un resolvedor de tenant basado en {@link TenantContext} con respaldo "BOOTSTRAP" (igual que
 * el de produccion, que es privado del paquete commons).
 */
@TestConfiguration
@EntityScan(basePackageClasses = BillingDocumentEntity.class)
@EnableJpaRepositories(basePackageClasses = BillingDocumentJpaRepository.class)
@Import({BillingDocumentPersistenceAdapter.class, BillingDocumentPersistenceMapper.class,
        BillingDocumentSequenceAdapter.class, BillingIdempotencyAdapter.class, BillingTransactionRunnerAdapter.class,
        BillingClockAdapter.class, BillingExecutionContextAdapter.class, BillingEventSerializer.class, BillingOutboxAdapter.class})
public class BillingJpaTestConfig {

    @Bean
    CurrentTenantIdentifierResolver<String> billingTestTenantIdentifierResolver() {
        return new CurrentTenantIdentifierResolver<String>() {
            @Override
            public String resolveCurrentTenantIdentifier() {
                String tenantId = TenantContext.getTenantId();
                return ObjectUtils.isEmpty(tenantId) ? "BOOTSTRAP" : tenantId;
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return true;
            }
        };
    }

    @Bean
    HibernatePropertiesCustomizer billingTestTenantCustomizer(CurrentTenantIdentifierResolver<String> resolver) {
        return properties -> properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
