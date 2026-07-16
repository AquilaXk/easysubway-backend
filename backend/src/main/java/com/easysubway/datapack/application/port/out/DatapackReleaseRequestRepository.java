package com.easysubway.datapack.application.port.out;

import com.easysubway.datapack.domain.DatapackReleaseRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DatapackReleaseRequestRepository {

	void save(DatapackReleaseRequest request);

	Optional<DatapackReleaseRequest> findByApprovalId(String approvalId);

	List<DatapackReleaseRequest> findRecent(int limit);

	List<DatapackReleaseRequest> claimReconciliationDue(
		LocalDateTime cutoff, LocalDateTime now, LocalDateTime leaseUntil, int limit);
}
