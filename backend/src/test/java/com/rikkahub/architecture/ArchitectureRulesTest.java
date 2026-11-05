package com.rikkahub.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable module boundaries. These rules make the dependency directions in
 * docs/project-structure.md fail the build instead of relying on review.
 */
@AnalyzeClasses(packages = "com.rikkahub", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule agentCoreHasNoFrameworkOrProductDependencies = noClasses()
            .that().resideInAPackage("com.rikkahub.agent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "java.sql..",
                    "javax.sql..",
                    "jakarta.servlet..",
                    "com.rikkahub.features..",
                    "com.rikkahub.platform..",
                    "com.rikkahub.bootstrap..",
                    "com.rikkahub.plugins..");

    @ArchTest
    static final ArchRule pluginsDoNotDependOnFrameworkOrBackend = noClasses()
            .that().resideInAPackage("com.rikkahub.plugins..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "com.rikkahub.features..",
                    "com.rikkahub.platform..",
                    "com.rikkahub.bootstrap..");

    @ArchTest
    static final ArchRule featuresDoNotDependOnOtherFeatureInfrastructure = classes()
            .that().resideInAPackage("com.rikkahub.features..")
            .should(notDependOnOtherFeatureInfrastructure())
            .because("features may only use another feature's application services or shared ports");

    /** Verifies the custom condition really catches a violation instead of passing vacuously. */
    @Test
    void crossFeatureInfrastructureRuleRejectsViolations() {
        JavaClasses classes = new ClassFileImporter().importPackages(
                "com.rikkahub.features.fixturea",
                "com.rikkahub.features.fixtureb");

        EvaluationResult result = classes()
                .that().resideInAPackage("com.rikkahub.features..")
                .should(notDependOnOtherFeatureInfrastructure())
                .evaluate(classes);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("fixtureb");
    }

    private static ArchCondition<JavaClass> notDependOnOtherFeatureInfrastructure() {
        return new ArchCondition<JavaClass>("not depend on another feature's infrastructure package") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String ownerFeature = featureOf(item.getPackageName());
                if (ownerFeature == null) {
                    return;
                }
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetFeature = featureOf(target.getPackageName());
                    if (targetFeature != null
                            && !targetFeature.equals(ownerFeature)
                            && target.getPackageName().contains(".infrastructure")) {
                        events.add(SimpleConditionEvent.violated(item, item.getName()
                                + " depends on " + target.getName()
                                + " (" + dependency.getDescription() + ")"));
                    }
                }
            }
        };
    }

    private static String featureOf(String packageName) {
        String prefix = "com.rikkahub.features.";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String remainder = packageName.substring(prefix.length());
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }
}
