package com.easysubway.train.adapter.out.http;

import com.easysubway.train.application.TrainSearchCache;
import com.easysubway.train.application.TrainSearchProvider.ProviderFailure;
import com.easysubway.train.application.TrainSearchProviderCallBudget;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
final class SharedTrainSearchProviderCallBudget implements TrainSearchProviderCallBudget {

	private static final String PROVIDER_ID = "tago-train";
	private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");

	private final TrainSearchCache cache;
	private final int minuteLimit;
	private final int dayLimit;

	SharedTrainSearchProviderCallBudget(
		TrainSearchCache cache,
		@Value("${EASYSUBWAY_TAGO_TRAIN_CALL_LIMIT_PER_MINUTE:60}") int minuteLimit,
		@Value("${EASYSUBWAY_TAGO_TRAIN_CALL_LIMIT_PER_DAY:1000}") int dayLimit
	) {
		this.cache = cache;
		this.minuteLimit = minuteLimit;
		this.dayLimit = dayLimit;
	}

	@Override
	public void acquire() {
		try {
			if (cache.tryAcquireProviderCall(PROVIDER_ID, PROVIDER_ZONE, minuteLimit, dayLimit)) {
				return;
			}
		} catch (DataAccessException | TransactionException exception) {
			throw new ProviderFailure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
		throw new ProviderFailure("TRAIN_SEARCH_UNAVAILABLE");
	}
}
