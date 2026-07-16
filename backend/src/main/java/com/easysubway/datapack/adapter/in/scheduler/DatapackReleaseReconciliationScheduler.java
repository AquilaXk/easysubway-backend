package com.easysubway.datapack.adapter.in.scheduler;

import com.easysubway.datapack.application.service.DatapackReleaseReconciliationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DatapackReleaseReconciliationScheduler {
	private final DatapackReleaseReconciliationService service;

	public DatapackReleaseReconciliationScheduler(DatapackReleaseReconciliationService service) {
		this.service = service;
	}

	@Scheduled(
		fixedDelayString = "${easysubway.datapack.reconciliation-interval-ms:60000}",
		scheduler = "datapackReleaseTaskScheduler"
	)
	public void run() {
		service.reconcileDue();
	}
}
