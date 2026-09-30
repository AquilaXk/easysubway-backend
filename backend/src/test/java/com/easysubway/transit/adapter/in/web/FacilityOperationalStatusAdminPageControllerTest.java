package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort.BundleElevatorFacility;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityStatusSource;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 엘리베이터 가동 상태 확인 기록")
class FacilityOperationalStatusAdminPageControllerTest {

	private static final String PAGE = "/admin/reports/elevator-status/page";
	private static final String VERIFY = "/admin/reports/elevator-status/page/verify";
	private static final String EXIT_1 = "smrt-elev:0201:2:1번 출입구";
	private static final String EXIT_1_NAME = "가역 엘리베이터 1번 출입구";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FacilityOperationalStatusStore store;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@MockitoBean
	private LoadBundleElevatorFacilitiesPort bundleFacilities;

	@BeforeEach
	void setUp() {
		new JdbcTemplate(dataSource).update("DELETE FROM facility_operational_status");
		when(bundleFacilities.loadActiveBundleElevatorFacilities())
			.thenReturn(Optional.of(List.of(new BundleElevatorFacility(EXIT_1, EXIT_1_NAME))));
	}

	@Test
	@DisplayName("제보 확인 대기열에서 이동한 화면은 번들 smrt-elev 목록에서 시설을 고르게 한다")
	void pageListsBundleElevatorFacilitiesFromTheReportQueue() throws Exception {
		String queue = adminHtml("/admin/reports/page", new MockHttpSession());
		String page = adminHtml(PAGE, new MockHttpSession());

		assertThat(queue).contains(PAGE);
		assertThat(page)
			.contains("엘리베이터 가동 상태 확인 기록")
			.contains("<option value=\"" + EXIT_1 + "\">" + EXIT_1_NAME + " (" + EXIT_1 + ")</option>")
			.contains("value=\"OPERATING\"")
			.contains("value=\"OUT_OF_SERVICE\"")
			.contains("name=\"commandToken\"");
	}

	@Test
	@DisplayName("관리자가 확인한 상태를 ADMIN_VERIFIED로 기록하고 감사 이력을 남긴다")
	void authorizedAdminRecordsVerifiedState() throws Exception {
		Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

		mockMvc.perform(verify(EXIT_1, "OUT_OF_SERVICE").with(httpBasic("admin-test", "admin-test-password")).with(commandToken()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", PAGE));

		assertThat(store.loadStatuses()).singleElement().satisfies(row -> {
			assertThat(row.facilityId()).isEqualTo(EXIT_1);
			assertThat(row.status()).isEqualTo(FacilityOperationalState.OUT_OF_SERVICE);
			assertThat(row.source()).isEqualTo(FacilityStatusSource.ADMIN_VERIFIED);
			assertThat(row.observedAt()).isAfterOrEqualTo(before);
		});
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 5))
			.anySatisfy(event -> {
				assertThat(event.targetType()).isEqualTo("FACILITY_OPERATIONAL_STATUS");
				assertThat(event.targetId()).isEqualTo(EXIT_1);
				assertThat(event.action()).isEqualTo("RECORD_ADMIN_VERIFIED");
				assertThat(event.outcome()).isEqualTo(AdminAuditOutcome.SUCCESS);
				assertThat(event.reason()).isEqualTo("OUT_OF_SERVICE");
			});
	}

