package com.easysubway.auth.application.port.out;

import com.easysubway.auth.domain.AnonymousUserCredentials;

public interface RegisterAnonymousUserPort {

	boolean existsByUserId(String userId);

	boolean isAnonymousUser(String userId);

	void registerAnonymousUser(AnonymousUserCredentials credentials);

	boolean deleteAnonymousUser(String userId);
}
