package com.easysubway.datapack.application.service;

import com.easysubway.datapack.application.port.in.AutomationStatusUseCase;
import com.easysubway.datapack.application.port.out.AutomationStatusRepository;
import com.easysubway.datapack.application.port.out.AutomationStatusRepository.SaveResult;
import com.easysubway.datapack.application.port.out.AutomationStatusRepository.StoredPayload;
import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import com.easysubway.datapack.domain.InvalidAutomationStatusException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 자동화 상태 snapshot 수신과 판정. backend는 GitHub를 호출하지 않고, 게시된 값만 검증해 보관한다. */
@Service
public class AutomationStatusService implements AutomationStatusUseCase {

	private static final Logger LOGGER = LoggerFactory.getLogger(AutomationStatusService.class);
	/** 게시 시계가 이만큼 앞서 있으면 거부한다: 미래 시각 하나가 이후의 모든 갱신을 STALE로 막는 것을 방지한다. */
	static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

	private final AutomationStatusRepository repository;
	private final AutomationStatusSnapshotParser parser;
	private final AutomationStatusAssessor assessor;
	private final Clock clock;
	/** 이 서버가 snapshot 수신을 기다리기 시작한 시각. 아무것도 받지 못한 채 오래 지나면 조용히 두지 않는다. */
	private final Instant observingSince;

	@Autowired
	public AutomationStatusService(
		AutomationStatusRepository repository,
		AutomationStatusSnapshotParser parser,
		AutomationStatusAssessor assessor,
		ObjectProvider<Clock> clockProvider
	) {
		this(repository, parser, assessor, clockProvider.getIfAvailable(Clock::systemUTC));
	}

	AutomationStatusService(
		AutomationStatusRepository repository,
		AutomationStatusSnapshotParser parser,
		AutomationStatusAssessor assessor,
		Clock clock
	) {
		this.repository = repository;
		this.parser = parser;
		this.assessor = assessor;
		this.clock = clock;
		this.observingSince = clock.instant();
	}

	@Override
	public SaveResult receive(String payloadJson) {
		AutomationStatusSnapshot snapshot = parser.parse(payloadJson);
		Instant now = clock.instant();
		if (snapshot.generatedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
			throw new InvalidAutomationStatusException("generatedAt이 현재 시각보다 앞서 있습니다");
		}
		return repository.save(payloadJson, snapshot.generatedAt(), now);
	}

	@Override
	public Reading read() {
		Instant now = clock.instant();
		Optional<StoredPayload> stored = repository.findLatest();
		if (stored.isEmpty()) {
			return new Reading(Optional.empty(), null, now, assessor.assess(Optional.empty(), now, observingSince));
		}
		try {
			AutomationStatusSnapshot snapshot = parser.parse(stored.get().payloadJson());
			AutomationAssessment assessment = assessor.assess(
				Optional.of(new StoredAutomationStatus(snapshot, stored.get().receivedAt())), now, observingSince);
			return new Reading(Optional.of(snapshot), stored.get().receivedAt(), now, assessment);
		} catch (InvalidAutomationStatusException invalid) {
			LOGGER.error("저장된 자동화 상태가 검증을 통과하지 못했습니다: {}", invalid.getMessage());
			return new Reading(Optional.empty(), stored.get().receivedAt(), now, assessor.storedPayloadInvalid());
		}
	}
}
