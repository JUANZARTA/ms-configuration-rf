package co.unicauca.edu.co.contables.billing.application.service;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.InvoiceLineCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnDraftCommand;
import co.unicauca.edu.co.contables.billing.application.ports.input.command.ReturnLineCommand;
import co.unicauca.edu.co.contables.billing.domain.model.DocumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** IDM-4: la huella del cuerpo es canonica (mismo contenido, misma huella) y sensible a cualquier cambio real. */
class BillingRequestFingerprintTest {

    private static final DocumentType SALE = DocumentType.SALE_INVOICE;

    private static InvoiceDraftCommand invoice(Long thirdId, String quantity, Long expectedVersion) {
        return new InvoiceDraftCommand(thirdId, LocalDate.of(2026, 1, 15), null,
                List.of(new InvoiceLineCommand(1L, new BigDecimal(quantity), null, null)), expectedVersion);
    }

    @Test
    void IDM_4_the_fingerprint_is_a_sha256_hex_string() {
        String hash = BillingRequestFingerprint.of(SALE, invoice(10L, "2", null));

        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void IDM_4_the_same_content_gives_the_same_fingerprint() {
        assertThat(BillingRequestFingerprint.of(SALE, invoice(10L, "2", null)))
                .isEqualTo(BillingRequestFingerprint.of(SALE, invoice(10L, "2", null)));
    }

    @Test
    void IDM_4_numerically_equal_decimals_are_canonicalised() {
        assertThat(BillingRequestFingerprint.of(SALE, invoice(10L, "2", null)))
                .isEqualTo(BillingRequestFingerprint.of(SALE, invoice(10L, "2.00", null)));
    }

    @Test
    void IDM_4_the_expected_version_is_not_part_of_the_fingerprint() {
        assertThat(BillingRequestFingerprint.of(SALE, invoice(10L, "2", 3L)))
                .isEqualTo(BillingRequestFingerprint.of(SALE, invoice(10L, "2", null)));
    }

    @Test
    void IDM_4_null_lines_and_empty_lines_are_the_same_body() {
        InvoiceDraftCommand withNull = new InvoiceDraftCommand(10L, null, null, null, null);
        InvoiceDraftCommand withEmpty = new InvoiceDraftCommand(10L, null, null, List.of(), null);

        assertThat(BillingRequestFingerprint.of(SALE, withNull))
                .isEqualTo(BillingRequestFingerprint.of(SALE, withEmpty));
    }

    @Test
    void IDM_4_a_different_quantity_third_or_type_changes_the_fingerprint() {
        String base = BillingRequestFingerprint.of(SALE, invoice(10L, "2", null));

        assertThat(BillingRequestFingerprint.of(SALE, invoice(10L, "3", null))).isNotEqualTo(base);
        assertThat(BillingRequestFingerprint.of(SALE, invoice(11L, "2", null))).isNotEqualTo(base);
        assertThat(BillingRequestFingerprint.of(DocumentType.PURCHASE_INVOICE, invoice(10L, "2", null)))
                .isNotEqualTo(base);
    }

    @Test
    void IDM_4_the_order_of_the_lines_matters() {
        InvoiceLineCommand a = new InvoiceLineCommand(1L, BigDecimal.ONE, null, null);
        InvoiceLineCommand b = new InvoiceLineCommand(2L, BigDecimal.ONE, null, null);

        assertThat(BillingRequestFingerprint.of(SALE, new InvoiceDraftCommand(10L, null, null, List.of(a, b), null)))
                .isNotEqualTo(BillingRequestFingerprint.of(SALE,
                        new InvoiceDraftCommand(10L, null, null, List.of(b, a), null)));
    }

    @Test
    void IDM_4_field_boundaries_cannot_be_forged_by_moving_text_between_fields() {
        InvoiceDraftCommand one = new InvoiceDraftCommand(10L, null, "A|B", List.of(), null);
        InvoiceDraftCommand two = new InvoiceDraftCommand(10L, null, "A", List.of(), null);

        assertThat(BillingRequestFingerprint.of(SALE, one)).isNotEqualTo(BillingRequestFingerprint.of(SALE, two));
    }

    @Test
    void IDM_4_return_fingerprints_follow_the_same_rules() {
        ReturnDraftCommand base = new ReturnDraftCommand(uuid(1), null, "defectuoso",
                List.of(new ReturnLineCommand(1, new BigDecimal("2"))), null);
        ReturnDraftCommand same = new ReturnDraftCommand(uuid(1), null, "defectuoso",
                List.of(new ReturnLineCommand(1, new BigDecimal("2.0"))), 9L);
        ReturnDraftCommand other = new ReturnDraftCommand(uuid(1), null, "defectuoso",
                List.of(new ReturnLineCommand(1, new BigDecimal("3"))), null);

        DocumentType type = DocumentType.SALE_RETURN;
        assertThat(BillingRequestFingerprint.of(type, base)).isEqualTo(BillingRequestFingerprint.of(type, same));
        assertThat(BillingRequestFingerprint.of(type, base)).isNotEqualTo(BillingRequestFingerprint.of(type, other));
        assertThat(BillingRequestFingerprint.of(type, base))
                .isNotEqualTo(BillingRequestFingerprint.of(DocumentType.PURCHASE_RETURN, base));
    }
}
