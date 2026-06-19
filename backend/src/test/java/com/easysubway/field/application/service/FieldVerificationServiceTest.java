package com.easysubway.field.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.common.error.ResourceNotFoundException;
import com.easysubway.field.adapter.out.persistence.InMemoryFieldVerificationChangeHistoryRepository;
import com.easysubway.field.adapter.out.persistence.InMemoryFieldVerificationSessionRepository;
import com.easysubway.field.application.port.in.UpdateFieldVerificationItemStatusCommand;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("현장 검증 기준선")
class FieldVerificationServiceTest {

	private final InMemoryFieldVerificationChangeHistoryRepository historyRepository =
		new InMemoryFieldVerificationChangeHistoryRepository();
	private final InMemoryFieldVerificationSessionRepository sessionRepository =
		new InMemoryFieldVerificationSessionRepository();
	private final FieldVerificationService service = new FieldVerificationService(sessionRepository, historyRepository);

	@Test
	@DisplayName("상록수역 필수 현장 검증 항목을 조회한다")
	void getsSangnoksuFieldVerificationBaseline() {
		var session = service.getStationVerification("station-sangnoksu");

		assertThat(session.id()).isEqualTo("field-verification-sangnoksu-2026-06");
		assertThat(session.stationId()).isEqualTo("station-sangnoksu");
		assertThat(session.stationName()).isEqualTo("상록수역");
		assertThat(session.verifiedAt()).isEqualTo(LocalDate.of(2026, 6, 19));
		assertThat(session.verifiedBy()).isEqualTo("field-team");
		assertThat(session.status()).isEqualTo(FieldVerificationStatus.IN_PROGRESS);
		assertThat(session.items())
			.extracting(item -> item.type())
			.containsExactly(
				FieldVerificationItemType.EXIT,
				FieldVerificationItemType.ELEVATOR,
				FieldVerificationItemType.ESCALATOR,
				FieldVerificationItemType.RESTROOM,
				FieldVerificationItemType.PLATFORM_TRANSFER
			);
		assertThat(session.items())
			.extracting(item -> item.status())
			.containsOnly(FieldVerificationStatus.PLANNED, FieldVerificationStatus.VERIFIED);
	}

	@Test
	@DisplayName("사당역 필수 현장 검증 항목을 조회한다")
	void getsSadangFieldVerificationBaseline() {
		var session = service.getStationVerification("station-sadang");

		assertThat(session.id()).isEqualTo("field-verification-sadang-2026-06");
		assertThat(session.stationId()).isEqualTo("station-sadang");
		assertThat(session.stationName()).isEqualTo("사당역");
		assertThat(session.verifiedAt()).isEqualTo(LocalDate.of(2026, 6, 19));
		assertThat(session.verifiedBy()).isEqualTo("field-team");
		assertThat(session.status()).isEqualTo(FieldVerificationStatus.PLANNED);
		assertThat(session.note()).isEqualTo("주요 환승역 현장 검증 확대 기준선");
		assertThat(session.items())
			.extracting(item -> item.type())
			.containsExactly(
				FieldVerificationItemType.EXIT,
				FieldVerificationItemType.ELEVATOR,
				FieldVerificationItemType.ESCALATOR,
				FieldVerificationItemType.RESTROOM,
				FieldVerificationItemType.PLATFORM_TRANSFER
			);
		assertThat(session.items())
			.extracting(item -> item.status())
			.containsOnly(FieldVerificationStatus.PLANNED);
	}

	@Test
	@DisplayName("현장 검증 대상 세션 목록을 조회한다")
	void listsFieldVerificationBaselines() {
		var sessions = service.listStationVerifications();

		assertThat(sessions)
			.extracting(session -> session.stationId())
			.containsExactly("station-sangnoksu", "station-sadang");
		assertThat(sessions)
			.extracting(session -> session.status())
			.containsExactly(FieldVerificationStatus.IN_PROGRESS, FieldVerificationStatus.PLANNED);
	}

