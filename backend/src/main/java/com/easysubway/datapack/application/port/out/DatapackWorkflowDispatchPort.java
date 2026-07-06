package com.easysubway.datapack.application.port.out;

public interface DatapackWorkflowDispatchPort {

	DispatchResult dispatch(DispatchCommand command);

	record DispatchCommand(String targetChannel, String releaseRequestId, String buildSpecPath) {
	}

	record DispatchResult(boolean skipped, boolean ok, String detail) {

		public static DispatchResult skippedResult() {
			return new DispatchResult(true, false, "dispatch token not configured");
		}

		public static DispatchResult succeeded(String detail) {
			return new DispatchResult(false, true, detail);
		}

		public static DispatchResult failed(String detail) {
			return new DispatchResult(false, false, detail);
		}
	}
}
