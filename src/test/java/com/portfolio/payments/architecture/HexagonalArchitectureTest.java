package com.portfolio.payments.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Testes arquiteturais com ArchUnit para validar a Arquitetura Hexagonal (ADR-0001).
 */
@AnalyzeClasses(packages = "com.portfolio.payments", importOptions = ImportOption.DoNotIncludeTests.class)
public class HexagonalArchitectureTest {

    @ArchTest
    public static final ArchRule domain_must_be_pure =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..application..",
                "..infrastructure..",
                "..interfaces..",
                "org.springframework..",
                "jakarta.persistence..",
                "org.apache.kafka.."
            )
            .because("Domain layer must be completely pure and independent of frameworks and outer layers");

    @ArchTest
    public static final ArchRule application_must_not_depend_on_infra_or_interfaces =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..infrastructure..",
                "..interfaces..",
                "org.springframework.kafka..",
                "jakarta.persistence..",
                "org.springframework.data.redis.."
            )
            .because("Application layer orchestrates use cases via ports and must not depend on concrete adapters");

    @ArchTest
    public static final ArchRule interfaces_must_not_touch_infrastructure_directly =
        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..")
            .because("Web interfaces must interact through application use cases, not persistence adapters");
}
