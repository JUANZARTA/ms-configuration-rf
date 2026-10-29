package co.unicauca.edu.co.contables.billing.infrastructure.adapters.output.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * Clave de idempotencia de creacion (header {@code Idempotency-Key}). Se escribe en la misma
 * transaccion que el documento. La unicidad (tenant, empresa, clave) es lo que hace seguro el
 * registro bajo concurrencia y debe ser explicita porque {@code ddl-auto=update} no corrige despues.
 * Las filas vencidas ({@code expires_at}) se ignoran al consultar y se reemplazan al reutilizar la
 * clave; el indice por vencimiento permite una limpieza posterior sin recorrer la tabla.
 */
@Entity(name = "BillingIdempotencyKeyEntity")
@Table(name = "billing_idempotency_keys",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_billing_idempotency",
                        columnNames = {"tenant_id", "enterprise_id", "idempotency_key"})
        },
        indexes = {
                @Index(name = "ix_billing_idempotency_expires", columnList = "expires_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class BillingIdempotencyKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 80)
    @Setter(AccessLevel.NONE)
    private String tenantId;

    @Column(name = "enterprise_id", nullable = false, length = 50)
    private String enterpriseId;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "document_type", nullable = false, length = 20)
    private String documentType;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
