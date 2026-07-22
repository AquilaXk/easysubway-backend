package com.easysubway.admin.errors.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 예외 스택 상위 프레임 SHA-256. message 원문은 해시 입력에 넣지 않는다. */
final class ErrorStackHash {

	private static final int FRAME_LIMIT = 20;

	private ErrorStackHash() {
	}

	static String of(Throwable exception) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(exception.getClass().getName().getBytes(StandardCharsets.UTF_8));
			digest.update("\n".getBytes(StandardCharsets.UTF_8));
			StackTraceElement[] frames = exception.getStackTrace();
			int limit = Math.min(frames.length, FRAME_LIMIT);
			for (int index = 0; index < limit; index++) {
				StackTraceElement frame = frames[index];
				String line = frame.getClassName()
					+ "#"
					+ frame.getMethodName()
					+ ":"
					+ frame.getLineNumber()
					+ "\n";
				digest.update(line.getBytes(StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException unexpected) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", unexpected);
		}
	}
}
