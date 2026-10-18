package com.easysubway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 운영 코드가 JVM 기본 시간대에 기대지 않게 한다(이슈 #534).
 *
 * <p>저장하는 시각은 UTC 벽시계 시각이고 응답은 그 UTC 순간을 offset과 함께 내보낸다. 시각을 읽는
 * 쪽이 기본 시간대로 "지금"을 정하면 배포 환경 시간대가 바뀔 때 기록값과 비교값이 어긋난다.
 * 시각은 주입된 또는 명시한 {@code Clock}·{@code ZoneId}로만 얻는다.
 *
 * <p>ArchUnit의 {@code callMethod}는 {@code Clock::systemDefaultZone} 같은 메서드 참조를 보지 못해
 * 호출과 참조를 함께 훑는 조건을 직접 둔다.
 */
@AnalyzeClasses(packages = "com.easysubway", importOptions = ImportOption.DoNotIncludeTests.class)
class DefaultZoneUsageRulesTest {

	/** 인자 없이 호출하면 JVM 기본 시간대를 읽는 정적 메서드. */
	private static final Map<String, Set<String>> DEFAULT_ZONE_SOURCES = Map.ofEntries(
		Map.entry(Clock.class.getName(), Set.of("systemDefaultZone")),
		Map.entry(ZoneId.class.getName(), Set.of("systemDefault")),
		Map.entry(TimeZone.class.getName(), Set.of("getDefault")),
		Map.entry(LocalDateTime.class.getName(), Set.of("now")),
		Map.entry(LocalDate.class.getName(), Set.of("now")),
		Map.entry(LocalTime.class.getName(), Set.of("now")),
		Map.entry(ZonedDateTime.class.getName(), Set.of("now")),
		Map.entry(OffsetDateTime.class.getName(), Set.of("now")),
		Map.entry(Year.class.getName(), Set.of("now")),
		Map.entry(YearMonth.class.getName(), Set.of("now")));

	@ArchTest
	static final ArchRule 운영_코드는_JVM_기본_시간대에_의존하지_않는다 = defaultZoneRule();

	@Test
	@DisplayName("규칙은 기본 시간대 호출과 메서드 참조를 모두 위반으로 잡는다")
	void 기본_시간대_사용을_포착한다() {
		JavaClasses fixture = new ClassFileImporter()
			.importPackages("com.easysubway.architecture.fixture.defaultzone");

		List<String> details = defaultZoneRule().evaluate(fixture).getFailureReport().getDetails();

		assertThat(details)
			.anyMatch(detail -> detail.contains("Clock.systemDefaultZone()") && detail.contains("defaultZoneClock()"))
			.anyMatch(detail -> detail.contains("Clock.systemDefaultZone()")
				&& detail.contains("defaultZoneClockReference()"))
			.anyMatch(detail -> detail.contains("LocalDateTime.now()"))
			.anyMatch(detail -> detail.contains("LocalDate.now()"))
			.anyMatch(detail -> detail.contains("ZoneId.systemDefault()"))
			.anyMatch(detail -> detail.contains("TimeZone.getDefault()"));
	}

	@Test
	@DisplayName("명시한 Clock과 시간대를 쓰는 now는 위반이 아니다")
	void 명시한_시간대는_허용한다() {
		JavaClasses explicitZone = new ClassFileImporter()
			.importPackages("com.easysubway.architecture.fixture.explicitzone");

		assertThat(defaultZoneRule().evaluate(explicitZone).hasViolation()).isFalse();
	}

	private static ArchRule defaultZoneRule() {
		return noClasses()
			.should(useJvmDefaultTimeZone())
			.because("시각은 UTC 또는 명시한 서비스 시간대의 Clock으로만 얻는다");
	}

	private static ArchCondition<JavaClass> useJvmDefaultTimeZone() {
		return new ArchCondition<>("use the JVM default time zone") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				Stream.concat(javaClass.getMethodCallsFromSelf().stream(), javaClass.getMethodReferencesFromSelf().stream())
					.filter(access -> readsDefaultZone(access))
					.forEach(access -> events.add(SimpleConditionEvent.satisfied(access, access.getDescription())));
			}
		};
	}

	private static boolean readsDefaultZone(JavaCodeUnitAccess<?> access) {
		CodeUnitAccessTarget target = access.getTarget();
		return DEFAULT_ZONE_SOURCES.getOrDefault(target.getOwner().getName(), Set.of()).contains(target.getName())
			&& target.getRawParameterTypes().isEmpty();
	}
}
