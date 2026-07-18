package com.easysubway.train.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.train.application.TrainSearchCache;
import com.easysubway.train.application.TrainSearchProvider.ProviderFailure;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

class SharedTrainSearchProviderCallBudgetTest {

	@Test
	void quotaRejectionFailsClosedAsUnavailable() {
		var cache = mock(TrainSearchCache.class);
		when(cache.tryAcquireProviderCall("tago-train", ZoneId.of("Asia/Seoul"), 10, 1000))
			.thenReturn(false);
		var budget = new SharedTrainSearchProviderCallBudget(cache, 10, 1000);

		assertThatThrownBy(budget::acquire)
			.isInstanceOf(ProviderFailure.class)
			.hasMessage("TRAIN_SEARCH_UNAVAILABLE");
		verify(cache).tryAcquireProviderCall("tago-train", ZoneId.of("Asia/Seoul"), 10, 1000);
	}

	@Test
	void quotaPersistenceFailureFailsClosedAsUnavailable() {
		var cache = mock(TrainSearchCache.class);
		when(cache.tryAcquireProviderCall("tago-train", ZoneId.of("Asia/Seoul"), 10, 1000))
			.thenThrow(new QueryTimeoutException("quota timeout"));
		var budget = new SharedTrainSearchProviderCallBudget(cache, 10, 1000);

		assertThatThrownBy(budget::acquire)
			.isInstanceOf(ProviderFailure.class)
			.hasMessage("TRAIN_SEARCH_UNAVAILABLE")
			.hasCauseInstanceOf(QueryTimeoutException.class);
		verify(cache).tryAcquireProviderCall("tago-train", ZoneId.of("Asia/Seoul"), 10, 1000);
	}

	@Test
	void quotaTransactionBoundaryFailureFailsClosedAsUnavailable() {
		var cache = mock(TrainSearchCache.class);
		when(cache.tryAcquireProviderCall("tago-train", ZoneId.of("Asia/Seoul"), 10, 1000))
			.thenThrow(new CannotCreateTransactionException("cannot open quota transaction"));
		var budget = new SharedTrainSearchProviderCallBudget(cache, 10, 1000);

		assertThatThrownBy(budget::acquire)
			.isInstanceOf(ProviderFailure.class)
			.hasMessage("TRAIN_SEARCH_UNAVAILABLE");
		verify(cache).tryAcquireProviderCall("tago-train", ZoneId.of("Asia/Seoul"), 10, 1000);
	}
}
