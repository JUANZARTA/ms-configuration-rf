package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence;

import co.unicauca.edu.co.contables.billing.application.ports.output.BillingIdempotencyPort;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyKeyCollisionException;
import co.unicauca.edu.co.contables.billing.application.ports.output.model.IdempotencyRecord;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.entity.BillingIdempotencyKeyEntity;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.repository.BillingIdempotencyKeyJpaRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Persistencia JPA de claves de idempotencia (puerto {@link BillingIdempotencyPort}). Debe
 * ejecutarse dentro de una transaccion del llamador. El registro NO consulta antes de insertar: la
 * restriccion unica (tenant, empresa, clave) decide la carrera. Si otra transaccion inserto la misma
 * clave sin confirmar, la base espera a que confirme o revierta y, si confirmo, esta transaccion
 * recibe la violacion, que se traduce a {@link IdempotencyKeyCollisionException}.
 */
@Component("billingIdempotencyAdapter")
public class BillingIdempotencyAdapter implements BillingIdempotencyPort {

    private final BillingIdempotencyKeyJpaRepository repository;

    public BillingIdempotencyAdapter(BillingIdempotencyKeyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<IdempotencyRecord> find(String enterpriseId, String key, Instant now) {
        return repository.findByEnterpriseIdAndIdempotencyKeyAndExpiresAtAfter(enterpriseId, key, now)
                .map(BillingIdempotencyAdapter::toRecord);
    }

    @Override
    public void register(IdempotencyRecord record) {
        repository.deleteExpired(record.enterpriseId(), record.key(), record.createdAt());
        BillingIdempotencyKeyEntity entity = new BillingIdempotencyKeyEntity();
        entity.setEnterpriseId(record.enterpriseId());
        entity.setIdempotencyKey(record.key());
        entity.setRequestHash(record.requestHash());
        entity.setDocumentType(record.documentType().name());
        entity.setDocumentId(record.documentId());
        entity.setCreatedAt(record.createdAt());
        entity.setExpiresAt(record.expiresAt());
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new IdempotencyKeyCollisionException(
                    "La clave de idempotencia ya esta registrada para la empresa " + record.enterpriseId(), ex);
        }
    }

    private static IdempotencyRecord toRecord(BillingIdempotencyKeyEntity entity) {
        return new IdempotencyRecord(entity.getEnterpriseId(), entity.getIdempotencyKey(), entity.getRequestHash(),
                DocumentType.valueOf(entity.getDocumentType()), entity.getDocumentId(), entity.getCreatedAt(),
                entity.getExpiresAt());
    }
}
