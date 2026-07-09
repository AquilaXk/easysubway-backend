package com.easysubway.ads.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("무추적 광고 공개 API")
class AdPublicControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM ad_event_daily");
		jdbcTemplate.update("DELETE FROM ad_creatives");
		jdbcTemplate.update("DELETE FROM ad_placements");
	}

	@Test
	@DisplayName("활성 소재 1개만 식별자 없이 반환하고 public max-age=300 캐시를 붙인다")
	void returnsActiveCreativeWithoutIdentifiers() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		insertPlacement("route-result-bottom");
		insertCreative("active", "route-result-bottom", now.minusHours(1), now.plusHours(1), true);
		insertCreative("expired", "route-result-bottom", now.minusHours(3), now.minusHours(1), true);
		insertCreative("disabled", "route-result-bottom", now.minusHours(1), now.plusHours(1), false);

		MvcResult first = mockMvc.perform(get("/api/ads/active")
				.param("placement", "route-result-bottom"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", containsString("max-age=300")))
			.andExpect(header().string("Cache-Control", containsString("public")))
			.andExpect(header().exists("ETag"))
			.andExpect(jsonPath("$.data.placement").value("route-result-bottom"))
			.andExpect(jsonPath("$.data.creativeId").value("active"))
			.andExpect(jsonPath("$.data.imageUrl").value("https://cdn.easysubway.example/ads/active.png"))
			.andExpect(jsonPath("$.data.landingUrl").value("https://partner.example/active"))
			.andExpect(jsonPath("$.data.advertiserName").value("상록수 제휴"))
			.andExpect(jsonPath("$.data.altText").value("상록수 제휴 광고"))
			.andExpect(jsonPath("$.data.trackingId").doesNotExist())
			.andExpect(jsonPath("$.data.sessionId").doesNotExist())
			.andReturn();

		mockMvc.perform(get("/api/ads/active")
				.param("placement", "route-result-bottom")
				.header("If-None-Match", first.getResponse().getHeader("ETag")))
			.andExpect(status().isNotModified());
	}

	@Test
	@DisplayName("활성 소재가 없으면 무재고 응답을 저장하지 않는다")
	void doesNotCacheNoActiveCreative() throws Exception {
		mockMvc.perform(get("/api/ads/active")
				.param("placement", "route-result-bottom"))
			.andExpect(status().isNoContent())
			.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	@Test
	@DisplayName("소재 응답 필드가 바뀌면 같은 creative도 ETag가 바뀐다")
	void changesEtagWhenCreativeContentChanges() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		insertPlacement("route-result-bottom");
		insertCreative("active", "route-result-bottom", now.minusHours(1), now.plusHours(1), true);

		MvcResult first = mockMvc.perform(get("/api/ads/active")
				.param("placement", "route-result-bottom"))
			.andExpect(status().isOk())
			.andReturn();

		jdbcTemplate.update("""
			UPDATE ad_creatives
			SET image_url=?
			WHERE id=?
			""", "https://cdn.easysubway.example/ads/active-v2.png", "active");

		mockMvc.perform(get("/api/ads/active")
				.param("placement", "route-result-bottom")
				.header("If-None-Match", first.getResponse().getHeader("ETag")))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", org.hamcrest.Matchers.not(first.getResponse().getHeader("ETag"))))
			.andExpect(jsonPath("$.data.imageUrl").value("https://cdn.easysubway.example/ads/active-v2.png"));
	}

	@Test
	@DisplayName("이벤트는 placement·creative·종류·일자 count만 집계한다")
	void aggregatesAnonymousEventsByDay() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		insertPlacement("route-result-bottom");
		insertCreative("active", "route-result-bottom", now.minusHours(1), now.plusHours(1), true);

		mockMvc.perform(post("/api/ads/events")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"placement":"route-result-bottom","creativeId":"active","eventType":"IMPRESSION"}
					"""))
			.andExpect(status().isNoContent());

		Integer count = jdbcTemplate.queryForObject("""
			SELECT event_count
			FROM ad_event_daily
			WHERE event_date=? AND placement_id=? AND creative_id=? AND event_type=?
			""", Integer.class, LocalDate.now(ZoneOffset.UTC), "route-result-bottom", "active", "IMPRESSION");
		Assertions.assertThat(count).isEqualTo(1);
	}

	@Test
	@DisplayName("이벤트 placement와 creative 조합이 다르면 저장하지 않고 204로 닫는다")
	void ignoresMismatchedCreativeEvent() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		insertPlacement("route-result-bottom");
		insertPlacement("home-top");
		insertCreative("active", "route-result-bottom", now.minusHours(1), now.plusHours(1), true);

		mockMvc.perform(post("/api/ads/events")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"placement":"home-top","creativeId":"active","eventType":"IMPRESSION"}
					"""))
			.andExpect(status().isNoContent());

		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ad_event_daily", Integer.class);
		Assertions.assertThat(count).isZero();
	}

	@Test
	@DisplayName("이벤트 필수 필드가 비어 있으면 400으로 거부한다")
	void rejectsBlankEventFields() throws Exception {
		mockMvc.perform(post("/api/ads/events")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"placement":"","creativeId":"active","eventType":"IMPRESSION"}
					"""))
			.andExpect(status().isBadRequest());
	}

	private void insertPlacement(String id) {
		jdbcTemplate.update("""
			INSERT INTO ad_placements (id, display_name, enabled)
			VALUES (?, ?, TRUE)
			""", id, id);
	}

	private void insertCreative(
		String id,
		String placementId,
		LocalDateTime startsAt,
		LocalDateTime endsAt,
		boolean enabled
	) {
		jdbcTemplate.update("""
			INSERT INTO ad_creatives (
				id, placement_id, image_url, landing_url, advertiser_name, alt_text, starts_at, ends_at, enabled
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			id, placementId,
			"https://cdn.easysubway.example/ads/" + id + ".png",
			"https://partner.example/" + id,
			"상록수 제휴",
			"상록수 제휴 광고",
			startsAt,
			endsAt,
			enabled);
	}
}
