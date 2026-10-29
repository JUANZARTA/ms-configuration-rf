package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.repository;

import co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.entity.BillingIdempotencyKeyEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositorio de claves de idempotencia; pasa por el filtro {@code @TenantId} (solo ve su tenant). */
public interface BillingIdempotencyKeyJpaRepository extends JpaRepository<BillingIdempotencyKeyEntity, Long> {

    Optional<BillingIdempotencyKeyEntity> findByEnterpriseIdAndIdempotencyKeyAndExpiresAtAfter(
            String enterpriseId, String idempotencyKey, Instant now);

    /**
     * Borra la clave SOLO si ya vencio (para poder reutilizarla). Es un DELETE masivo: si dos
     * transacciones lo ejecutan a la vez no hay "fila ya borrada" que dispare un error de version.
     */
    @Modifying
    @Query("delete from BillingIdempotencyKeyEntity k"
            + " where k.enterpriseId = :enterpriseId and k.idempotencyKey = :key and k.expiresAt <= :now")
    int deleteExpired(@Param("enterpriseId") String enterpriseId, @Param("key") String key,
            @Param("now") Instant now);
}
