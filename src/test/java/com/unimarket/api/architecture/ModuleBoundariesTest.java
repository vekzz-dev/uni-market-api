package com.unimarket.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Fronteras del monolito modular (SDD §3 y §4).
 *
 * <p>Se verifica sobre bytecode, sin levantar contexto Spring: es un test unitario plano.
 * Mientras los módulos estén vacíos, las reglas usan {@code allowEmptyShould(true)}; al
 * agregar código de dominio deben seguir pasando sin cambios.
 */
@AnalyzeClasses(packages = "com.unimarket.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    @ArchTest
    static final ArchRule sharedShouldNotDependOnAnyModule = noClasses()
            .that().resideInAPackage("com.unimarket.api.shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.unimarket.api.identity..",
                    "com.unimarket.api.catalog..",
                    "com.unimarket.api.moderation..",
                    "com.unimarket.api.messaging..",
                    "com.unimarket.api.admin..",
                    "com.unimarket.api.notifications..",
                    "com.unimarket.api.media..")
            .allowEmptyShould(true)
            .because("`shared` es el kernel transversal y no puede conocer a ningún módulo de dominio (SDD §4)");

    @ArchTest
    static final ArchRule modulesShouldBeFreeOfCycles = slices()
            .matching("com.unimarket.api.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("las dependencias entre módulos siguen una única dirección, sin ciclos (SDD §3)");
}