	@Test
	@DisplayName("제보 검수 권한만 있어도 기록할 수 있다")
	void reportReviewerRecordsVerifiedState() throws Exception {
		RequestPostProcessor reviewer = user("report-reviewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.REPORT_REVIEW.authority()));
		MockHttpSession session = new MockHttpSession();
		String token = commandTokenFrom(mockMvc.perform(get(PAGE).session(session).with(reviewer))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		mockMvc.perform(verify(EXIT_1, "OPERATING").session(session).with(reviewer).param("commandToken", token))
			.andExpect(status().is3xxRedirection());

		assertThat(store.loadStatuses()).extracting(FacilityOperationalStatus::status)
			.containsExactly(FacilityOperationalState.OPERATING);
	}

	@Test
	@DisplayName("익명은 401, 관리자 권한·제보 검수 권한이 없으면 403, CSRF 없으면 403이고 아무것도 기록하지 않는다")
	void unauthorizedRequestsAreRejected() throws Exception {
		RequestPostProcessor viewer = user("viewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.ADMIN_VIEW.authority()));

		mockMvc.perform(get(PAGE)).andExpect(status().isUnauthorized());
		mockMvc.perform(verify(EXIT_1, "OUT_OF_SERVICE")).andExpect(status().isUnauthorized());
		mockMvc.perform(get(PAGE).with(httpBasic("basic-user", "user-test-password"))).andExpect(status().isForbidden());
		mockMvc.perform(verify(EXIT_1, "OUT_OF_SERVICE").with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(PAGE).with(viewer)).andExpect(status().isForbidden());
		mockMvc.perform(verify(EXIT_1, "OUT_OF_SERVICE").with(viewer)).andExpect(status().isForbidden());
		mockMvc.perform(post(VERIFY)
				.with(httpBasic("admin-test", "admin-test-password"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("facilityId", EXIT_1)
				.param("state", "OUT_OF_SERVICE"))
			.andExpect(status().isForbidden());

		assertThat(store.loadStatuses()).isEmpty();
	}

	@Test
	@DisplayName("정규 smrt-elev id가 아니거나 상태가 없거나 알 수 없는 상태 값이면 400이고 기록하지 않는다")
	void nonCanonicalIdentifierOrMissingStateIsRejected() throws Exception {
		String html = mockMvc.perform(verify("smrt-elev:0201:2:대합실", "OUT_OF_SERVICE")
				.with(httpBasic("admin-test", "admin-test-password")).with(commandToken()))
			.andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		mockMvc.perform(post(VERIFY)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("facilityId", EXIT_1))
			.andExpect(status().isBadRequest());
		mockMvc.perform(verify(EXIT_1, "BROKEN")
				.with(httpBasic("admin-test", "admin-test-password")).with(commandToken()))
			.andExpect(status().isBadRequest());

		assertThat(html).contains("번들 엘리베이터 목록에서 시설을 골라 주세요");
		assertThat(store.loadStatuses()).isEmpty();
	}

	@Test
	@DisplayName("번들 목록에 없는 시설 id는 400이고 기록하지 않는다")
	void facilityMissingFromTheBundleListIsRejected() throws Exception {
		mockMvc.perform(verify("smrt-elev:0201:2:7번 출입구", "OUT_OF_SERVICE")
				.with(httpBasic("admin-test", "admin-test-password")).with(commandToken()))
			.andExpect(status().isBadRequest());

		assertThat(store.loadStatuses()).isEmpty();
	}

	@Test
	@DisplayName("활성 번들 목록을 읽을 수 없으면 화면과 기록 모두 503으로 드러내고 기록하지 않는다")
	void unavailableBundleListIsExplicit() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = commandTokenFrom(adminHtml(PAGE, session));
		when(bundleFacilities.loadActiveBundleElevatorFacilities()).thenReturn(Optional.empty());

		String page = mockMvc.perform(get(PAGE).with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isServiceUnavailable())
			.andReturn().getResponse().getContentAsString();
		mockMvc.perform(verify(EXIT_1, "OUT_OF_SERVICE").session(session)
				.with(httpBasic("admin-test", "admin-test-password")).param("commandToken", token))
			.andExpect(status().isServiceUnavailable());

		assertThat(page).contains("활성 경로 번들의 엘리베이터 목록을 불러올 수 없습니다").doesNotContain("<option value=\"smrt-elev");
		assertThat(store.loadStatuses()).isEmpty();
	}

	@Test
	@DisplayName("확인 시각보다 새 원천 관측이 이미 있으면 409로 알리고 원천 값을 그대로 둔다")
	void newerObservationConflicts() throws Exception {
		Instant future = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
		store.applyFeedCollection(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED,
			List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")), future);

		String html = mockMvc.perform(verify(EXIT_1, "OUT_OF_SERVICE")
				.with(httpBasic("admin-test", "admin-test-password")).with(commandToken()))
			.andExpect(status().isConflict())
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("더 최근 관측");
		assertThat(store.loadStatuses()).singleElement()
			.extracting(FacilityOperationalStatus::source).isEqualTo(FacilityStatusSource.SEOUL_METRO_FEED);
	}

	@Test
	@DisplayName("미검증 시민 제보는 운영 상태 테이블에 쓰지 않는다")
	void unverifiedCitizenReportDoesNotWriteOperationalStatus() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터가 멈췄어요"
					}
					"""))
			.andExpect(status().isCreated());

		assertThat(store.loadStatuses()).isEmpty();
	}

	private static MockHttpServletRequestBuilder verify(String facilityId, String state) {
		return post(VERIFY)
			.with(csrf())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("facilityId", facilityId)
			.param("state", state);
	}

	private String adminHtml(String path, MockHttpSession session) throws Exception {
		return mockMvc.perform(get(path).session(session).with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private RequestPostProcessor commandToken() {
		return request -> {
			MockHttpSession session = sessionFrom(request);
			try {
				request.setSession(session);
				request.addParameter("commandToken", commandTokenFrom(adminHtml(PAGE, session)));
				return request;
			} catch (Exception exception) {
				throw new AssertionError(exception);
			}
		};
	}

	private static MockHttpSession sessionFrom(MockHttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session instanceof MockHttpSession mockHttpSession) {
			return mockHttpSession;
		}
		return new MockHttpSession();
	}

	private static String commandTokenFrom(String html) {
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
