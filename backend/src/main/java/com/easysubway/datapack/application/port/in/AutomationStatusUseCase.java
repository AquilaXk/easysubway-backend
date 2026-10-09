package com.easysubway.datapack.application.port.in;

import com.easysubway.datapack.application.port.out.AutomationStatusRepository.SaveResult;
import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import java.time.Instant;
import java.util.Optional;

/** 데이터팩 자동화 상태: data 레포 workflow가 게시한 snapshot의 수신과, 지금 시각 기준의 판정. */
public interface AutomationStatusUseCase {

	/** 검증을 통과한 snapshot만 저장한다. 계약에 어긋나면 InvalidAutomationStatusException. */
	SaveResult receive(String payloadJson);

	/** 저장된 snapshot과 지금 시각 기준의 판정. snapshot이 없으면 snapshot은 비어 있고 판정은 수신 전이다. */
	Reading read();

	record Reading(Optional<AutomationStatusSnapshot> snapshot, Instant receivedAt, Instant now, AutomationAssessment assessment) {
	}
}
