package com.easysubway.admin.batch.domain;

import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.util.Arrays;
import java.util.List;

public enum AdminBatchJob {
	TRANSIT_MASTER_COLLECTION(
		"transit-master-collection",
		"transitMasterCollectionJob",
		"도시철도 마스터 수집",
		DataCollectionSource.TRANSIT_MASTER,
		true
	);

	private final String id;
	private final String jobName;
	private final String label;
	private final DataCollectionSource source;
	private final boolean retryEnabled;

	AdminBatchJob(String id, String jobName, String label, DataCollectionSource source, boolean retryEnabled) {
		this.id = id;
		this.jobName = jobName;
		this.label = label;
		this.source = source;
		this.retryEnabled = retryEnabled;
	}

	public String id() {
		return id;
	}

	public String jobName() {
		return jobName;
	}

	public String label() {
		return label;
	}

	public DataCollectionSource source() {
		return source;
	}

	public boolean retryEnabled() {
		return retryEnabled;
	}

	public static List<AdminBatchJob> all() {
		return Arrays.asList(values());
	}

	public static AdminBatchJob require(String id) {
		return Arrays.stream(values())
			.filter(job -> job.id.equals(id))
			.findFirst()
			.orElseThrow(() -> new InvalidDataCollectionException("허용되지 않은 배치 작업입니다."));
	}
}
