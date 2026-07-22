package com.easysubway.admin.errors.application.service;

import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 오류 이벤트 비동기 저장. 호출자와 빈을 분리해 {@code @Async} 프록시가 동작하게 한다. */
@Component
public class ErrorEventAsyncWriter {

	private static final Logger log = LoggerFactory.getLogger(ErrorEventAsyncWriter.class);

	private final ErrorEventRepository repository;

	public ErrorEventAsyncWriter(ErrorEventRepository repository) {
		this.repository = repository;
	}

	@Async("errorEventTaskExecutor")
	public void persist(ErrorEvent event) {
		try {
			repository.upsertOccurrence(event);
		}
		catch (RuntimeException failure) {
			log.warn(
				"error event persist failed code={} pathPattern={} stackHashPrefix={}",
				event.code(),
				event.pathPattern(),
				event.stackHash() == null || event.stackHash().length() < 8
					? event.stackHash()
					: event.stackHash().substring(0, 8),
				failure
			);
		}
	}
}
