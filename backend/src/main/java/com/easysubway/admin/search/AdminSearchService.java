package com.easysubway.admin.search;

import com.easysubway.admin.navigation.AdminProgram;
import com.easysubway.admin.web.AdminMasterLabelResolver;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.StationWithLines;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 관리자 통합 검색(#1738). 유형별 그룹으로 결과를 돌려준다.
 *
 * <p>권한 필터가 핵심이다: 보이지 않는 프로그램·데이터는 결과에서 제외한다. 메뉴는 이미
 * {@link AdminProgram#visibleTo}가 RBAC로 거른다. 프리픽스 매칭을 앞세운다(한글 초성은 비범위).
 *
 * <p>증분 1은 메뉴(프로그램) 검색. 역·시설·제보·장애·데이터팩 엔티티 검색은 후속 증분에서 각
 * 조회 포트를 권한 필터와 함께 붙인다.
 */
@Service
public class AdminSearchService {

	private static final int MENU_LIMIT = 8;
	private static final int STATION_LIMIT = 6;

	private final TransitMasterQueryUseCase transitMasterQuery;

	public AdminSearchService(TransitMasterQueryUseCase transitMasterQuery) {
		this.transitMasterQuery = transitMasterQuery;
	}

	public List<AdminSearchGroup> search(String query, Authentication authentication) {
		String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return List.of();
		}
		List<AdminSearchGroup> groups = new ArrayList<>();
		List<AdminSearchHit> menuHits = searchMenus(normalized, authentication);
		if (!menuHits.isEmpty()) {
			groups.add(new AdminSearchGroup("menu", "메뉴", menuHits));
		}
		List<AdminSearchHit> stationHits = searchStations(query, authentication);
		if (!stationHits.isEmpty()) {
			groups.add(new AdminSearchGroup("station", "역", stationHits));
		}
		return groups;
	}

	// 역 검색: 역 화면 권한(STATIONS=ADMIN_VIEW)이 없으면 제외한다. 이름(코드)로 표기, 역 상세로 이동.
	private List<AdminSearchHit> searchStations(String query, Authentication authentication) {
		if (!AdminProgram.visibleTo(authentication).contains(AdminProgram.STATIONS)) {
			return List.of();
		}
		try {
			return transitMasterQuery.searchStations(new StationSearchCommand(query.trim(), null)).stream()
				.map(StationWithLines::station)
				.limit(STATION_LIMIT)
				.map(station -> new AdminSearchHit(
					AdminMasterLabelResolver.label(station.nameKo(), station.id()),
					"역",
					"/admin/stations/" + station.id() + "/page"))
				.toList();
		} catch (RuntimeException exception) {
			// 짧은 질의 등으로 검색이 거부되면 역 결과는 비운다(다른 그룹은 그대로).
			return List.of();
		}
	}

	private List<AdminSearchHit> searchMenus(String normalizedQuery, Authentication authentication) {
		return AdminProgram.visibleTo(authentication).stream()
			.filter(program -> matches(program, normalizedQuery))
			.sorted(Comparator
				.comparing((AdminProgram program) ->
					!program.label().toLowerCase(Locale.ROOT).startsWith(normalizedQuery))
				.thenComparing(AdminProgram::label))
			.limit(MENU_LIMIT)
			.map(program -> new AdminSearchHit(program.label(), program.groupLabel(), program.path()))
			.toList();
	}

	private static boolean matches(AdminProgram program, String normalizedQuery) {
		return program.label().toLowerCase(Locale.ROOT).contains(normalizedQuery)
			|| program.groupLabel().toLowerCase(Locale.ROOT).contains(normalizedQuery);
	}
}
