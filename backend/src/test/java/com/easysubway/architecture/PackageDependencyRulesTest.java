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
	static final ArchRule journey_raptor_adapters는_legacy_mobility를_모른다 = noClasses()
		.that().haveFullyQualifiedName("com.easysubway.route.application.service.JourneyRaptorAdapter")
		.or().haveFullyQualifiedName("com.easysubway.route.application.service.JourneyRealtimeAdapter")
		.should().dependOnClassesThat().haveFullyQualifiedName("com.easysubway.profile.domain.MobilityType");

	@ArchTest
	static final ArchRule route_domain은_framework을_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.route.domain..")
		.should().dependOnClassesThat().resideInAnyPackage(
			"org.springframework..", "jakarta.servlet..", "javax.servlet..",
			"java.sql..", "javax.sql..", "io.micrometer.."
		);

	@ArchTest
	static final ArchRule route_domain은_jackson을_모른다 = noClasses()
		.that().resideInAPackage("com.easysubway.route.domain..")
		.should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..")
		.because("route 도메인은 직렬화 라이브러리에 의존하지 않는다");

	@ArchTest
	static final ArchRule 웹_진입점은_레거시_경로검색을_모른다 = noClasses()
		.that().resideInAPackage("..adapter.in.web..")
		.should().dependOnClassesThat()
		.haveFullyQualifiedName("com.easysubway.route.application.service.RouteSearchService")
		.orShould().dependOnClassesThat()
		.haveFullyQualifiedName("com.easysubway.route.application.port.in.RouteSearchUseCase")
		.because("공개 경로 탐색은 Journey V3 서버 공인 라우팅만 쓴다");

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
		assertThrows(AssertionError.class, () -> route_domain은_jackson을_모른다.check(
			new ClassFileImporter().importPackages("com.easysubway.route.domain.fixture")
		));
		assertThrows(AssertionError.class, () -> route_domain은_framework을_모른다.check(
			new ClassFileImporter().importPackages("com.easysubway.route.domain.fixture.jdbc")
		));
	}
}
