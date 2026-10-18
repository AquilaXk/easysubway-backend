package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * JVM 기본 시간대가 UTC가 아니어도 응답 시각이 같은 순간을 가리키는지 확인한다(이슈 #534).
 *
 * <p>컨텍스트를 기본 시간대 변경 뒤에 만들어야 {@code Clock}이 기본 시간대를 읽는 회귀를 잡는다.
 * 그래서 {@code @BeforeAll}에서 시간대를 바꾸고, 다른 테스트가 만든 컨텍스트를 재사용하지 않도록
 * 고유한 속성을 주며, 끝나면 이 컨텍스트를 폐기한다.
 */
@SpringBootTest(properties = {
	"easysubway.test.default-time-zone=Asia/Seoul",
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ResourceLock(Resources.TIME_ZONE)
@DisplayName("응답 시각의 시간대 독립성")
class ResponseTimestampTimeZoneIndependenceTest {

	private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(2);
	private static TimeZone originalDefaultZone;

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ServiceNoticeRepository noticeRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void useSeoulDefaultZone() {
		originalDefaultZone = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
	}

	@AfterAll
	static void restoreDefaultZone() {
		TimeZone.setDefault(originalDefaultZone);
	}

	@Test
	@DisplayName("제보 접수 시각은 기본 시간대가 KST여도 offset 있는 현재 UTC 순간이다")
	void reportCreatedAtIsAnOffsetInstant() throws Exception {
		Instant before = Instant.now();
		String body = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "timezone-independence-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		String createdAt = JsonPath.read(body, "$.data.createdAt");

		assertThat(OffsetDateTime.parse(createdAt).toInstant())
			.as("createdAt=%s", createdAt)
			.isBetween(before.minus(CLOCK_TOLERANCE), Instant.now().plus(CLOCK_TOLERANCE));
	}

	@Test
	@DisplayName("제보 상태 조회도 같은 접수 시각을 offset과 함께 돌려준다")
	void reportStatusKeepsTheSameInstant() throws Exception {
		String created = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "timezone-independence-2",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String reportId = JsonPath.read(created, "$.data.id");
		String receiptToken = JsonPath.read(created, "$.data.receiptToken");

		String status = mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.header("X-Easysubway-Report-Receipt-Token", receiptToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(OffsetDateTime.parse(JsonPath.read(status, "$.data.createdAt")).toInstant())
			.isEqualTo(OffsetDateTime.parse(JsonPath.read(created, "$.data.createdAt")).toInstant());
	}

	@Test
	@DisplayName("공지 발행·만료 시각은 저장한 UTC 순간을 offset과 함께 돌려준다")
	void noticeTimestampsAreOffsetInstants() throws Exception {
		jdbcTemplate.update("DELETE FROM service_notice");
		LocalDateTime publishedAt = LocalDateTime.now(Clock.systemUTC()).minusHours(1);
		LocalDateTime expiresAt = publishedAt.plusHours(5);
		noticeRepository.save(new ServiceNotice("tz-notice", ServiceNoticeScope.ALL, null,
			"전체 공지", "본문", ServiceNoticeSeverity.INFO, publishedAt, expiresAt, "operator-a"));

		String body = mockMvc.perform(get("/api/notices/active"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(OffsetDateTime.parse(JsonPath.read(body, "$.data[0].publishedAt")).toInstant())
			.isEqualTo(publishedAt.toInstant(ZoneOffset.UTC));
		assertThat(OffsetDateTime.parse(JsonPath.read(body, "$.data[0].expiresAt")).toInstant())
			.isEqualTo(expiresAt.toInstant(ZoneOffset.UTC));
	}
}
