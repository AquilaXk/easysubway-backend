package com.easysubway.auth.adapter.out.security;

import java.security.Principal;

public record AnonymousBearerPrincipal(String userId) implements Principal {

	@Override
	public String getName() {
		return userId;
	}
}
