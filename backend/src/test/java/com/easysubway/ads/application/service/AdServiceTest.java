package com.easysubway.ads.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.ads.application.port.out.AdRepository;
import com.easysubway.ads.domain.AdCreative;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.error.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@DisplayName("광고 소재 lifecycle service")
class AdServiceTest {

	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-11T00:00:00");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC);
	private static final String PLACEMENT = "route-result-bottom";
	private static final String IMAGE = "https://assets.easysubway.example/ads/creative-1.png";
	private static final String LANDING = "https://partner.example/creative-1";
	private static final String ADVERTISER = "광고주";
	private static final String ALT = "광고 대체텍스트";

	@Mock
	private AdRepository repository;
	private AdService service;

	@BeforeEach
	void setUp() {
		service = new AdService(repository, CLOCK, "https://assets.easysubway.example");
	}

	@Test
	@DisplayName("create payload의 enabled=true는 무시하고 disabled로 저장한다")
	void createsDisabledRegardlessOfPayloadEnabled() {
		AdCreative payload = creative("creative-1", true);
		AdCreative expected = withEnabled(payload, false);
		when(repository.findByIdForUpdate(payload.id())).thenReturn(Optional.empty());
		when(repository.findById(payload.id())).thenReturn(Optional.empty());

		assertThat(service.saveCreative(payload)).isEqualTo(AdService.SaveResult.CREATED);

		verify(repository).insert(expected);
		verify(repository, never()).hasEnabledOverlap(any(), any(), any(), any());
	}

	@Test
	@DisplayName("update payload의 enabled=false는 무시하고 잠근 current enabled를 보존한다")
	void updatesWithCurrentEnabledState() {
		AdCreative current = creative("creative-1", true);
		AdCreative payload = new AdCreative(
			current.id(), "station-detail-bottom", current.imageUrl(), current.landingUrl(),
			"수정 광고주", current.altText(), T0.plusHours(1), T0.plusDays(2), false);
		AdCreative expected = withEnabled(payload, true);
		when(repository.findByIdForUpdate(payload.id())).thenReturn(Optional.of(current));

		assertThat(service.saveCreative(payload)).isEqualTo(AdService.SaveResult.UPDATED);

		verify(repository).hasEnabledOverlap(
			expected.placementId(), expected.id(), expected.startsAt(), expected.endsAt());
		verify(repository).save(expected);
	}

	@ParameterizedTest
	@MethodSource("invalidCreatives")
	@DisplayName("고정 placement·DB 길이·HTTPS·first-party image·기간 순서를 위반하면 저장하지 않는다")
	void rejectsInvalidCreativeBoundaries(AdCreative creative) {
		assertThatThrownBy(() -> service.saveCreative(creative))
			.isInstanceOf(InvalidRequestException.class);
		verify(repository, never()).save(any());
		verify(repository, never()).insert(any());
	}

