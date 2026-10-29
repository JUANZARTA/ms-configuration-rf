package co.unicauca.edu.co.contables.billing.application.service;

import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceLineCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnLineCommand;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Huella SHA-256 del cuerpo de una creacion, para decidir si dos peticiones con la misma clave de
 * idempotencia son "la misma". La forma canonica: tipo de documento, campos en orden fijo con su
 * longitud (no se pueden falsificar fronteras moviendo texto entre campos), decimales sin ceros
 * sobrantes ({@code 2} = {@code 2.00}), lineas nulas = lineas vacias y SIN la version esperada
 * (no forma parte del contenido de una creacion).
 */
public final class BillingRequestFingerprint {

    private BillingRequestFingerprint() {
    }

    public static String of(DocumentType type, InvoiceDraftCommand command) {
        StringBuilder canonical = new StringBuilder("invoice;");
        field(canonical, type == null ? null : type.name());
        field(canonical, command.counterpartyId());
        field(canonical, command.issueDate());
        field(canonical, command.supplierInvoiceNumber());
        List<InvoiceLineCommand> lines = command.lines() == null ? List.of() : command.lines();
        field(canonical, lines.size());
        for (InvoiceLineCommand line : lines) {
            field(canonical, line.productId());
            field(canonical, line.quantity());
            field(canonical, line.unitPriceOverride());
            field(canonical, line.taxRateOverride());
        }
        return sha256(canonical.toString());
    }

    public static String of(DocumentType type, ReturnDraftCommand command) {
        StringBuilder canonical = new StringBuilder("return;");
        field(canonical, type == null ? null : type.name());
        field(canonical, command.originalDocumentId());
        field(canonical, command.issueDate());
        field(canonical, command.reason());
        List<ReturnLineCommand> lines = command.lines() == null ? List.of() : command.lines();
        field(canonical, lines.size());
        for (ReturnLineCommand line : lines) {
            field(canonical, line.sourceLineNo());
            field(canonical, line.quantity());
        }
        return sha256(canonical.toString());
    }

    private static void field(StringBuilder canonical, Object value) {
        if (value == null) {
            canonical.append("-;");
            return;
        }
        String text = value instanceof BigDecimal decimal
                ? decimal.stripTrailingZeros().toPlainString()
                : value.toString();
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
