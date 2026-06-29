package com.easysubway.field.application.service;

import com.easysubway.common.error.ResourceNotFoundException;
import com.easysubway.field.application.port.in.FieldVerificationUseCase;
import com.easysubway.field.application.port.in.UpdateFieldVerificationItemStatusCommand;
import com.easysubway.field.application.port.out.FieldVerificationChangeHistoryRepository;
import com.easysubway.field.application.port.out.FieldVerificationSessionRepository;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FieldVerificationService implements FieldVerificationUseCase {

	private static final String SANGNOKSU_STATION_ID = "station-sangnoksu";
	private static final String SADANG_STATION_ID = "station-sadang";
	private static final FieldVerificationSession SANGNOKSU_BASELINE = new FieldVerificationSession(
		"field-verification-sangnoksu-2026-06",
		SANGNOKSU_STATION_ID,
		"상록수역",
		LocalDate.of(2026, 6, 19),
		"field-team",
		FieldVerificationStatus.IN_PROGRESS,
		"첫 현장 확인 지역 기준선",
		List.of(
			item("field-verification-sangnoksu-exit", FieldVerificationItemType.EXIT, "주요 출구 연결", FieldVerificationStatus.VERIFIED),
			item("field-verification-sangnoksu-elevator", FieldVerificationItemType.ELEVATOR, "엘리베이터 위치와 운행 상태", FieldVerificationStatus.VERIFIED),
			item("field-verification-sangnoksu-escalator", FieldVerificationItemType.ESCALATOR, "에스컬레이터 위치와 방향", FieldVerificationStatus.PLANNED),
			item("field-verification-sangnoksu-restroom", FieldVerificationItemType.RESTROOM, "일반/장애인 화장실 위치", FieldVerificationStatus.PLANNED),
			item("field-verification-sangnoksu-platform-transfer", FieldVerificationItemType.PLATFORM_TRANSFER, "승강장과 환승 접근 동선", FieldVerificationStatus.PLANNED)
		)
	);
	private static final FieldVerificationSession SADANG_BASELINE = new FieldVerificationSession(
		"field-verification-sadang-2026-06",
		SADANG_STATION_ID,
		"사당역",
		LocalDate.of(2026, 6, 19),
		"field-team",
		FieldVerificationStatus.PLANNED,
		"주요 환승역 현장 확인 확대 기준선",
		List.of(
			item("field-verification-sadang-exit", FieldVerificationItemType.EXIT, "2호선/4호선 출구 연결", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-elevator", FieldVerificationItemType.ELEVATOR, "환승 구간 엘리베이터 위치와 운행 상태", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-escalator", FieldVerificationItemType.ESCALATOR, "환승 구간 에스컬레이터 위치와 방향", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-restroom", FieldVerificationItemType.RESTROOM, "일반/장애인 화장실 위치", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-platform-transfer", FieldVerificationItemType.PLATFORM_TRANSFER, "2호선과 4호선 환승 접근 동선", FieldVerificationStatus.PLANNED)
		)
	);
	private final FieldVerificationSessionRepository sessionRepository;
	private final FieldVerificationChangeHistoryRepository historyRepository;

	public FieldVerificationService(
		FieldVerificationSessionRepository sessionRepository,
		FieldVerificationChangeHistoryRepository historyRepository
	) {
		this.sessionRepository = sessionRepository;
		this.historyRepository = historyRepository;
		seedBaselineSession(SANGNOKSU_BASELINE);
		seedBaselineSession(SADANG_BASELINE);
	}

	@Override
	public synchronized List<FieldVerificationSession> listStationVerifications() {
		return sessionRepository.listAll();
	}

	@Override
	public synchronized FieldVerificationSession getStationVerification(String stationId) {
		return findSession(stationId);
	}

	@Override
	@Transactional
	public synchronized FieldVerificationSession updateItemStatus(UpdateFieldVerificationItemStatusCommand command) {
		FieldVerificationSession session = findSession(command.stationId());
		FieldVerificationItem previousItem = session.items().stream()
			.filter(item -> item.id().equals(command.itemId()))
			.findFirst()
			.orElse(null);
		if (previousItem == null) {
			throw new ResourceNotFoundException("현장 확인 항목을 찾을 수 없습니다.");
		}
		List<FieldVerificationItem> items = session.items().stream()
			.map(item -> updateItem(command, item))
			.toList();
		FieldVerificationSession updated = new FieldVerificationSession(
			session.id(),
			session.stationId(),
			session.stationName(),
			session.verifiedAt(),
			session.verifiedBy(),
			sessionStatus(items),
			session.note(),
			items
		);
		sessionRepository.save(updated);
		addHistory(session, previousItem, command);
		return updated;
	}

	@Override
	public synchronized List<FieldVerificationChangeHistory> listStationChangeHistory(String stationId) {
		findSession(stationId);
		return historyRepository.listByStationId(stationId);
	}

	private FieldVerificationSession findSession(String stationId) {
		return sessionRepository.findByStationId(stationId)
			.orElseThrow(() -> new ResourceNotFoundException("현장 확인 기준선을 찾을 수 없습니다."));
	}

	private void seedBaselineSession(FieldVerificationSession session) {
		if (sessionRepository.findByStationId(session.stationId()).isEmpty()) {
			sessionRepository.save(session);
		}
	}

	private FieldVerificationItem updateItem(
		UpdateFieldVerificationItemStatusCommand command,
		FieldVerificationItem item
	) {
		if (!item.id().equals(command.itemId())) {
			return item;
		}
		return new FieldVerificationItem(
			item.id(),
			item.type(),
			item.targetName(),
			command.status(),
			command.note()
		);
	}

	private void addHistory(
		FieldVerificationSession session,
		FieldVerificationItem previousItem,
		UpdateFieldVerificationItemStatusCommand command
	) {
		int nextSequence = historyRepository.listByStationId(session.stationId()).size() + 1;
		historyRepository.save(new FieldVerificationChangeHistory(
			"field-verification-history-" + session.stationId() + "-" + nextSequence,
			session.id(),
			session.stationId(),
			previousItem.id(),
			previousItem.status(),
			command.status(),
			previousItem.note(),
			command.note(),
			command.changedBy(),
			LocalDateTime.now()
		));
	}

	private FieldVerificationStatus sessionStatus(List<FieldVerificationItem> items) {
		if (items.stream().allMatch(item -> item.status() == FieldVerificationStatus.VERIFIED)) {
			return FieldVerificationStatus.VERIFIED;
		}
		if (items.stream().anyMatch(item -> item.status() == FieldVerificationStatus.NEEDS_RECHECK)) {
			return FieldVerificationStatus.NEEDS_RECHECK;
		}
		if (items.stream().allMatch(item -> item.status() == FieldVerificationStatus.PLANNED)) {
			return FieldVerificationStatus.PLANNED;
		}
		return FieldVerificationStatus.IN_PROGRESS;
	}

	private static FieldVerificationItem item(
		String id,
		FieldVerificationItemType type,
		String targetName,
		FieldVerificationStatus status
	) {
		return new FieldVerificationItem(id, type, targetName, status, null);
	}
}
