package com.easysubway.admin.errors.adapter.out.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.errors.application.ErrorEventQuery;
import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("오류 이벤트 90일 보존 배치")
class ErrorEventRetentionBatchConfigTest {

	@Autowired
	private JobLauncher jobLauncher;

	@Autowired
	@Qualifier(ErrorEventRetentionBatchConfig.JOB_NAME)
	private Job errorEventRetentionJob;

	@Autowired
	private ErrorEventRepository errorEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("보존 잡은 90일 초과 행만 삭제한다")
	void retentionJobDeletesOnlyExpiredRows() throws Exception {
		Instant now = Instant.now();
		errorEventRepository.upsertOccurrence(event("ret-old", "/old", now.minus(120, ChronoUnit.DAYS)));
		errorEventRepository.upsertOccurrence(event("ret-new", "/new", now.minus(10, ChronoUnit.DAYS)));

		var execution = jobLauncher.run(
			errorEventRetentionJob,
			new JobParametersBuilder()
				.addLong("run.id", System.nanoTime())
				.toJobParameters()
		);

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		var hashes = errorEventRepository.search(ErrorEventQuery.of(null, null, null, null, 0, 50)).items()
			.stream().map(ErrorEvent::stackHash).toList();
		assertThat(hashes).anyMatch(hash -> hash.startsWith("ret-new"));
		assertThat(hashes).noneMatch(hash -> hash.startsWith("ret-old"));
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM error_events WHERE stack_hash LIKE ?",
			Long.class,
			"ret-old%"
		)).isZero();
	}

	private static ErrorEvent event(String stackHash, String pathPattern, Instant at) {
		return new ErrorEvent(
			null,
			at,
			at,
			"INTERNAL_ERROR",
			"SYSTEM",
			500,
			"GET",
			pathPattern,
			"java.lang.IllegalStateException",
			(stackHash + "0".repeat(64)).substring(0, 64),
			"corr",
			1L
		);
	}
}
