package com.easysubway.usage.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.usage.domain.InvalidUserActivityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 사용자 활동 저장소")
class InMemoryUserActivityRepositoryTest {

	private final InMemoryUserActivityRepository repository = new InMemoryUserActivityRepository();

	@Test
	@DisplayName("최근 기간의 일별 고유 활성 사용자와 기간 내 전체 고유 사용자를 집계한다")
	void summarizeUserActivityCountsDistinctUsersByDayAndRange() {
		repository.recordUserActivity("anonymous-user-1", LocalDateTime.of(2026, 6, 17, 9, 0));
		repository.recordUserActivity("anonymous-user-1", LocalDateTime.of(2026, 6, 17, 10, 0));
		repository.recordUserActivity("anonymous-user-2", LocalDateTime.of(2026, 6, 16, 11, 0));
		repository.recordUserActivity("anonymous-user-old", LocalDateTime.of(2026, 6, 15, 11, 0));

		var summary = repository.summarizeUserActivity(LocalDate.of(2026, 6, 17), 2);

		assertThat(summary.totalActiveUsers()).isEqualTo(2);
		assertThat(summary.dailyActivities())
			.extracting(row -> row.date() + ":" + row.activeUserCount())
			.containsExactly("2026-06-17:1", "2026-06-16:1");
	}

	@Test
	@DisplayName("비어 있는 사용자 식별자는 활동 기록으로 저장하지 않는다")
	void recordUserActivityRejectsBlankUserId() {
		assertThatThrownBy(() -> repository.recordUserActivity(" ", LocalDateTime.of(2026, 6, 17, 9, 0)))
			.isInstanceOf(InvalidUserActivityException.class)
			.hasMessage("사용자 활동 식별자가 필요합니다.");
	}
}
