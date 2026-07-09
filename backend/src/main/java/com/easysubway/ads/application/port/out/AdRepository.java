package com.easysubway.ads.application.port.out;

import com.easysubway.ads.domain.AdCreative;
import com.easysubway.ads.domain.AdEventType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AdRepository {

	Optional<AdCreative> findActive(String placementId, LocalDateTime now);

	void incrementEvent(String placementId, String creativeId, AdEventType eventType, LocalDate eventDate);
}
