package com.easysubway.ads.adapter.in.web;

import com.easysubway.ads.application.service.AdService;
import com.easysubway.ads.domain.AdCreative;
import com.easysubway.ads.domain.AdEventType;
import com.easysubway.common.web.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AdPublicController {

	private static final CacheControl AD_CACHE = CacheControl.maxAge(300, TimeUnit.SECONDS).cachePublic();

	private final AdService service;

	AdPublicController(AdService service) {
		this.service = service;
	}

	@GetMapping("/api/ads/active")
	ResponseEntity<ApiResponse<AdResponse>> active(
		@RequestParam String placement,
		WebRequest webRequest
	) {
		return service.activeCreative(placement)
			.map(creative -> activeResponse(creative, webRequest))
			.orElseGet(() -> ResponseEntity.noContent().cacheControl(AD_CACHE).build());
	}

	@PostMapping("/api/ads/events")
	ResponseEntity<Void> event(@RequestBody AdEventRequest request) {
		request.validate();
		service.recordEvent(request.placement(), request.creativeId(), request.toEventType());
		return ResponseEntity.noContent().build();
	}

	private ResponseEntity<ApiResponse<AdResponse>> activeResponse(AdCreative creative, WebRequest webRequest) {
		AdResponse response = AdResponse.from(creative);
		String etag = etagFor(response, creative);
		if (etag.equals(webRequest.getHeader("If-None-Match"))) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
				.eTag(etag)
				.cacheControl(AD_CACHE)
				.build();
		}
		return ResponseEntity.ok()
			.eTag(etag)
			.cacheControl(AD_CACHE)
			.body(ApiResponse.ok(response));
	}

	private static String etagFor(AdResponse response, AdCreative creative) {
		String fingerprint = response.placement() + "@" + response.creativeId()
			+ "@" + creative.startsAt() + "~" + creative.endsAt();
		return "\"" + DigestUtils.md5DigestAsHex(fingerprint.getBytes(StandardCharsets.UTF_8)) + "\"";
	}

	record AdResponse(
		String placement,
		String creativeId,
		String imageUrl,
		String landingUrl,
		String advertiserName,
		String altText
	) {
		static AdResponse from(AdCreative creative) {
			return new AdResponse(
				creative.placementId(),
				creative.id(),
				creative.imageUrl(),
				creative.landingUrl(),
				creative.advertiserName(),
				creative.altText());
		}
	}

	record AdEventRequest(String placement, String creativeId, String eventType) {
		void validate() {
			requireText(placement, "placement");
			requireText(creativeId, "creativeId");
		}

		AdEventType toEventType() {
			try {
				return AdEventType.valueOf(eventType);
			} catch (RuntimeException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ad event type");
			}
		}

		private static void requireText(String value, String field) {
			if (!StringUtils.hasText(value)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing ad " + field);
			}
		}
	}
}
