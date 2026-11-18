package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 응답 JSON에 나가는 시각은 offset이 있는 ISO-8601이어야 한다는 계약(이슈 #534).
 *
 * <p>{@code LocalDateTime}은 시간대 없이 직렬화되어, 받는 쪽이 어느 시간대의 벽시계 시각인지
 * 추측해야 한다. 사건 발생 시각(접수·발행·갱신 시각)은 {@link Instant}로 내보낸다.
 * 지역 의미가 있는 값(운행일, 업무 일자)은 {@link LocalDate}로 두며 이 계약의 대상이 아니다.
 */
@DisplayName("응답 시각 필드 계약")
class ApiResponseTimestampContractTest {

	private static final String BASE_PACKAGE = "com.easysubway";

	/**
	 * 본문 구조를 따라갈 수 없어(Map·JsonNode·와일드카드) 사람이 시각 필드가 없거나 이미 offset을
	 * 갖는다고 확인한 엔드포인트. 새 엔드포인트가 여기에 걸리면 같은 확인을 하고 추가한다.
	 */
	private static final Set<String> REVIEWED_UNTYPED_BODIES = Set.of(
		// 시각 필드 없음: 상태 코드와 재전송 여부만 내려간다.
		"DatapackReleaseCallbackApiController#receiveCallback",
		// 시각 필드 없음: release-request 스키마 필드는 해시·식별자뿐이다.
		"DatapackReleaseRequestApiController#get",
		// approvedAt은 ISO_OFFSET_DATE_TIME, UTC 고정(DatapackRollbackApprovalApiControllerTest).
		"DatapackRollbackApprovalApiController#get",
		// Journey V3 계약: Instant 또는 offset 있는 문자열만 쓴다.
		"JourneyActivationController#activate",
		"JourneyBenchmarkObservationController#observe",
		"JourneyCandidateCanaryController#canary",
		"JourneyProfileController#profile",
		"JourneyReadinessController#active",
		"JourneyReadinessController#candidate",
		// 열차 검색 계약: departureAt·arrivalAt·observedAt은 OffsetDateTime(train-api.openapi.yaml).
		"TrainSearchContractController#search",
		"TrainSearchContractController#stations");

	@Test
	@DisplayName("응답 DTO는 시간대 없는 LocalDateTime·Date를 내보내지 않는다")
	void responseTypesHaveNoZonelessTimestamps() {
		Map<String, Class<?>> zoneless = ResponseTemporalFieldScanner.scan(BASE_PACKAGE).temporalFields().entrySet()
			.stream()
			.filter(entry -> !isZoneBearingOrDateOnly(entry.getValue()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(zoneless)
			.as("사건 발생 시각은 Instant로 내보낸다. 지역 업무 일자는 LocalDate를 쓴다")
			.isEmpty();
	}

	@Test
	@DisplayName("스캐너는 이미 Instant인 응답 필드를 찾아내므로 빈 결과로 통과하지 않는다")
	void scannerFindsKnownInstantField() {
		assertThat(ResponseTemporalFieldScanner.scan(BASE_PACKAGE).temporalFields())
			.containsEntry("ads.adapter.in.web.AdPublicController$AdResponse.endsAt", Instant.class);
	}

	@Test
	@DisplayName("본문 구조를 알 수 없는 엔드포인트는 사람이 확인한 목록과 같다")
	void untypedBodiesAreReviewed() {
		Set<String> untypedEndpoints = ResponseTemporalFieldScanner.scan(BASE_PACKAGE).untypedBodies().stream()
			.map(entry -> entry.substring(0, entry.indexOf(" -> ")))
			.collect(Collectors.toSet());

		assertThat(untypedEndpoints).isEqualTo(REVIEWED_UNTYPED_BODIES);
	}

	private static boolean isZoneBearingOrDateOnly(Class<?> type) {
		return type == Instant.class || type == OffsetDateTime.class || type == LocalDate.class
			|| type == LocalTime.class;
	}
}
