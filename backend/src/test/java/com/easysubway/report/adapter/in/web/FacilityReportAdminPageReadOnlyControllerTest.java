package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.common.web.WebMessageResolver;
import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportRepository;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.service.FacilityReportService;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.transit.adapter.out.persistence.UnavailableTransitMasterRepository;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

@DisplayName("읽기 전용 마스터 데이터 신고 검수 화면")
class FacilityReportAdminPageReadOnlyControllerTest {

	private static final Principal ADMIN = () -> "admin-user";

	@Test
	@DisplayName("시설 상태 반영이 필요한 신고 승인은 읽기 전용 오류를 flash로 돌려보낸다")
	void reportReviewPostRedirectsWithReadOnlyFlash() {
		var transitRepository = new UnavailableTransitMasterRepository();
		var reportRepository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			transitRepository,
			transitRepository,
			reportRepository,
			reportRepository,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var controller = new FacilityReportAdminPageController(
			service,
			objectKey -> java.util.Optional.empty(),
			WebMessageResolver.defaultMessages(),
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var report = service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-read-only",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"읽기 전용 마스터 데이터에서 승인할 수 없는 신고입니다.",
			null,
			null,
			null,
			null,
			null
		));
		var redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.reviewReportFromPage(
			report.id(),
			FacilityReportReviewDecision.ACCEPT,
			null,
			ADMIN,
			redirectAttributes
		);

		assertThat(viewName).isEqualTo("redirect:/admin/reports/%s/page".formatted(report.id()));
		assertThat(redirectAttributes.getFlashAttributes().get("masterDataError"))
			.isEqualTo("운영 마스터 데이터가 읽기 전용입니다.");
	}
}
