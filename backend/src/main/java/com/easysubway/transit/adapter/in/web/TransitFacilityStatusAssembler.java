package com.easysubway.transit.adapter.in.web;

import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class TransitFacilityStatusAssembler {

	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	TransitFacilityStatusAssembler(TransitMasterQueryUseCase transitMasterQueryUseCase) {
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	List<FacilityStatusRow> assemble() {
		// 관리자 화면과 API는 같은 기준으로 역별 시설 상태 행을 만든다.
		return transitMasterQueryUseCase.searchStations(new StationSearchCommand(null, null))
			.stream()
			.flatMap(station -> transitMasterQueryUseCase.listStationFacilities(station.station().id())
				.stream()
				.map(facility -> FacilityStatusRow.from(station, facility)))
			.toList();
	}
}
