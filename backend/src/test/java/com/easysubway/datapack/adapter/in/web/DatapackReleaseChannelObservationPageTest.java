package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 배포 채널 화면의 git 원본 발행 관측")
class DatapackReleaseChannelObservationPageTest {

	private static final String SHA = "c".repeat(64);
	private static final String PAGE = "/admin/datapack/release-channels/page";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private DatapackReleaseCatalogPort catalog;

	@BeforeEach
	@AfterEach
	void clean() {
		jdbcTemplate.update("DELETE FROM datapack_release_channel_observations");
	}

	@Test
	@DisplayName("관측 행이 있으면 sequence·sha·request id·관측 시각·workflow run을 읽기 전용으로 보여준다")
	void showsObservation() throws Exception {
		insertObservation("production", 126);
		when(catalog.fetchCurrent("production")).thenReturn(new CatalogIdentity(126, SHA, "production", "", true, SHA));

		String html = render("observation-present");

		assertThat(html)
			.contains("git 원본 발행 관측")
			.contains("<th scope=\"col\">release sequence</th>")
			.contains("126")
			.contains(SHA.substring(0, 11))
			.contains("request-126")
			.contains("2026-10-03")
			.contains("https://github.com/AquilaXk/easysubway/actions/runs/126")
			.contains("공개 catalog와 일치")
			.contains("가로로 스크롤 가능한 git 원본 발행 관측 표")
			.doesNotContain("공개 catalog와 sequence가 다릅니다");
		assertThat(observationSection(html)).doesNotContain("<form").doesNotContain("<button");
	}

	@Test
	@DisplayName("관측 행이 없으면 세 채널 모두 관측 없음으로 표시하고 catalog를 조회하지 않는다")
	void showsAbsence() throws Exception {
		String html = render("observation-absent");

		String section = observationSection(html);
		assertThat(section.split("관측 없음", -1).length - 1).isGreaterThanOrEqualTo(3);
		assertThat(section)
			.contains("프로덕션").contains("스테이징").contains("개발")
			.doesNotContain("공개 catalog와 일치")
			.doesNotContain("sequence가 다릅니다");
		org.mockito.Mockito.verifyNoInteractions(catalog);
	}

	@Test
	@DisplayName("관측 sequence가 공개 catalog와 다르면 두 값을 담은 경고를 표시한다")
	void warnsOnSequenceMismatch() throws Exception {
		insertObservation("production", 126);
		when(catalog.fetchCurrent("production")).thenReturn(new CatalogIdentity(125, SHA, "production", "", true, SHA));

		String html = render("sequence-mismatch");

		assertThat(observationSection(html))
			.contains("공개 catalog와 sequence가 다릅니다")
			.contains("관측 126")
			.contains("catalog 125")
			.doesNotContain("공개 catalog와 일치");
	}

	@Test
	@DisplayName("catalog를 읽지 못하면 그 사실을 표시한다")
	void showsCatalogUnavailable() throws Exception {
		insertObservation("production", 126);
		when(catalog.fetchCurrent("production")).thenThrow(new DatapackReleaseCatalogPort.Unavailable());

		String html = render("catalog-unavailable");

		assertThat(observationSection(html))
			.contains("공개 catalog를 읽지 못해 비교하지 못했습니다")
			.doesNotContain("공개 catalog와 일치");
	}

	@Test
	@DisplayName("관측 화면 문구에 내부 거버넌스 용어를 쓰지 않는다")
	void usesNoGovernanceWords() throws Exception {
		insertObservation("production", 126);
		when(catalog.fetchCurrent("production")).thenReturn(new CatalogIdentity(125, SHA, "production", "", true, SHA));

		assertThat(observationSection(render("governance-words")))
			.doesNotContain("pilot").doesNotContain("게이트").doesNotContain("검증");
	}

	private String render(String name) throws Exception {
		String html = mockMvc.perform(get(PAGE)
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		saveEvidence(name, html);
		return html;
	}

	private static String observationSection(String html) {
		int start = html.indexOf("git 원본 발행 관측");
		assertThat(start).as("관측 섹션").isNotNegative();
		int end = html.indexOf("</section>", start);
		return html.substring(start, end);
	}

	private static void saveEvidence(String name, String html) throws IOException {
		Path directory = Path.of("build", "test-evidence", "release-channel-observations");
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(name + ".html"), html, StandardCharsets.UTF_8);
	}

	private void insertObservation(String channel, long sequence) {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channel_observations (
				channel, release_sequence, manifest_sha256, release_request_id,
				binding_signature_sha256, delivery_idempotency_key, workflow_run_url, observed_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""", channel, sequence, SHA, "request-" + sequence, "d".repeat(64), "delivery-" + sequence,
			"https://github.com/AquilaXk/easysubway/actions/runs/" + sequence,
			LocalDateTime.of(2026, 10, 3, 9, 30));
	}
}
