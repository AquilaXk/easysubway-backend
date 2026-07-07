package com.easysubway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.easysubway", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageDependencyRulesTest {

	@ArchTest
	static final ArchRule realtime은_admin을_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.realtime..")
		.should().dependOnClassesThat().resideInAnyPackage("com.easysubway.admin..");

	@ArchTest
	static final ArchRule report는_route를_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.report..")
		.should().dependOnClassesThat().resideInAnyPackage("com.easysubway.route..");

	@ArchTest
	static final ArchRule datapack은_runtime_feature를_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.datapack..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"com.easysubway.report..",
			"com.easysubway.realtime..",
			"com.easysubway.route.."
		);
}
