package com.easysubway.ads.application.port.out;

import com.easysubway.ads.domain.AdCreative;
import com.easysubway.ads.domain.AdEventType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AdRepository {

	Optional<AdCreative> findActive(String placementId, LocalDateTime now);

	List<AdCreative> findAll();

	Optional<AdCreative> findById(String creativeId);

	Optional<AdCreative> findByIdForUpdate(String creativeId);

	void lockPlacement(String placementId);

	void insert(AdCreative creative);

	void save(AdCreative creative);

	boolean setEnabled(String creativeId, boolean enabled);

	boolean hasEnabledOverlap(
		String placementId,
		String excludedCreativeId,
		LocalDateTime startsAt,
		LocalDateTime endsAt
	);

	void incrementEvent(String placementId, String creativeId, AdEventType eventType, LocalDate eventDate);
}
