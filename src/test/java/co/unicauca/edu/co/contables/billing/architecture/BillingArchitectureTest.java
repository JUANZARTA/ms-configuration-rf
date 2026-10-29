package co.unicauca.edu.co.contables.billing.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Reglas de arquitectura del modulo billing (TEN-2, TEN-4).
 * Las reglas permiten conjuntos vacios porque la tanda H1 arranca con poco codigo.
 */
@AnalyzeClasses(
        packages = "co.unicauca.edu.co.contables.billing",
        importOptions = ImportOption.DoNotIncludeTests.class)
class BillingArchitectureTest {

    private static final String BILLING = "co.unicauca.edu.co.contables.billing";

    /** R1: el dominio es Java puro (sin Spring, JPA, Jackson, commons ni otros modulos). */
    private static final ArchRule R1_domain_is_pure = classes()
            .that().resideInAPackage("..billing.domain..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", BILLING + ".domain..")
            .allowEmptyShould(true);

    /** R2: la capa application solo depende de java.*, domain y application. */
    private static final ArchRule R2_application_depends_only_on_domain = classes()
            .that().resideInAPackage("..billing.application..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", BILLING + ".domain..", BILLING + ".application..")
            .allowEmptyShould(true);

    /** R3a: otros modulos solo se alcanzan desde los adaptadores de integracion. */
    private static final ArchRule R3a_other_modules_only_via_integration_adapters = noClasses()
            .that().resideInAPackage("..billing..")
            .and().resideOutsideOfPackage("..billing.infrastructure.adapters.output.integration..")
            .should().dependOnClassesThat(
                    resideInAPackage("co.unicauca.edu.co.contables..")
                            .and(not(resideInAnyPackage(
                                    "..contables.billing..",
                                    "..contables.commons.."))))
            .allowEmptyShould(true);

    /** R3b: ni siquiera la integracion toca infraestructura, entidades o repositorios ajenos. */
    private static final ArchRule R3b_no_other_module_infrastructure = noClasses()
            .that().resideInAPackage("..billing..")
            .should().dependOnClassesThat(
                    resideInAPackage("co.unicauca.edu.co.contables..")
                            .and(not(resideInAnyPackage(
                                    "..contables.billing..",
                                    "..contables.commons..")))
                            .and(resideInAnyPackage(
                                    "..infrastructure..",
                                    "..infraestructure..",
                                    "..dataAccess..",
                                    "..entity..",
                                    "..repository..",
                                    "..persistence..")))
            .allowEmptyShould(true);

    /** R3c: commons solo se usa desde infrastructure. */
    private static final ArchRule R3c_commons_only_from_infrastructure = noClasses()
            .that().resideInAPackage("..billing..")
            .and().resideOutsideOfPackage("..billing.infrastructure..")
            .should().dependOnClassesThat(resideInAPackage("..contables.commons.."))
            .allowEmptyShould(true);

    /** R4: nada de billing depende del legado facture ni de clases Skeleton*. */
    private static final ArchRule R4_no_legacy_dependencies = noClasses()
            .that().resideInAPackage("..billing..")
            .should().dependOnClassesThat(resideInAPackage("..accounting.facture.."))
            .orShould().dependOnClassesThat(simpleNameStartingWith("Skeleton"))
            .allowEmptyShould(true);

    // Las reglas se ejecutan como metodos @ArchTest y no como campos: surefire no contabiliza los
    // tests de ArchUnit declarados como campos (reportaba "Tests run: 0" sin ejecutar nada visible).

    @ArchTest
    void R1_domain_is_pure(JavaClasses classes) {
        R1_domain_is_pure.check(classes);
    }

    @ArchTest
    void R2_application_depends_only_on_domain(JavaClasses classes) {
        R2_application_depends_only_on_domain.check(classes);
    }

    @ArchTest
    void R3a_other_modules_only_via_integration_adapters(JavaClasses classes) {
        R3a_other_modules_only_via_integration_adapters.check(classes);
    }

    @ArchTest
    void R3b_no_other_module_infrastructure(JavaClasses classes) {
        R3b_no_other_module_infrastructure.check(classes);
    }

    @ArchTest
    void R3c_commons_only_from_infrastructure(JavaClasses classes) {
        R3c_commons_only_from_infrastructure.check(classes);
    }

    @ArchTest
    void R4_no_legacy_dependencies(JavaClasses classes) {
        R4_no_legacy_dependencies.check(classes);
    }
}
