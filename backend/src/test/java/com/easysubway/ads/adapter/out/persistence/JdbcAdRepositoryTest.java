package com.easysubway.ads.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.ads.domain.AdCreative;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

@DisplayName("H2 광고 소재 저장소")
class JdbcAdRepositoryTest {

	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-11T00:00:00");

	private JdbcTemplate jdbcTemplate;
	private JdbcAdRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.generateUniqueName(true)
			.build();
		jdbcTemplate = new JdbcTemplate(dataSource);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();
		repository = new JdbcAdRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("소재를 생성·목록 조회하고 같은 id를 수정한 뒤 활성 상태를 전환한다")
	void createsUpdatesListsAndTogglesCreative() {
		repository.save(creative("creative-1", "route-result-bottom", T0, T0.plusDays(1), false));

		assertThat(repository.findAll()).containsExactly(
			creative("creative-1", "route-result-bottom", T0, T0.plusDays(1), false));

		AdCreative updated = new AdCreative(
			"creative-1",
			"station-detail-bottom",
			"https://assets.easysubway.example/ads/updated.png",
			"https://partner.example/updated",
			"수정 광고주",
			"수정된 광고 대체텍스트",
			T0.plusHours(1),
			T0.plusDays(2),
			false);
		repository.save(updated);

		assertThat(repository.findById("creative-1")).contains(updated);
		assertThat(repository.findByIdForUpdate("creative-1")).contains(updated);
		repository.lockPlacement("station-detail-bottom");
		assertThat(repository.setEnabled("creative-1", true)).isTrue();
		assertThat(repository.findById("creative-1")).get()
			.extracting(AdCreative::enabled)
			.isEqualTo(true);
		assertThat(repository.setEnabled("missing", true)).isFalse();
	}

	@Test
	@DisplayName("같은 placement의 enabled 반개구간만 겹침으로 판단하고 자기 자신은 제외한다")
	void detectsEnabledHalfOpenIntervalOverlap() {
		repository.save(creative("existing", "route-result-bottom", T0, T0.plusHours(2), true));
		repository.save(creative("disabled", "route-result-bottom", T0, null, false));

		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "new", T0.plusHours(1), T0.plusHours(3))).isTrue();
		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "new", T0.plusHours(2), T0.plusHours(3))).isFalse();
		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "existing", T0, T0.plusHours(2))).isFalse();
		assertThat(repository.hasEnabledOverlap(
			"station-detail-bottom", "new", T0, null)).isFalse();
	}

	private AdCreative creative(
		String id,
		String placementId,
		LocalDateTime startsAt,
		LocalDateTime endsAt,
		boolean enabled
	) {
		return new AdCreative(
			id,
			placementId,
			"https://assets.easysubway.example/ads/" + id + ".png",
			"https://partner.example/" + id,
			"광고주",
			"광고 대체텍스트",
			startsAt,
			endsAt,
			enabled);
	}

}
