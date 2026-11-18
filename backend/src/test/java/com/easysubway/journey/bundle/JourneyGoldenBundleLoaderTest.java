package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("번들 diff 도구의 번들 로더는 manifest와 맞지 않는 번들을 읽지 않는다")
class JourneyGoldenBundleLoaderTest {

	@TempDir
	Path directory;

	@Test
	@DisplayName("manifest가 없으면 읽지 않는다")
	void rejectsMissingManifest() {
		assertThatThrownBy(() -> JourneyGoldenBundleLoader.load(directory))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifest.json");
	}

	@Test
	@DisplayName("payload 파일이 없으면 읽지 않는다")
	void rejectsMissingPayload() throws IOException {
		Files.writeString(directory.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> JourneyGoldenBundleLoader.load(directory))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining(".sqlite.zst");
	}

	@Test
	@DisplayName("payload digest가 manifest와 다르면 읽지 않는다")
	void rejectsPayloadDigestMismatch() throws IOException {
		Files.writeString(directory.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(directory.resolve("payload"));
		for (String name : new String[] {"topology", "timetable", "accessibility", "fare"}) {
			Files.writeString(directory.resolve("payload/" + name + ".sqlite.zst"), name, StandardCharsets.UTF_8);
		}

		assertThatThrownBy(() -> JourneyGoldenBundleLoader.load(directory))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest does not match");
	}
}
