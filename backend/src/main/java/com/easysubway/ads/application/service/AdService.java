package com.easysubway.ads.application.service;

import com.easysubway.ads.application.port.out.AdRepository;
import com.easysubway.ads.domain.AdCreative;
import com.easysubway.ads.domain.AdEventType;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.error.ResourceNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdService {

	public enum SaveResult {
		CREATED,
		UPDATED
	}

	private static final List<String> PLACEMENTS = List.of("route-result-bottom", "station-detail-bottom");
	private static final Pattern CREATIVE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private final AdRepository repository;
	private final Clock clock;
	private final String assetOrigin;

	@Autowired
	public AdService(
		AdRepository repository,
		@Value("${easysubway.ads.asset-origin:}") String assetOrigin
	) {
		this(repository, Clock.systemUTC(), assetOrigin);
	}

	AdService(AdRepository repository, Clock clock, String assetOrigin) {
		this.repository = repository;
		this.clock = clock;
		this.assetOrigin = assetOrigin;
	}

	public Optional<AdCreative> activeCreative(String placementId) {
		return repository.findActive(placementId, LocalDateTime.now(clock));
	}

	public List<AdCreative> creatives() {
		return repository.findAll();
	}

	@Transactional
	public SaveResult saveCreative(AdCreative creative) {
		AdCreative payload = validate(creative);
		lockPlacements();
		Optional<AdCreative> current = repository.findByIdForUpdate(payload.id());
		AdCreative saved;
		boolean created = current.isEmpty();
		if (created) {
			saved = withEnabled(payload, false);
			if (repository.findById(saved.id()).isPresent()) {
				throw duplicateCreative();
			}
		} else {
			saved = withEnabled(payload, current.orElseThrow().enabled());
			assertNoEnabledOverlap(saved);
		}
		try {
			if (created) {
				repository.insert(saved);
			} else {
				repository.save(saved);
			}
		} catch (DuplicateKeyException exception) {
			throw new InvalidRequestException("이미 존재하는 광고 creative id입니다.", exception);
		}
		return created ? SaveResult.CREATED : SaveResult.UPDATED;
	}

	@Transactional
	public void setCreativeEnabled(String creativeId, boolean enabled) {
		String normalizedId = creativeId(creativeId);
		lockPlacements();
		AdCreative creative = repository.findByIdForUpdate(normalizedId)
			.orElseThrow(() -> new ResourceNotFoundException("광고 소재를 찾을 수 없습니다: " + normalizedId));
		if (enabled) {
			assertNoEnabledOverlap(withEnabled(validate(creative), true));
		}
		repository.setEnabled(normalizedId, enabled);
	}

	public void recordEvent(String placementId, String creativeId, AdEventType eventType) {
		repository.incrementEvent(placementId, creativeId, eventType, LocalDate.now(clock));
	}

	private AdCreative validate(AdCreative creative) {
		if (creative == null) {
			throw new InvalidRequestException("광고 소재가 필요합니다.");
		}
		String id = creativeId(creative.id());
		String placementId = requireText(creative.placementId(), 64, "placement id");
		if (!PLACEMENTS.contains(placementId)) {
			throw new InvalidRequestException("지원하지 않는 광고 placement입니다.");
		}
		String imageUrl = requireText(creative.imageUrl(), 1000, "image URL");
		String landingUrl = requireText(creative.landingUrl(), 1000, "landing URL");
		URI image = httpsUri(imageUrl, "image URL");
		URI landing = httpsUri(landingUrl, "landing URL");
		URI configuredOrigin = configuredAssetOrigin();
		if (!sameOrigin(image, configuredOrigin)) {
			throw new InvalidRequestException("image URL은 first-party asset origin을 사용해야 합니다.");
		}
		String advertiserName = requireText(creative.advertiserName(), 255, "광고주명");
		String altText = requireText(creative.altText(), 500, "대체텍스트");
		if (creative.startsAt() == null) {
			throw new InvalidRequestException("광고 시작 시각이 필요합니다.");
		}
		if (creative.endsAt() != null && !creative.startsAt().isBefore(creative.endsAt())) {
			throw new InvalidRequestException("광고 종료 시각은 시작 시각보다 늦어야 합니다.");
		}
		return new AdCreative(
			id, placementId, image.toString(), landing.toString(), advertiserName, altText,
			creative.startsAt(), creative.endsAt(), creative.enabled());
	}

	private URI configuredAssetOrigin() {
		if (assetOrigin == null || assetOrigin.isBlank()) {
			throw new IllegalStateException("광고 asset origin 설정이 필요합니다.");
		}
		URI origin;
		try {
			origin = new URI(assetOrigin.trim());
		} catch (URISyntaxException exception) {
			throw new IllegalStateException("광고 asset origin 형식이 올바르지 않습니다.", exception);
		}
		if (!"https".equalsIgnoreCase(origin.getScheme())
			|| origin.getHost() == null
			|| origin.getUserInfo() != null
			|| (origin.getPath() != null && !origin.getPath().isEmpty() && !"/".equals(origin.getPath()))
			|| origin.getQuery() != null
			|| origin.getFragment() != null) {
			throw new IllegalStateException("광고 asset origin은 HTTPS origin이어야 합니다.");
		}
		return origin;
	}

	private URI httpsUri(String value, String field) {
		try {
			URI uri = new URI(value);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
				throw new InvalidRequestException(field + "은(는) user info 없는 absolute HTTPS URL이어야 합니다.");
			}
			return uri;
		} catch (URISyntaxException exception) {
			throw new InvalidRequestException(field + " 형식이 올바르지 않습니다.", exception);
		}
	}

	private boolean sameOrigin(URI left, URI right) {
		return left.getScheme().equalsIgnoreCase(right.getScheme())
			&& left.getHost().equalsIgnoreCase(right.getHost())
			&& effectivePort(left) == effectivePort(right);
	}

	private int effectivePort(URI uri) {
		return uri.getPort() == -1 ? 443 : uri.getPort();
	}

	private void assertNoEnabledOverlap(AdCreative creative) {
		if (creative.enabled() && repository.hasEnabledOverlap(
			creative.placementId(), creative.id(), creative.startsAt(), creative.endsAt())) {
			throw new InvalidRequestException("같은 광고 placement의 활성 기간이 겹칩니다.");
		}
	}

	private void lockPlacements() {
		// ponytail: two fixed placements; narrow locks if admin mutation throughput ever matters
		PLACEMENTS.forEach(repository::lockPlacement);
	}

	private AdCreative withEnabled(AdCreative creative, boolean enabled) {
		return new AdCreative(
			creative.id(), creative.placementId(), creative.imageUrl(), creative.landingUrl(),
			creative.advertiserName(), creative.altText(), creative.startsAt(), creative.endsAt(), enabled);
	}

	private InvalidRequestException duplicateCreative() {
		return new InvalidRequestException("이미 존재하는 광고 creative id입니다.");
	}

	private String creativeId(String value) {
		if (value == null || !CREATIVE_ID.matcher(value).matches()) {
			throw new InvalidRequestException("creative id는 영문자, 숫자, 점, 밑줄, 하이픈 1~64자여야 합니다.");
		}
		if (".".equals(value) || "..".equals(value)) {
			throw new InvalidRequestException("creative id는 URL dot-segment일 수 없습니다.");
		}
		return value;
	}

	private String requireText(String value, int maxLength, String field) {
		if (value == null || value.isBlank()) {
			throw new InvalidRequestException(field + "은(는) 필수입니다.");
		}
		String normalized = value.trim();
		if (normalized.length() > maxLength) {
			throw new InvalidRequestException(field + "은(는) " + maxLength + "자 이하여야 합니다.");
		}
		return normalized;
	}
}
