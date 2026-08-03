package com.easysubway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

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

	@ArchTest
	static final ArchRule route_application은_adapter를_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.route.application..")
		.should().dependOnClassesThat().resideInAnyPackage("com.easysubway.route.adapter..");

	@ArchTest
	static final ArchRule route_application_port_in은_port_out을_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.route.application.port.in..")
		.should().dependOnClassesThat().resideInAnyPackage("com.easysubway.route.application.port.out..");

	@ArchTest
	static final ArchRule route_domain은_framework을_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.route.domain..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"org.springframework..", "jakarta.servlet..", "javax.servlet..",
			"java.sql..", "javax.sql..", "io.micrometer.."
		);

	@ArchTest
	static final ArchRule route_domain의_jackson은_두_기존_class만_허용한다 = noClasses()
		.that().resideInAPackage("com.easysubway.route.domain..")
		.and().areNotAssignableTo(com.easysubway.route.domain.RouteSearchResult.class)
		.and().areNotAssignableTo(com.easysubway.route.domain.RouteWarning.class)
		.should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..")
		.because("AquilaXk/easysubway-backend#11: adapter-owned JSON mapping/domain annotation-free로 전환하면 "
			+ "RouteSearchResult·RouteWarning Jackson baseline을 제거한다 (2026-10-31 만료).");

	@Test
	void application_to_adapter_위반을_포착한다() {
		assertThrows(AssertionError.class, () -> route_application은_adapter를_모른다.check(
			new ClassFileImporter().importPackages("com.easysubway.route.application.fixture.adapter")
		));
	}

	@Test
	void port_in_to_port_out_위반을_포착한다() {
		assertThrows(AssertionError.class, () -> route_application_port_in은_port_out을_모른다.check(
			new ClassFileImporter().importPackages("com.easysubway.route.application.port.in.fixture")
		));
	}

	@Test
	void domain_to_framework_위반을_포착한다() {
		assertThrows(AssertionError.class, () -> route_domain은_framework을_모른다.check(
			new ClassFileImporter().importPackages("com.easysubway.route.domain.fixture")
		));
		assertThrows(AssertionError.class, () -> route_domain의_jackson은_두_기존_class만_허용한다.check(
			new ClassFileImporter().importPackages("com.easysubway.route.domain.fixture")
		));
		assertThrows(AssertionError.class, () -> route_domain은_framework을_모른다.check(
			new ClassFileImporter().importPackages("com.easysubway.route.domain.fixture.jdbc")
		));
	}
}
