package co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest;

import static co.unicauca.edu.co.contables.billing.support.BillingTestData.ENTERPRISE_ID;
import static co.unicauca.edu.co.contables.billing.support.BillingTestData.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingPurchaseInvoiceController;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingPurchaseReturnController;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingSaleInvoiceController;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.controller.BillingSaleReturnController;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingInvoiceEndpointSupport;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingPermissions;
import co.unicauca.edu.co.contables.billing.infrastructure.adapters.input.rest.support.BillingReturnEndpointSupport;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Seguridad de billing (H9, SEC-1..3) con los permisos ABIERTOS vigentes: hoy toda constante de
 * {@link BillingPermissions} es {@code isAuthenticated()}, asi que lo unico que separa a un usuario
 * de un endpoint es estar autenticado. Verifica (1) por reflexion que ningun handler usa
 * {@code hasAuthority}/{@code permitAll}/{@code anonymous} y que cualquier {@code @PreAuthorize}
 * presente es exactamente {@code isAuthenticated()}, (2) que sin credenciales cada endpoint responde
 * 401 y el caso de uso nunca se invoca, y (3) que un usuario autenticado, tenga o no authorities,
 * pasa la autorizacion de TODOS los endpoints (ya no existe el 403 por permiso faltante; volvera a
 * probarse por recurso/alcance cuando se definan permisos granulares en Keycloak). Los soportes de
 * endpoints se mockean: aqui solo se prueba la capa de seguridad. Los cuerpos son validos para que
 * la validacion (400) no oculte la autorizacion.
 */
@WebMvcTest(controllers = {BillingSaleInvoiceController.class, BillingPurchaseInvoiceController.class,
        BillingSaleReturnController.class, BillingPurchaseReturnController.class})
@ContextConfiguration(classes = {BillingSaleInvoiceController.class, BillingPurchaseInvoiceController.class,
        BillingSaleReturnController.class, BillingPurchaseReturnController.class,
        BillingWebMvcTestConfig.class})
@ActiveProfiles("test")
class BillingSecurityWebMvcTest {

    private static final String AUTHENTICATED = "isAuthenticated()";
    private static final List<Class<?>> CONTROLLERS = List.of(BillingSaleInvoiceController.class,
            BillingPurchaseInvoiceController.class, BillingSaleReturnController.class,
            BillingPurchaseReturnController.class);

    private static final String INVOICE_BODY = """
            {"lines":[{"productId":"1","cantidad":1}]}
            """;
    private static final String RETURN_BODY = """
            {"invoiceId":"%s","date":"2026-01-20","reason":"defectuoso",
             "lines":[{"sourceLineNo":1,"cantidad":1}]}
            """.formatted(uuid(1));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingInvoiceEndpointSupport invoiceSupport;
    @MockitoBean
    private BillingReturnEndpointSupport returnSupport;

    /** Un endpoint del modulo. */
    record Endpoint(HttpMethod method, String path, String body) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    // ---------- catalogo de endpoints ----------

    private static List<Endpoint> endpoints() {
        List<Endpoint> all = new ArrayList<>();
        all.addAll(invoiceEndpoints(BillingSaleInvoiceController.BASE_PATH));
        all.addAll(invoiceEndpoints(BillingPurchaseInvoiceController.BASE_PATH));
        all.addAll(returnEndpoints(BillingSaleReturnController.BASE_PATH));
        all.addAll(returnEndpoints(BillingPurchaseReturnController.BASE_PATH));
        return all;
    }

    private static List<Endpoint> invoiceEndpoints(String base) {
        String id = "/" + uuid(2);
        return List.of(
                new Endpoint(HttpMethod.POST, base, INVOICE_BODY),
                new Endpoint(HttpMethod.PUT, base + id, INVOICE_BODY),
                new Endpoint(HttpMethod.DELETE, base + id, null),
                new Endpoint(HttpMethod.POST, base + id + "/duplicate", null),
                new Endpoint(HttpMethod.POST, base + id + "/emit", null),
                new Endpoint(HttpMethod.POST, base + id + "/annul", null),
                new Endpoint(HttpMethod.GET, base + id, null),
                new Endpoint(HttpMethod.GET, base, null),
                // sub-recursos de devoluciones de la factura
                new Endpoint(HttpMethod.GET, base + id + "/return-availability", null),
                new Endpoint(HttpMethod.GET, base + id + "/returns", null),
                new Endpoint(HttpMethod.GET, base + "/with-returns", null));
    }

