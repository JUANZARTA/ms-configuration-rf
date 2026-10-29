package co.unicauca.edu.co.contables.billing.domain.exception;

/**
 * Codigos de error del modulo billing. Cada codigo declara su categoria; la capa REST
 * traduce categoria a HTTP, de modo que el dominio no conoce HTTP ni Spring.
 */
public enum BillingErrorCode {

    // NOT_FOUND
    BILLING_DOCUMENT_NOT_FOUND(ErrorCategory.NOT_FOUND),

    // CONFLICT
    BILLING_INVALID_STATE(ErrorCategory.CONFLICT),
    BILLING_CONCURRENT_MODIFICATION(ErrorCategory.CONFLICT),
    BILLING_HAS_EMITTED_RETURNS(ErrorCategory.CONFLICT),
    BILLING_ORIGINAL_NOT_EMITTED(ErrorCategory.CONFLICT),
    BILLING_IDEMPOTENCY_CONFLICT(ErrorCategory.CONFLICT),

    // UNPROCESSABLE
    BILLING_DRAFT_INCOMPLETE(ErrorCategory.UNPROCESSABLE),
    BILLING_COUNTERPARTY_NOT_FOUND(ErrorCategory.UNPROCESSABLE),
    BILLING_PRODUCT_NOT_FOUND(ErrorCategory.UNPROCESSABLE),
    BILLING_ENTERPRISE_NOT_FOUND(ErrorCategory.UNPROCESSABLE),
    BILLING_INACTIVE_REFERENCE(ErrorCategory.UNPROCESSABLE),
    BILLING_RETURN_QUANTITY_EXCEEDED(ErrorCategory.UNPROCESSABLE),
    BILLING_RETURN_LINE_NOT_FOUND(ErrorCategory.UNPROCESSABLE),

    // BAD_REQUEST
    BILLING_INVALID_LINE(ErrorCategory.BAD_REQUEST),
    BILLING_ANNUL_REASON_REQUIRED(ErrorCategory.BAD_REQUEST),
    BILLING_VALIDATION_ERROR(ErrorCategory.BAD_REQUEST),

    // FORBIDDEN / UNAVAILABLE / INTERNAL
    BILLING_FORBIDDEN(ErrorCategory.FORBIDDEN),
    BILLING_SEQUENCE_BUSY(ErrorCategory.UNAVAILABLE),
    BILLING_INTERNAL_ERROR(ErrorCategory.INTERNAL);

    /** Categoria semantica del error (independiente de HTTP). */
    public enum ErrorCategory {
        NOT_FOUND,
        CONFLICT,
        UNPROCESSABLE,
        BAD_REQUEST,
        FORBIDDEN,
        UNAVAILABLE,
        INTERNAL
    }

    private final ErrorCategory category;

    BillingErrorCode(ErrorCategory category) {
        this.category = category;
    }

    public ErrorCategory category() {
        return category;
    }

    /** Codigo textual expuesto en el cuerpo de error. */
    public String code() {
        return name();
    }
}
