package com.easysubway.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JDBC 시각 변환이 JVM 기본 시간대에 기대는 지점을 더 늘리지 못하게 한다(이슈 #534).
 *
 * <p>{@code Timestamp.valueOf(LocalDateTime)}·{@code Timestamp.toLocalDateTime()}은 JVM 기본 시간대로
 * 변환한다. 쓰기와 읽기가 같은 시간대로 왕복하므로 UTC·KST처럼 DST가 없는 시간대에서는 값이 보존되지만,
 * DST가 있는 시간대로 바뀌면 존재하지 않는 시각(봄 전환 구간)이 밀릴 수 있다. 기존 사용처는
 * {@link JdbcDefaultZoneConversionAllowlist}에 고정하고, 새 사용처는 {@code PreparedStatement.setObject(LocalDateTime)}·
 * {@code ResultSet.getObject(LocalDateTime.class)}처럼 기본 시간대를 거치지 않는 바인딩을 쓴다.
 */
class JdbcDefaultZoneConversionRulesTest {

	private static final Map<String, Set<String>> DEFAULT_ZONE_CONVERSIONS = Map.of(
		"java.sql.Timestamp", Set.of("valueOf", "toLocalDateTime"),
		"java.sql.Date", Set.of("valueOf", "toLocalDate"),
		"java.util.Calendar", Set.of("getInstance"));

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.easysubway");

	@Test
	@DisplayName("기본 시간대 JDBC 변환은 허용 목록 밖에서 쓰지 않는다")
	void noNewDefaultZoneConversions() {
		Set<String> unlisted = new TreeSet<>(conversionSites(PRODUCTION_CLASSES));
		unlisted.removeAll(JdbcDefaultZoneConversionAllowlist.SITES);

		assertThat(unlisted)
			.as("새 코드는 setObject(LocalDateTime)·getObject(LocalDateTime.class)를 쓴다")
			.isEmpty();
	}

	@Test
	@DisplayName("허용 목록에는 더 이상 쓰지 않는 항목이 남지 않는다")
	void allowlistHasNoStaleEntries() {
		Set<String> stale = new TreeSet<>(JdbcDefaultZoneConversionAllowlist.SITES);
		stale.removeAll(conversionSites(PRODUCTION_CLASSES));

		assertThat(stale).as("사용처를 정리했다면 허용 목록에서도 지운다").isEmpty();
	}

	@Test
	@DisplayName("규칙은 호출과 메서드 참조, 람다 안의 사용을 둘러싼 메서드로 식별한다")
	void identifiesSitesByEnclosingMethod() {
		JavaClasses fixture = new ClassFileImporter()
			.importPackages("com.easysubway.architecture.fixture.jdbczone");

		assertThat(conversionSites(fixture)).containsExactlyInAnyOrder(
			"com.easysubway.architecture.fixture.jdbczone.JdbcZoneFixture#toColumn",
			"com.easysubway.architecture.fixture.jdbczone.JdbcZoneFixture#fromColumn",
			"com.easysubway.architecture.fixture.jdbczone.JdbcZoneFixture#fromColumnInLambda",
			"com.easysubway.architecture.fixture.jdbczone.JdbcZoneFixture#fromColumnByReference",
			"com.easysubway.architecture.fixture.jdbczone.JdbcZoneFixture#sqlDate",
			"com.easysubway.architecture.fixture.jdbczone.JdbcZoneFixture#calendar");
	}

	/** 변환을 쓰는 곳을 {@code 클래스#메서드}로 모은다. 줄 번호와 람다 번호는 키에 넣지 않는다. */
	private static Set<String> conversionSites(JavaClasses classes) {
		Set<String> sites = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			Stream.concat(javaClass.getMethodCallsFromSelf().stream(), javaClass.getMethodReferencesFromSelf().stream())
				.filter(JdbcDefaultZoneConversionRulesTest::isDefaultZoneConversion)
				.forEach(access -> sites.add(javaClass.getName() + "#" + enclosingMethodName(access.getOrigin())));
		}
		return sites;
	}

	private static boolean isDefaultZoneConversion(JavaCodeUnitAccess<?> access) {
		return DEFAULT_ZONE_CONVERSIONS
			.getOrDefault(access.getTarget().getOwner().getName(), Set.of())
			.contains(access.getTarget().getName());
	}

	/** 컴파일러가 만든 {@code lambda$메서드$N}은 그 메서드 이름으로 되돌린다. */
	private static String enclosingMethodName(JavaCodeUnit origin) {
		String name = origin.getName();
		if (name.startsWith("lambda$")) {
			int end = name.lastIndexOf('$');
			return name.substring("lambda$".length(), end);
		}
		return name;
	}
}
