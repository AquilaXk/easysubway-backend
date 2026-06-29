package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.transit.adapter.out.persistence.UnavailableTransitMasterRepository;
import com.easysubway.transit.application.service.TransitMasterService;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.StationLayoutSourceType;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

@DisplayName("읽기 전용 마스터 데이터 관리자 화면 모델")
class TransitReadOnlyAdminPageModelTest {

	private static final Principal ADMIN = () -> "admin-user";

	private final UnavailableTransitMasterRepository repository = new UnavailableTransitMasterRepository();
	private final TransitMasterService transitMasterService = new TransitMasterService(
		repository,
		repository,
		Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
	);

	@Test
	@DisplayName("시설 상태 화면은 저장 버튼 비활성화 기준을 전달한다")
	void facilityStatusPageExposesReadOnlyMasterDataFlag() {
		var controller = new TransitFacilityAdminPageController(
			new TransitFacilityStatusAssembler(transitMasterService),
			transitMasterService
		);
		var model = new ExtendedModelMap();

		String viewName = controller.facilitiesPage(null, null, model);

		assertThat(viewName).isEqualTo("admin/facilities/list");
		assertThat(model.get("masterDataWritable")).isEqualTo(false);
	}

	@Test
	@DisplayName("시설 상태 직접 저장 요청은 읽기 전용 오류를 flash로 돌려보낸다")
	void facilityStatusPostRedirectsWithReadOnlyFlash() {
		var controller = new TransitFacilityAdminPageController(
			new TransitFacilityStatusAssembler(transitMasterService),
			transitMasterService
		);
		var redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.updateFacilityStatusFromPage(
			"facility-sangnoksu-elevator-1",
			new TransitFacilityAdminPageController.FacilityStatusForm(AccessibilityFacilityStatus.BROKEN),
			new BeanPropertyBindingResult(new TransitFacilityAdminPageController.FacilityStatusForm(AccessibilityFacilityStatus.BROKEN), "facilityStatusForm"),
			ADMIN,
			redirectAttributes,
			new ExtendedModelMap(),
			new MockHttpServletResponse()
		);

		assertThat(viewName).isEqualTo("redirect:/admin/facilities/page");
		assertReadOnlyFlash(redirectAttributes);
	}

	@Test
	@DisplayName("시설 등록·수정 화면은 저장 버튼 비활성화 기준을 전달한다")
	void facilityEditorPageExposesReadOnlyMasterDataFlag() {
		var controller = new TransitStationAdminPageController(
			transitMasterService,
			transitMasterService,
			mock(DatapackReleaseBlockerSummaryUseCase.class)
		);
		var model = new ExtendedModelMap();

		String viewName = controller.facilityEditorPage("station-sangnoksu", "facility-sangnoksu-elevator-1", model);

		assertThat(viewName).isEqualTo("admin/facilities/editor");
		assertThat(model.get("masterDataWritable")).isEqualTo(false);
	}

	@Test
	@DisplayName("시설 등록·수정 직접 저장 요청은 읽기 전용 오류를 flash로 돌려보낸다")
	void facilityEditorPostRedirectsWithReadOnlyFlash() {
		var controller = new TransitStationAdminPageController(
			transitMasterService,
			transitMasterService,
			mock(DatapackReleaseBlockerSummaryUseCase.class)
		);
		var redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.saveFacilityFromPage(
			new TransitStationAdminPageController.FacilityEditorForm(
				"facility-sangnoksu-elevator-1",
				"station-sangnoksu",
				null,
				AccessibilityFacilityType.ELEVATOR,
				"1번 출구 엘리베이터",
				"B1",
				"1F",
				null,
				null,
				"휠체어 이동 가능",
				AccessibilityFacilityStatus.BROKEN,
				DataConfidenceLevel.HIGH,
				DataSourceType.OFFICIAL_API
			),
			new BeanPropertyBindingResult(new TransitStationAdminPageController.FacilityEditorForm(
				"facility-sangnoksu-elevator-1",
				"station-sangnoksu",
				null,
				AccessibilityFacilityType.ELEVATOR,
				"1번 출구 엘리베이터",
				"B1",
				"1F",
				null,
				null,
				"휠체어 이동 가능",
				AccessibilityFacilityStatus.BROKEN,
				DataConfidenceLevel.HIGH,
				DataSourceType.OFFICIAL_API
			), "facilityForm"),
			ADMIN,
			redirectAttributes,
			new ExtendedModelMap(),
			new MockHttpServletResponse()
		);

		assertThat(viewName)
			.isEqualTo("redirect:/admin/facilities/editor/page?stationId=station-sangnoksu&facilityId=facility-sangnoksu-elevator-1");
		assertReadOnlyFlash(redirectAttributes);
	}

