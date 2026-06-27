package com.easysubway.admin.web;

import com.easysubway.common.error.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdminCommandTokenService {

	public static final String PARAMETER_NAME = "commandToken";
	private static final String SESSION_ATTRIBUTE = AdminCommandTokenService.class.getName() + ".TOKENS";
	private static final int MAX_TOKENS_PER_SESSION = 64;

	public String issue(HttpServletRequest request) {
		HttpSession session = request.getSession();
		synchronized (session) {
			LinkedHashSet<String> tokens = tokens(session);
			String token = UUID.randomUUID().toString();
			tokens.add(token);
			while (tokens.size() > MAX_TOKENS_PER_SESSION) {
				tokens.remove(tokens.iterator().next());
			}
			return token;
		}
	}

	public void consume(HttpServletRequest request, String token) {
		if (!StringUtils.hasText(token)) {
			throw duplicateSubmit();
		}
		HttpSession session = request.getSession(false);
		if (session == null) {
			throw duplicateSubmit();
		}
		synchronized (session) {
			Object value = session.getAttribute(SESSION_ATTRIBUTE);
			if (!(value instanceof Set<?> tokens) || !tokens.remove(token)) {
				throw duplicateSubmit();
			}
		}
	}

	@SuppressWarnings("unchecked")
	private LinkedHashSet<String> tokens(HttpSession session) {
		Object value = session.getAttribute(SESSION_ATTRIBUTE);
		if (value instanceof LinkedHashSet<?> existing) {
			return (LinkedHashSet<String>) existing;
		}
		LinkedHashSet<String> tokens = new LinkedHashSet<>();
		session.setAttribute(SESSION_ATTRIBUTE, tokens);
		return tokens;
	}

	private static ConflictException duplicateSubmit() {
		return new ConflictException("이미 처리되었거나 만료된 관리자 요청입니다. 화면을 새로고침한 뒤 다시 시도해 주세요.");
	}
}