	private static Stream<Arguments> invalidCreatives() {
		return Stream.of(
			invalid(" ", PLACEMENT, IMAGE, LANDING, ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("x".repeat(65), PLACEMENT, IMAGE, LANDING, ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", "home-top", IMAGE, LANDING, ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, " ", LANDING, ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, "https://assets.easysubway.example/" + "x".repeat(1000), LANDING, ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, "http://assets.easysubway.example/ad.png", LANDING, ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, "https://third-party.example/ad.png", LANDING, ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, " ", ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, "https://partner.example/" + "x".repeat(1000), ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, "http://partner.example", ADVERTISER, ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, LANDING, " ", ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, LANDING, "x".repeat(256), ALT, T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, LANDING, ADVERTISER, " ", T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, LANDING, ADVERTISER, "x".repeat(501), T0, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, LANDING, ADVERTISER, ALT, null, T0.plusDays(1)),
			invalid("creative-1", PLACEMENT, IMAGE, LANDING, ADVERTISER, ALT, T0, T0));
	}

	@ParameterizedTest
	@MethodSource("routeUnsafeCreativeIds")
	@DisplayName("creative id는 단일 route segment에 안전하지 않은 문자를 거부한다")
	void rejectsRouteUnsafeCreativeIds(String id) {
		AdCreative payload = new AdCreative(
			id, PLACEMENT, IMAGE, LANDING, ADVERTISER, ALT, T0, T0.plusDays(1), false);

		assertThatThrownBy(() -> service.saveCreative(payload))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("creative id");
		assertThatThrownBy(() -> service.setCreativeEnabled(id, true))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("creative id");
		verify(repository, never()).insert(any());
		verify(repository, never()).setEnabled(id, true);
	}

	private static Stream<String> routeUnsafeCreativeIds() {
		return Stream.of(
			".",
			"..",
			"summer/banner",
			"summer%2Fbanner",
			"summer%banner",
			"summer?banner",
			"summer#banner",
			"summer banner",
			"summer\tbanner",
			"summer\nbanner");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"A",
		".banner",
		"a..b",
		"A._-A._-A._-A._-A._-A._-A._-A._-A._-A._-A._-A._-A._-A._-A._-A._-"
	})
	@DisplayName("creative id는 route-safe 문자의 1자와 64자 경계를 허용한다")
	void acceptsRouteSafeCreativeIdBoundaries(String id) {
		AdCreative payload = creative(id, false);
		when(repository.findByIdForUpdate(id)).thenReturn(Optional.empty());
		when(repository.findById(id)).thenReturn(Optional.empty());

		assertThat(service.saveCreative(payload)).isEqualTo(AdService.SaveResult.CREATED);
		verify(repository).insert(payload);
	}

	private static Arguments invalid(
		String id,
		String placement,
		String image,
		String landing,
		String advertiser,
		String alt,
		LocalDateTime startsAt,
		LocalDateTime endsAt
	) {
		return Arguments.of(new AdCreative(
			id, placement, image, landing, advertiser, alt, startsAt, endsAt, false));
	}

	@Test
	@DisplayName("동시 생성 duplicate는 operator conflict로 매핑하고 SQL 재시도를 맡기지 않는다")
	void mapsDuplicateCreateToOperatorConflict() {
		AdCreative creative = creative("creative-1", false);
		when(repository.findByIdForUpdate(creative.id())).thenReturn(Optional.empty());
		when(repository.findById(creative.id())).thenReturn(Optional.empty());
		doThrow(new DuplicateKeyException("duplicate creative id")).when(repository).insert(creative);

		assertThatThrownBy(() -> service.saveCreative(creative))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("이미 존재");
		verify(repository).insert(creative);
	}

	@Test
	@DisplayName("enabled 소재의 같은 placement 기간 겹침은 저장과 활성화를 거부한다")
	void rejectsEnabledOverlapOnSaveAndEnable() {
		AdCreative creative = creative("creative-1", true);
		when(repository.findByIdForUpdate(creative.id())).thenReturn(Optional.of(creative));
		when(repository.hasEnabledOverlap(
			creative.placementId(), creative.id(), creative.startsAt(), creative.endsAt())).thenReturn(true);

		assertThatThrownBy(() -> service.saveCreative(creative))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("겹칩니다");
		verify(repository, never()).save(any());

		AdCreative disabled = withEnabled(creative, false);
		when(repository.findByIdForUpdate(creative.id())).thenReturn(Optional.of(disabled));
		assertThatThrownBy(() -> service.setCreativeEnabled(creative.id(), true))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("겹칩니다");
		verify(repository, never()).setEnabled(creative.id(), true);
	}

	@Test
	@DisplayName("disabled 전환은 overlap과 무관하고 없는 소재는 404 경계로 닫는다")
	void disablesExistingAndRejectsMissingCreative() {
		AdCreative creative = creative("creative-1", true);
		when(repository.findByIdForUpdate(creative.id())).thenReturn(Optional.of(creative));

		service.setCreativeEnabled(creative.id(), false);

		verify(repository).setEnabled(creative.id(), false);
		when(repository.findByIdForUpdate("missing")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.setCreativeEnabled("missing", true))
			.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	@DisplayName("configured asset origin 오류는 request validation과 분리해 모두 fail closed한다")
	void invalidConfiguredAssetOriginsFailClosed() {
		for (String origin : List.of(
			"",
			"not a URI",
			"http://assets.easysubway.example",
			"https://user@assets.easysubway.example",
			"https://assets.easysubway.example/ads",
			"https://assets.easysubway.example?tenant=ads")) {
			AdService invalid = new AdService(repository, CLOCK, origin);
			assertThatThrownBy(() -> invalid.saveCreative(creative("creative-1", false)))
				.as(origin)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("asset origin");
		}
	}

	private AdCreative creative(String id, boolean enabled) {
		return new AdCreative(
			id,
			"route-result-bottom",
			"https://assets.easysubway.example/ads/" + id + ".png",
			"https://partner.example/" + id,
			"광고주",
			"광고 대체텍스트",
			T0,
			T0.plusDays(1),
			enabled);
	}

	private AdCreative withEnabled(AdCreative creative, boolean enabled) {
		return new AdCreative(
			creative.id(), creative.placementId(), creative.imageUrl(), creative.landingUrl(),
			creative.advertiserName(), creative.altText(), creative.startsAt(), creative.endsAt(), enabled);
	}

}
