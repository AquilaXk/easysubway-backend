package com.easysubway.datapack.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.easysubway.datapack.application.service.DatapackReleaseReconciliationService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

class DatapackReleaseReconciliationSchedulerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(DatapackReleaseSchedulingConfiguration.class);

	@Test
	void delegatesToReconciler() {
		var service = mock(DatapackReleaseReconciliationService.class);
		new DatapackReleaseReconciliationScheduler(service).run();
		verify(service).reconcileDue();
	}

	@Test
	void usesDedicatedTaskScheduler() throws Exception {
		Method run = DatapackReleaseReconciliationScheduler.class.getMethod("run");
		assertThat(run.getAnnotation(Scheduled.class).scheduler())
			.isEqualTo("datapackReleaseTaskScheduler");

		contextRunner.run(context -> {
			assertThat(context).hasBean("datapackReleaseTaskScheduler");
			assertThat(context.getBean("datapackReleaseTaskScheduler"))
				.isInstanceOf(TaskScheduler.class);
		});
	}
}
