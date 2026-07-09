package com.easysubway.ads.application.service;

import com.easysubway.ads.application.port.out.AdRepository;
import com.easysubway.ads.domain.AdCreative;
import com.easysubway.ads.domain.AdEventType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdService {

	private final AdRepository repository;
	private final Clock clock;

	@Autowired
	public AdService(AdRepository repository) {
		this(repository, Clock.systemUTC());
	}

	AdService(AdRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public Optional<AdCreative> activeCreative(String placementId) {
		return repository.findActive(placementId, LocalDateTime.now(clock));
	}

	public void recordEvent(String placementId, String creativeId, AdEventType eventType) {
		repository.incrementEvent(placementId, creativeId, eventType, LocalDate.now(clock));
	}
}
