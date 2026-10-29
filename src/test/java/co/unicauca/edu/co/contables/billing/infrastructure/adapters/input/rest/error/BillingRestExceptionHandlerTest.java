package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.unicauca.edu.co.contables.billing.domain.exception.BillingErrorCode;
import co.unicauca.edu.co.contables.billing.domain.exception.BillingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ERR-1 / ERR-2: traduccion de excepciones a HTTP con cuerpo BILLING_*. */
class BillingRestExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new BillingRestExceptionHandler())
                .build();
    }

    @Test
    void ERR_1_invalid_state_is_409_not_400() throws Exception {
        mockMvc.perform(get("/probe/state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_STATE"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/probe/state"));
    }

    @Test
    void IDM_2_idempotency_conflict_is_409_with_its_own_code() throws Exception {
        mockMvc.perform(get("/probe/idempotency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void ERR_1_not_found_is_404() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void ERR_1_unprocessable_is_422() throws Exception {
        mockMvc.perform(get("/probe/unprocessable"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BILLING_DRAFT_INCOMPLETE"));
    }

    @Test
    void ERR_1_invalid_line_is_400() throws Exception {
        mockMvc.perform(get("/probe/invalid-line"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_INVALID_LINE"));
    }

    @Test
    void ERR_1_optimistic_lock_is_409_concurrent_modification_never_500() throws Exception {
        mockMvc.perform(get("/probe/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_CONCURRENT_MODIFICATION"));
    }

    @Test
    void EMT_3_lock_failure_is_503_sequence_busy() throws Exception {
        mockMvc.perform(get("/probe/lock"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BILLING_SEQUENCE_BUSY"));
    }

    @Test
    void ERR_1_bean_validation_is_400_validation_error() throws Exception {
        mockMvc.perform(post("/probe/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));
    }

    @Test
    void ERR_1_malformed_body_is_400_validation_error() throws Exception {
        mockMvc.perform(post("/probe/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));
    }

    @Test
    void ERR_1_missing_request_param_is_400_validation_error() throws Exception {
        mockMvc.perform(get("/probe/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));
    }

    @Test
    void ERR_1_type_mismatch_is_400_validation_error() throws Exception {
        mockMvc.perform(get("/probe/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING_VALIDATION_ERROR"));
    }

    @Test
    void SEC_2_access_denied_is_403_forbidden() throws Exception {
        mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BILLING_FORBIDDEN"));
    }

    @Test
    void ERR_1_unexpected_exception_is_500_without_leaking_message() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("BILLING_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Error interno del servidor"));
    }

    /** Controlador de prueba dentro del paquete billing para que el advice le aplique. */
    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        public record Body(@NotBlank String name) {
        }

        @GetMapping("/state")
        public String state() {
            throw new BillingException(BillingErrorCode.BILLING_INVALID_STATE, "documento emitido");
        }

        @GetMapping("/idempotency")
        public String idempotency() {
            throw new BillingException(BillingErrorCode.BILLING_IDEMPOTENCY_CONFLICT, "otro contenido");
        }

        @GetMapping("/not-found")
        public String notFound() {
            throw new BillingException(BillingErrorCode.BILLING_DOCUMENT_NOT_FOUND, "no existe");
        }

        @GetMapping("/unprocessable")
        public String unprocessable() {
            throw new BillingException(BillingErrorCode.BILLING_DRAFT_INCOMPLETE, "sin lineas");
        }

        @GetMapping("/invalid-line")
        public String invalidLine() {
            throw new BillingException(BillingErrorCode.BILLING_INVALID_LINE, "cantidad invalida");
        }

        @GetMapping("/optimistic")
        public String optimistic() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id");
        }

        @GetMapping("/lock")
        public String lock() {
            throw new CannotAcquireLockException("lock timeout");
        }

        @PostMapping("/validation")
        public String validation(@Valid @RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/param")
        public String param(@RequestParam String enterpriseId) {
            return enterpriseId;
        }

        @GetMapping("/number/{n}")
        public String number(@PathVariable Long n) {
            return String.valueOf(n);
        }

        @GetMapping("/denied")
        public String denied() {
            throw new AccessDeniedException("sin permiso");
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("secreto interno");
        }
    }
}
