package com.easysubway.notification.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.notification.application.port.in.NotificationPreferenceUseCase;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.domain.DevicePlatform;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 푸시 알림 현황 페이지")
class PushNotificationAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NotificationPreferenceUseCase notificationPreferenceUseCase;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("관리자는 푸시 알림 outbox의 전체와 상태별 건수를 확인한다")
	void adminGetsPushNotificationDashboardPage() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");
		deliverPendingNotifications();
		dispatchNotification("FAVORITE_STATION_FACILITY", "시설 변경 알림");

		String html = mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("푸시 알림 현황")
			.contains("전체 알림")
			.contains(">2<")
			.contains("대기 중")
			.contains("발송 완료")
			.contains("발송 실패")
			.contains("발송 시도")
			.contains("발송 성공률")
			.contains("발송 실패율")
			.contains("0.0%")
			.contains("100.0%")
			.contains("점검 필요")
			.contains("아직 발송 처리 전")
			.contains("외부 발송 성공")
			.contains("발송 어댑터 실패 또는 예외")
			.contains("최근 실패: 외부 푸시 발송 어댑터가 설정되지 않았습니다.")
			.doesNotContain("secret-device-token");
	}

	@Test
	@DisplayName("푸시 화면은 실패 분석 추이 차트·증감 카드·기간 버튼을 렌더링한다")
	void pushPageRendersFailureTrendChart() throws Exception {
		String html = mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("id=\"push-trends\"")
			.contains("추이 기간 선택")
			.contains("/admin/notifications/push/trends")
			.contains("전 기간 대비 증감")
			.contains("push-trend-canvas")
			.contains("발송 시도·실패")
			.contains("/js/admin/dashboard-charts.js")
			// admin/fragments/trend-chart-table :: table(trendChart) no-JS 대체 표 렌더 계약(#2349).
			.contains("class=\"static-table\"")
			.contains("<th scope=\"col\">날짜</th>")
			.contains("<th scope=\"col\">푸시 시도</th>")
			.contains("<th scope=\"col\">푸시 실패</th>");
	}

	@Test
	@DisplayName("기간 추이 fragment는 셸 없이 추이 영역만 반환한다")
	void pushTrendsFragmentReturnsSectionOnly() throws Exception {
		String fragment = mockMvc.perform(get("/admin/notifications/push/trends")
				.param("days", "30")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("id=\"push-trends\"")
			.contains("최근 30일 추이")
			.doesNotContain("admin-shell")
			.doesNotContain("상태별 알림");
	}

	@Test
	@DisplayName("발송 이력은 수신자 식별자를 마스킹하고 원문 토큰·사용자ID를 노출하지 않는다")
	void pushHistoryMasksRecipientIdentifiers() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");

		String html = mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("발송 이력")
			.contains("신고 처리 알림")
			.contains("제보 처리 상태")
			.contains("••••")
			.doesNotContain("secret-device-token")
			.doesNotContain("anonymous-user-1");
	}

	@Test
	@DisplayName("발송 이력 조회는 열람 감사(PRIVACY_READ)를 남긴다")
	void pushHistoryViewWritesPrivacyReadAudit() throws Exception {
		mockMvc.perform(get("/admin/notifications/push/page/history")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk());

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 5))
			.anySatisfy(event -> {
				assertThat(event.action()).isEqualTo("VIEW_PUSH_HISTORY");
				assertThat(event.targetType()).isEqualTo("PUSH_NOTIFICATION_HISTORY");
			});
	}

	@Test
	@DisplayName("발송 이력은 실패 사유별 분해 막대와 사유 드릴다운 링크를 렌더링한다")
	void pushHistoryRendersFailureBreakdownWithDrilldown() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");
		deliverPendingNotifications();

		String html = mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("실패 사유별 분해")
			.contains("외부 푸시 발송 어댑터가 설정되지 않았습니다")
			.contains("reason=");
	}

	@Test
	@DisplayName("사유 드릴다운은 해당 사유의 실패만 목록에 남기고 분해 건수와 정합한다")
	void pushHistoryDrilldownFiltersByReason() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");
		deliverPendingNotifications();

		String fragment = mockMvc.perform(get("/admin/notifications/push/page/history")
				.param("reason", "외부 푸시 발송 어댑터가 설정되지 않았습니다.")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("신고 처리 알림")
			.contains("전체 <strong>1</strong>")
			.contains("해제");
	}

	@Test
	@DisplayName("발송 이력 fragment는 상태 필터를 적용하고 셸 없이 목록만 반환한다")
	void pushHistoryFragmentFiltersByStatus() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "대기 중 알림");

		String fragment = mockMvc.perform(get("/admin/notifications/push/page/history")
				.param("status", "FAILED")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("id=\"push-history\"")
			.contains("조건에 맞는 발송 이력이 없습니다.")
			.doesNotContain("admin-shell")
			.doesNotContain("대기 중 알림");
	}

	@Test
	@DisplayName("실패가 있으면 실패 경고에 실패 이력 필터 딥링크를 노출한다")
	void pushPageShowsFailureDeepLinkWhenFailuresExist() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");
		deliverPendingNotifications();

		String html = mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("실패 발송 목록 보기")
			.contains("status=FAILED");
	}

	@Test
	@DisplayName("재발송은 command token 없이는 차단된다(중복·위조 방지)")
	void resendRequiresCommandToken() throws Exception {
		mockMvc.perform(post("/admin/notifications/push/resend")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("notificationIds", "push-x")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("실패 건 재발송은 command token으로 처리하고 결과 토스트·감사를 남긴다")
	void resendFailedNotificationTogglesToPendingAndAudits() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");
		deliverPendingNotifications();

		MockHttpSession session = new MockHttpSession();
		String html = mockMvc.perform(get("/admin/notifications/push/page")
				.session(session)
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		// 실패 행의 재발송 체크박스 값과 폼의 command token을 페이지에서 뽑아 no-JS 폼처럼 제출한다.
		assertThat(html).contains("최대 <strong>50</strong>건");
		String token = extract(html, "name=\"commandToken\" value=\"([^\"]+)\"");
		String notificationId = extract(html, "name=\"notificationIds\" value=\"([^\"]+)\"");

		mockMvc.perform(post("/admin/notifications/push/resend")
				.session(session)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("notificationIds", notificationId)
				.param("commandToken", token)
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().is3xxRedirection());

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 10))
			.anySatisfy(event -> {
				assertThat(event.action()).isEqualTo("RESEND_PUSH");
				assertThat(event.targetType()).isEqualTo("PUSH_NOTIFICATION_RESEND");
			});
	}

	private static String extract(String html, String regex) {
		Matcher matcher = Pattern.compile(regex).matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	@Test
	@DisplayName("푸시 알림 현황 페이지는 관리자 인증을 요구한다")
	void pushNotificationDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/notifications/push/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	private void registerDevice() {
		notificationPreferenceUseCase.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"secret-device-token"
		));
	}

	private void dispatchNotification(String type, String title) throws Exception {
		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "type": "%s",
					  "title": "%s",
					  "body": "상록수역 시설 상태를 확인하세요."
					}
					""".formatted(type, title)))
			.andExpect(status().isOk());
	}

	private void deliverPendingNotifications() throws Exception {
		mockMvc.perform(post("/admin/notifications/push/deliveries")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1"
					}
					"""))
			.andExpect(status().isOk());
	}
}
