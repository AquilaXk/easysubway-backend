package com.easysubway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
		.because("owner=AquilaXk/easysubway-backend#56; legacy-json-annotation-baseline=RouteSearchResult·RouteWarning Jackson; "
			+ "removal-condition=Journey V3 adapter-owned mapping replaces these legacy domain annotations; review-expiry=2026-10-31.");

	@Test
	void route_domain_jackson_기존_baseline은_만료일_이후_실패한다() {
		assertTrue(LocalDate.now(ZoneOffset.UTC).isBefore(LocalDate.of(2026, 11, 1)),
			"AquilaXk/easysubway-backend#56: Journey V3 adapter-owned mapping replaces these legacy domain annotations.");
	}

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
