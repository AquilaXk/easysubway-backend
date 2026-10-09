package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.application.port.out.AutomationStatusRepository.SaveResult;
import com.easysubway.datapack.application.port.out.AutomationStatusRepository.StoredPayload;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("JdbcAutomationStatusRepository")
class JdbcAutomationStatusRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-10-10T03:00:00Z");

	@Autowired
	private JdbcAutomationStatusRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_automation_status");
	}

	@Test
	@DisplayName("저장된 snapshot이 없으면 빈 값이다")
	void emptyWhenNothingStored() {
		assertThat(repository.findLatest()).isEmpty();
	}

	@Test
	@DisplayName("첫 snapshot을 저장하고 그대로 돌려준다")
	void storesTheFirstSnapshot() {
		assertThat(repository.save("{\"a\":1}", T0, T0.plusSeconds(5))).isEqualTo(SaveResult.ACCEPTED);

		StoredPayload stored = repository.findLatest().orElseThrow();
		assertThat(stored.payloadJson()).isEqualTo("{\"a\":1}");
		assertThat(stored.generatedAt()).isEqualTo(T0);
		assertThat(stored.receivedAt()).isEqualTo(T0.plusSeconds(5));
	}

	@Test
	@DisplayName("더 새로운 generatedAt만 이전 snapshot을 대체한다")
	void onlyANewerSnapshotReplaces() {
		repository.save("{\"v\":1}", T0, T0);

		assertThat(repository.save("{\"v\":0}", T0.minusSeconds(60), T0.plusSeconds(1))).isEqualTo(SaveResult.STALE);
		assertThat(repository.save("{\"v\":1b}", T0, T0.plusSeconds(2))).isEqualTo(SaveResult.STALE);
		assertThat(repository.findLatest().orElseThrow().payloadJson()).isEqualTo("{\"v\":1}");

		assertThat(repository.save("{\"v\":2}", T0.plusSeconds(60), T0.plusSeconds(61))).isEqualTo(SaveResult.ACCEPTED);
		StoredPayload stored = repository.findLatest().orElseThrow();
		assertThat(stored.payloadJson()).isEqualTo("{\"v\":2}");
		assertThat(stored.receivedAt()).isEqualTo(T0.plusSeconds(61));
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datapack_automation_status", Integer.class)).isEqualTo(1);
	}
}
