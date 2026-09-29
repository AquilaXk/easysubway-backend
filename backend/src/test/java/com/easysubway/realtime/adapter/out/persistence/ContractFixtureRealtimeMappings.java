package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.domain.RealtimeMapping;
import com.easysubway.realtime.domain.RealtimeTripMapping;
import java.util.List;

/**
 * hub가 발행하는 실시간 API 계약 fixture({@code contracts/api/fixtures/realtime-*.ok.json})는 상록수 4호선 도착의
 * {@code lineId}를 {@code "4"}로 싣는다. gateway는 도착 항목의 lineId가 정규화된 조회의 providerLineId와 같을 때만
 * 조회 노선으로 귀속하므로, 이 계약 예시를 재현하는 web 테스트는 provider_line_id가 {@code "4"}로 등록된
 * 상록수 4호선 매핑(역·trip)을 쓴다.
 */
public final class ContractFixtureRealtimeMappings {

	public static final String PROVIDER_LINE_ID = "4";

	private ContractFixtureRealtimeMappings() {
	}

	public static InMemoryRealtimeMappingPort sangnoksuLine4() {
		return new InMemoryRealtimeMappingPort(
			List.of(new RealtimeMapping(
				"seoul-topis",
				"station-sangnoksu",
				"seoul-4",
				PROVIDER_LINE_ID,
				"1004000448",
				"상록수",
				"4호선",
				true,
				true,
				"OFFICIAL",
				1L
			)),
			List.of(
				new RealtimeTripMapping(
					"seoul-topis", "seoul-4", PROVIDER_LINE_ID, "상행", "당고개 방면", "당고개", "당고개", "", "", "OFFICIAL", 1L
				),
				new RealtimeTripMapping(
					"seoul-topis", "seoul-4", PROVIDER_LINE_ID, "하행", "오이도 방면", "오이도", "오이도", "", "", "OFFICIAL", 1L
				)
			)
		);
	}
}
