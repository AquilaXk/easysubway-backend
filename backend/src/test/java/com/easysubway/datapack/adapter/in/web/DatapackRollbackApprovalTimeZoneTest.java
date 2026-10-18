package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelEventRow;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@DisplayName("rollback 승인 조회 API의 시간대 독립성")
class DatapackRollbackApprovalTimeZoneTest {

	private static final String EVENT_ID = "rollback-event-1";

	@Test
	@DisplayName("JVM 기본 시간대가 KST여도 승인 시각은 저장한 UTC 순간을 Z로 돌려준다")
	@ResourceLock(Resources.TIME_ZONE)
	void approvedAtIsUtcRegardlessOfDefaultTimeZone() {
		JdbcDatapackReleaseChannelRepository repository = mock(JdbcDatapackReleaseChannelRepository.class);
		when(repository.findEventById(EVENT_ID)).thenReturn(Optional.of(new ReleaseChannelEventRow(
			EVENT_ID, "production", "failed-candidate", "known-good-candidate",
			"1".repeat(64), "2".repeat(64), "ROLLBACK", "PASS", "operator", "approver",
			"incident-1", EVENT_ID, "https://github.com/run/1", LocalDateTime.parse("2026-07-15T01:00:00"))));
		DatapackRollbackApprovalApiController controller = new DatapackRollbackApprovalApiController(repository);
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));

			Map<String, Object> body = controller.get(EVENT_ID).getBody();

			assertThat(body).containsEntry("approvedAt", "2026-07-15T01:00:00Z");
		} finally {
			TimeZone.setDefault(original);
		}
	}
}
