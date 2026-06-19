package com.easysubway.auth.application.port.in;

import com.easysubway.auth.domain.AnonymousAuthTokenSession;
import com.easysubway.auth.domain.AuthenticatedUser;

public interface AnonymousAuthUseCase {

	AnonymousAuthTokenSession issueAnonymousUser();

	AnonymousAuthTokenSession refreshAnonymousUser(String refreshToken);

	AuthenticatedUser currentUser(String userId, String authType);
}