    private static List<Endpoint> returnEndpoints(String base) {
        String id = "/" + uuid(2);
        return List.of(
                new Endpoint(HttpMethod.POST, base, RETURN_BODY),
                new Endpoint(HttpMethod.PUT, base + id, RETURN_BODY),
                new Endpoint(HttpMethod.DELETE, base + id, null),
                new Endpoint(HttpMethod.POST, base + id + "/emit", null),
                new Endpoint(HttpMethod.POST, base + id + "/annul", null),
                new Endpoint(HttpMethod.GET, base + id, null),
                new Endpoint(HttpMethod.GET, base, null));
    }

    static Stream<Arguments> endpointArguments() {
        return endpoints().stream().map(Arguments::of);
    }

    private MockHttpServletRequestBuilder build(Endpoint endpoint) {
        MockHttpServletRequestBuilder builder = request(endpoint.method(), endpoint.path())
                .param("enterpriseId", ENTERPRISE_ID);
        if (endpoint.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
        }
        return builder;
    }

    // ---------- SEC-1: reflexion, nada publico ni con permisos finos ----------

    private static List<Method> handlers(Class<?> controller) {
        return java.util.Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
    }

    @Test
    void SEC_1_no_handler_is_public_and_any_pre_authorize_present_is_exactly_is_authenticated() {
        for (Class<?> controller : CONTROLLERS) {
            List<Method> handlers = handlers(controller);
            assertThat(handlers).as("handlers of %s", controller.getSimpleName()).isNotEmpty();
            for (Method handler : handlers) {
                PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
                if (annotation == null) {
                    continue; // lo protege la cadena de filtros: anyRequest().authenticated()
                }
                assertThat(annotation.value())
                        .as("expression on %s.%s", controller.getSimpleName(), handler.getName())
                        .isEqualTo(AUTHENTICATED)
                        .doesNotContain("permitAll", "isAnonymous", "hasAuthority");
            }
        }
    }

    @Test
    void SEC_1_handler_counts_match_the_published_api_surface() {
        assertThat(handlers(BillingSaleInvoiceController.class)).hasSize(11);
        assertThat(handlers(BillingPurchaseInvoiceController.class)).hasSize(11);
        assertThat(handlers(BillingSaleReturnController.class)).hasSize(7);
        assertThat(handlers(BillingPurchaseReturnController.class)).hasSize(7);
    }

    @Test
    void SEC_2_every_permission_constant_is_currently_is_authenticated() throws IllegalAccessException {
        int constants = 0;
        for (java.lang.reflect.Field field : BillingPermissions.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && field.getType() == String.class) {
                assertThat((String) field.get(null)).as(field.getName()).isEqualTo(AUTHENTICATED);
                constants++;
            }
        }
        assertThat(constants).as("la matriz de 4 recursos x 6 alcances sigue declarada").isEqualTo(24);
    }

    // ---------- SEC-1: sin credenciales -> 401 ----------

    @ParameterizedTest(name = "SEC_1 anonymous {0} -> 401")
    @MethodSource("endpointArguments")
    void SEC_1_anonymous_request_gets_401_and_never_reaches_the_use_cases(Endpoint endpoint) throws Exception {
        mockMvc.perform(build(endpoint)).andExpect(status().isUnauthorized());

        verifyNoInteractions(invoiceSupport, returnSupport);
    }

    @Test
    void SEC_1_unknown_billing_path_is_not_public_either() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/billing/unknown")).andExpect(status().isUnauthorized());
    }

    // ---------- SEC-2/3: autenticado -> pasa, con o sin authorities (permisos abiertos) ----------

    @ParameterizedTest(name = "SEC_2 authenticated without authorities {0} -> authorized")
    @MethodSource("endpointArguments")
    void SEC_2_authenticated_user_without_authorities_is_not_forbidden(Endpoint endpoint) throws Exception {
        mockMvc.perform(build(endpoint).with(user("tester").authorities(AuthorityUtils.NO_AUTHORITIES)))
                .andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest(name = "SEC_3 authenticated with unrelated authorities {0} -> authorized")
    @MethodSource("endpointArguments")
    void SEC_3_authorities_do_not_matter_while_permissions_are_open(Endpoint endpoint) throws Exception {
        mockMvc.perform(build(endpoint)
                        .with(user("tester").authorities(AuthorityUtils.createAuthorityList("OTRO#R"))))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void SEC_3_any_authenticated_user_can_emit_and_annul_an_invoice_until_granular_permissions_exist()
            throws Exception {
        for (String base : List.of(BillingSaleInvoiceController.BASE_PATH,
                BillingPurchaseInvoiceController.BASE_PATH)) {
            for (String action : List.of("/emit", "/annul")) {
                mockMvc.perform(request(HttpMethod.POST, base + "/" + uuid(2) + action)
                                .param("enterpriseId", ENTERPRISE_ID)
                                .with(user("tester").authorities(AuthorityUtils.NO_AUTHORITIES)))
                        .andExpect(status().is2xxSuccessful());
            }
        }
    }
}