	@Test
	@DisplayName("현장 검증 항목 상태와 비고를 변경한다")
	void updatesFieldVerificationItemStatus() {
		var session = service.updateItemStatus(new UpdateFieldVerificationItemStatusCommand(
			"station-sadang",
			"field-verification-sadang-elevator",
			FieldVerificationStatus.NEEDS_RECHECK,
			"엘리베이터 운행 중지 안내문 확인 필요",
			"admin-user"
		));

		assertThat(session.stationId()).isEqualTo("station-sadang");
		assertThat(session.status()).isEqualTo(FieldVerificationStatus.NEEDS_RECHECK);
		assertThat(session.items())
			.filteredOn(item -> item.id().equals("field-verification-sadang-elevator"))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.status()).isEqualTo(FieldVerificationStatus.NEEDS_RECHECK);
				assertThat(item.note()).isEqualTo("엘리베이터 운행 중지 안내문 확인 필요");
			});
		assertThat(sessionRepository.findByStationId("station-sadang"))
			.get()
			.extracting(stored -> stored.status())
			.isEqualTo(FieldVerificationStatus.NEEDS_RECHECK);
	}

	@Test
	@DisplayName("현장 검증 항목 상태 변경 이력을 최신순으로 조회한다")
	void listsFieldVerificationItemChangeHistory() {
		service.updateItemStatus(new UpdateFieldVerificationItemStatusCommand(
			"station-sadang",
			"field-verification-sadang-elevator",
			FieldVerificationStatus.NEEDS_RECHECK,
			"엘리베이터 운행 중지 안내문 확인 필요",
			"admin-user"
		));
		service.updateItemStatus(new UpdateFieldVerificationItemStatusCommand(
			"station-sadang",
			"field-verification-sadang-restroom",
			FieldVerificationStatus.VERIFIED,
			"화장실 위치 확인 완료",
			"second-admin"
		));

		var histories = service.listStationChangeHistory("station-sadang");

		assertThat(histories)
			.extracting(FieldVerificationChangeHistory::itemId)
			.containsExactly(
				"field-verification-sadang-restroom",
				"field-verification-sadang-elevator"
			);
		assertThat(histories.get(0)).satisfies(history -> {
			assertThat(history.sessionId()).isEqualTo("field-verification-sadang-2026-06");
			assertThat(history.stationId()).isEqualTo("station-sadang");
			assertThat(history.previousStatus()).isEqualTo(FieldVerificationStatus.PLANNED);
			assertThat(history.newStatus()).isEqualTo(FieldVerificationStatus.VERIFIED);
			assertThat(history.previousNote()).isNull();
			assertThat(history.newNote()).isEqualTo("화장실 위치 확인 완료");
			assertThat(history.changedBy()).isEqualTo("second-admin");
			assertThat(history.changedAt()).isNotNull();
		});
	}

	@Test
	@DisplayName("존재하지 않는 역의 현장 검증 변경 이력 조회는 실패한다")
	void listMissingStationChangeHistoryFails() {
		assertThatThrownBy(() -> service.listStationChangeHistory("missing-station"))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("현장 검증 기준선을 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("서로 다른 역의 첫 변경 이력도 고유한 식별자를 가진다")
	void changeHistoryIdsAreUniqueAcrossStations() {
		service.updateItemStatus(new UpdateFieldVerificationItemStatusCommand(
			"station-sadang",
			"field-verification-sadang-elevator",
			FieldVerificationStatus.VERIFIED,
			"사당역 엘리베이터 확인 완료",
			"admin-user"
		));
		service.updateItemStatus(new UpdateFieldVerificationItemStatusCommand(
			"station-sangnoksu",
			"field-verification-sangnoksu-restroom",
			FieldVerificationStatus.NEEDS_RECHECK,
			"상록수역 화장실 위치 재확인 필요",
			"admin-user"
		));

		String sadangHistoryId = service.listStationChangeHistory("station-sadang").get(0).id();
		String sangnoksuHistoryId = service.listStationChangeHistory("station-sangnoksu").get(0).id();

		assertThat(sadangHistoryId).isNotEqualTo(sangnoksuHistoryId);
	}

	@Test
	@DisplayName("존재하지 않는 현장 검증 항목 상태 변경은 실패한다")
	void updateMissingFieldVerificationItemStatusFails() {
		assertThatThrownBy(() -> service.updateItemStatus(new UpdateFieldVerificationItemStatusCommand(
			"station-sadang",
			"missing-item",
			FieldVerificationStatus.VERIFIED,
			"확인 완료",
			"admin-user"
		)))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("현장 검증 항목을 찾을 수 없습니다.");
	}
}
