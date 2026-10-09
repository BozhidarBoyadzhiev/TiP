package dev.tip;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Optional;

/** Feature packages talk to each other only through {@code api} packages or {@code shared}. */
@AnalyzeClasses(packages = "dev.tip", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "dev.tip.";
    private static final List<String> FEATURES = List.of("tipping", "paypal", "devices", "ai", "dashboard", "owners");

    @ArchTest
    static final ArchRule featuresUseOtherFeaturesOnlyThroughTheirApi = classes()
            .that()
            .resideInAnyPackage(FEATURES.stream().map(f -> ROOT + f + "..").toArray(String[]::new))
            .should(onlyDependOnOwnFeatureOtherApisOrShared());

    @ArchTest
    static final ArchRule sharedDependsOnNoFeature = noClasses()
            .that()
            .resideInAPackage(ROOT + "shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FEATURES.stream().map(f -> ROOT + f + "..").toArray(String[]::new));

    @ArchTest
    static final ArchRule apiPackagesDoNotReachIntoTheirFeature =
            classes().that().resideInAPackage(ROOT + "*.api..").should(notDependOnFeatureInternals());

    private static ArchCondition<JavaClass> onlyDependOnOwnFeatureOtherApisOrShared() {
        return new ArchCondition<>("only depend on their own feature, other features' api packages or shared") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String feature = featureOf(origin.getPackageName()).orElseThrow();
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    Optional<String> targetFeature = featureOf(target);
                    boolean allowed = !target.startsWith(ROOT)
                            || target.startsWith(ROOT + "shared")
                            || targetFeature.isEmpty()
                            || targetFeature.get().equals(feature)
                            || isApi(target);
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDependOnFeatureInternals() {
        return new ArchCondition<>("not depend on feature internals") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    if (featureOf(target).isPresent() && !isApi(target)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static Optional<String> featureOf(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return Optional.empty();
        }
        String first = packageName.substring(ROOT.length()).split("\\.")[0];
        return FEATURES.contains(first) ? Optional.of(first) : Optional.empty();
    }

    private static boolean isApi(String packageName) {
        return featureOf(packageName)
                .map(f -> packageName.equals(ROOT + f + ".api") || packageName.startsWith(ROOT + f + ".api."))
                .orElse(false);
    }
}
