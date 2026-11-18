package com.easysubway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * cron 스케줄은 시간대를 명시한다(이슈 #534).
 *
 * <p>{@code @Scheduled(cron)}에 {@code zone}이 없으면 JVM 기본 시간대로 실행 시각이 정해진다. 실행이 하루의
 * 어느 시각인지, 그 실행이 어느 날짜로 기록되는지가 배포 환경 시간대에 따라 달라지므로 UTC(저장 시각 기준)
 * 또는 업무 시간대 중 하나를 고른다. 어노테이션 기본값은 {@link DefaultZoneUsageRulesTest}가 보지 못한다.
 */
@AnalyzeClasses(packages = "com.easysubway", importOptions = ImportOption.DoNotIncludeTests.class)
class ScheduledCronZoneRulesTest {

	@ArchTest
	static final ArchRule cron_스케줄은_시간대를_명시한다 = cronWithoutZoneRule();

	@Test
	@DisplayName("규칙은 zone 없는 cron만 위반으로 잡는다")
	void zone_없는_cron을_포착한다() {
		JavaClasses fixture = new ClassFileImporter()
			.importPackages("com.easysubway.architecture.fixture.scheduledzone");

		List<String> details = cronWithoutZoneRule().evaluate(fixture).getFailureReport().getDetails();

		assertThat(details).hasSize(1);
		assertThat(details.getFirst()).contains("cronWithoutZone").doesNotContain("cronWithZone", "fixedDelay");
	}

	private static ArchRule cronWithoutZoneRule() {
		return methods()
			.that().areAnnotatedWith(Scheduled.class)
			.should(haveZoneWhenCron())
			.because("cron 실행 시각은 배포 환경의 JVM 기본 시간대가 아니라 명시한 시간대를 따라야 한다");
	}

	private static ArchCondition<JavaMethod> haveZoneWhenCron() {
		return new ArchCondition<>("declare a zone when it uses a cron expression") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				Scheduled scheduled = method.getAnnotationOfType(Scheduled.class);
				boolean valid = scheduled.cron().isEmpty() || !scheduled.zone().isEmpty();
				events.add(new SimpleConditionEvent(method, valid,
					method.getFullName() + (valid ? " declares its zone" : " uses cron without zone")));
			}
		};
	}
}
