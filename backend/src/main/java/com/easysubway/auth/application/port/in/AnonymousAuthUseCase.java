package com.easysubway.auth.application.port.in;

import com.easysubway.auth.domain.AnonymousUserCredentials;
import com.easysubway.auth.domain.AuthenticatedUser;

public interface AnonymousAuthUseCase {

	AnonymousUserCredentials issueAnonymousUser();

	AuthenticatedUser currentUser(String userId);
}