	@Test
	@DisplayName("역 구조도 화면은 저장 버튼 비활성화 기준을 전달한다")
	void stationLayoutPageExposesReadOnlyMasterDataFlag() {
		var controller = new TransitStationLayoutAdminPageController(transitMasterService, transitMasterService);
		var model = new ExtendedModelMap();

		String viewName = controller.stationLayoutsPage("station-sangnoksu", model);

		assertThat(viewName).isEqualTo("admin/stations/layouts");
		assertThat(model.get("masterDataWritable")).isEqualTo(false);
	}

	@Test
	@DisplayName("역 구조도 직접 저장 요청은 읽기 전용 오류를 flash로 돌려보낸다")
	void stationLayoutPostsRedirectWithReadOnlyFlash() {
		var controller = new TransitStationLayoutAdminPageController(transitMasterService, transitMasterService);
		var layoutStatusRedirectAttributes = readOnlyFlash();
		var sourceRedirectAttributes = readOnlyFlash();
		var nodeRedirectAttributes = readOnlyFlash();
		var edgeRedirectAttributes = readOnlyFlash();

		assertStationLayoutReadOnlyRedirect(controller.updateLayoutStatusFromPage(
			"station-sangnoksu",
			"layout-sangnoksu-draft",
			new TransitStationLayoutAdminPageController.LayoutStatusForm(SimplifiedStationLayoutStatus.READY_FOR_REVIEW),
			new BeanPropertyBindingResult(new TransitStationLayoutAdminPageController.LayoutStatusForm(SimplifiedStationLayoutStatus.READY_FOR_REVIEW), "layoutStatusForm"),
			ADMIN,
			layoutStatusRedirectAttributes,
			new ExtendedModelMap(),
			new MockHttpServletResponse()
		), layoutStatusRedirectAttributes);
		assertStationLayoutReadOnlyRedirect(controller.updateStationLayoutSourceFromPage(
			"station-sangnoksu",
			"layout-source-sangnoksu-station-map",
			new TransitStationLayoutAdminPageController.LayoutSourceForm(
				StationLayoutSourceType.OPERATOR_PAGE,
				"상록수역 운영기관 안내 페이지",
				"https://www.seoulmetro.co.kr/station/sangnoksu",
				"운영기관 페이지 확인용",
				true,
				false,
				LocalDate.of(2026, 6, 13),
				LocalDate.of(2026, 6, 14)
			),
			new BeanPropertyBindingResult(new TransitStationLayoutAdminPageController.LayoutSourceForm(
				StationLayoutSourceType.OPERATOR_PAGE,
				"상록수역 운영기관 안내 페이지",
				"https://www.seoulmetro.co.kr/station/sangnoksu",
				"운영기관 페이지 확인용",
				true,
				false,
				LocalDate.of(2026, 6, 13),
				LocalDate.of(2026, 6, 14)
			), "layoutSourceForm"),
			ADMIN,
			sourceRedirectAttributes,
			new ExtendedModelMap(),
			new MockHttpServletResponse()
		), sourceRedirectAttributes);
		assertStationLayoutReadOnlyRedirect(controller.updateRouteNodeDisplayFromPage(
			"station-sangnoksu",
			"node-sangnoksu-elevator-1",
			new TransitStationLayoutAdminPageController.RouteNodeForm(
				132,
				256,
				"1번 출구 승강기",
				"휠체어와 유모차 이동 가능"
			),
			new BeanPropertyBindingResult(new TransitStationLayoutAdminPageController.RouteNodeForm(
				132,
				256,
				"1번 출구 승강기",
				"휠체어와 유모차 이동 가능"
			), "routeNodeForm"),
			ADMIN,
			nodeRedirectAttributes,
			new ExtendedModelMap(),
			new MockHttpServletResponse()
		), nodeRedirectAttributes);
		assertStationLayoutReadOnlyRedirect(controller.updateRouteEdgeFromPage(
			"station-sangnoksu",
			"edge-sangnoksu-elevator-to-faregate",
			new TransitStationLayoutAdminPageController.RouteEdgeForm(
				34,
				90,
				false,
				true,
				false,
				1,
				5,
				88,
				true
			),
			new BeanPropertyBindingResult(new TransitStationLayoutAdminPageController.RouteEdgeForm(
				34,
				90,
				false,
				true,
				false,
				1,
				5,
				88,
				true
			), "routeEdgeForm"),
			ADMIN,
			edgeRedirectAttributes,
			new ExtendedModelMap(),
			new MockHttpServletResponse()
		), edgeRedirectAttributes);
	}

	private static RedirectAttributesModelMap readOnlyFlash() {
		return new RedirectAttributesModelMap();
	}

	private static void assertStationLayoutReadOnlyRedirect(
		String viewName,
		RedirectAttributesModelMap redirectAttributes
	) {
		assertThat(viewName).isEqualTo("redirect:/admin/stations/station-sangnoksu/layouts/page");
		assertReadOnlyFlash(redirectAttributes);
	}

	private static void assertReadOnlyFlash(RedirectAttributesModelMap redirectAttributes) {
		assertThat(redirectAttributes.getFlashAttributes().get("masterDataError"))
			.isEqualTo("운영 마스터 데이터가 읽기 전용입니다.");
	}
}
