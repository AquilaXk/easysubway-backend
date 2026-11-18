package com.easysubway.common.persistence;

import java.io.PrintStream;
import java.time.Clock;
import java.util.Map;

public final class LegacyFlywayBaselineCommand {

	private LegacyFlywayBaselineCommand() {
	}

	public static void main(String[] arguments) {
		int exitCode = run(
			arguments,
			System.getenv(),
			System.out,
			LegacyFlywayBaselineCommand.class.getClassLoader(),
			Clock.systemUTC()
		);
		System.exit(exitCode);
	}

	static int run(
		String[] arguments,
		Map<String, String> environment,
		PrintStream output,
		ClassLoader classLoader,
		Clock clock
	) {
		if (arguments == null || arguments.length != 0) {
			return writeFailure(output, clock, LegacyFlywayBaselineTransition.Reason.INVALID_ARGUMENTS);
		}
		try {
			var policy = LegacyFlywayBaselineTransition.loadPolicy(classLoader);
			var runtimeIdentity = LegacyFlywayBaselineTransition.verifyRuntime(policy, classLoader);
			var commandInput = LegacyFlywayBaselineTransition.readEnvironment(environment);
			var result = LegacyFlywayBaselineTransition.execute(
				commandInput.credentials(),
				commandInput.operation(),
				policy,
				runtimeIdentity,
				clock,
				() -> {
					if (Thread.currentThread().isInterrupted()) throw new InterruptedException("interrupted");
				}
			);
			output.println(LegacyFlywayBaselineTransition.renderEvidence(policy, result));
			return result.success() ? 0 : 1;
		} catch (LegacyFlywayBaselineTransition.ContractFailure failure) {
			return writeFailure(output, clock, failure.reason());
		} catch (RuntimeException exception) {
			return writeFailure(output, clock, LegacyFlywayBaselineTransition.Reason.INTERNAL_FAILURE);
		}
	}

	private static int writeFailure(
		PrintStream output,
		Clock clock,
		LegacyFlywayBaselineTransition.Reason reason
	) {
		var result = LegacyFlywayBaselineTransition.Result.failure(reason, clock.instant());
		output.println(LegacyFlywayBaselineTransition.renderEvidence(null, result));
		return 1;
	}
}
