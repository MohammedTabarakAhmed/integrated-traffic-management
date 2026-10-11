package gov.itms.permit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the service layering of LLD §1 (hexagonal; domain free of frameworks; no cross-service code).
 * Rules are checked from plain JUnit tests rather than the ArchUnit JUnit engine, which reported zero executed rules
 * under JUnit 6 (a silent false pass).
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("gov.itms.permit");
        assertThat(classes).as("production classes imported").hasSizeGreaterThan(4);
    }

    @Test
    void layersAreRespected() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("api").definedBy("..permit.api..")
                .layer("application").definedBy("..permit.application..")
                .layer("domain").definedBy("..permit.domain..")
                .layer("infrastructure").definedBy("..permit.infrastructure..")
                .whereLayer("api").mayNotBeAccessedByAnyLayer()
                .whereLayer("infrastructure").mayNotBeAccessedByAnyLayer()
                .whereLayer("application").mayOnlyBeAccessedByLayers("api", "infrastructure")
                .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "api", "infrastructure")
                .check(classes);
    }

    @Test
    void domainHasNoFrameworkDependencies() {
        noClasses().that().resideInAPackage("..permit.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..",
                        "tools.jackson..", "gov.itms.platform..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void applicationDoesNotUseJdbcOrWeb() {
        noClasses().that().resideInAPackage("..permit.application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..",
                        "org.springframework.web..", "java.sql..")
                .check(classes);
    }

    /** Services never share code except the platform library (ADR-016: no cross-service coupling). */
    @Test
    void noOtherServiceCode() {
        noClasses().should().dependOnClassesThat(JavaClass.Predicates.resideInAPackage("gov.itms..")
                        .and(JavaClass.Predicates.resideOutsideOfPackages("gov.itms.permit..", "gov.itms.platform..")))
                .check(classes);
    }
}
