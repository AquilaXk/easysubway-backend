package com.easysubway.collection.adapter.out.idgnr;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.collection.application.port.out.GenerateCollectionRunIdPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("수집 run ID control-plane 발번(fdl-property + fdl-idgnr)")
class CollectionControlPlaneEgovConfigTest {

	@Autowired
	private GenerateCollectionRunIdPort generateCollectionRunIdPort;

	@Test
	@DisplayName("run ID는 property 접두어 + idgnr 순번으로 발번되며 collection- 포맷을 유지한다")
	void generatesPrefixedIncrementingRunId() {
		String first = generateCollectionRunIdPort.nextCollectionRunId();
		String second = generateCollectionRunIdPort.nextCollectionRunId();

		assertThat(first).startsWith("collection-");
		assertThat(second).startsWith("collection-");
		assertThat(first).isNotEqualTo(second);

		long firstSequence = Long.parseLong(first.substring("collection-".length()));
		long secondSequence = Long.parseLong(second.substring("collection-".length()));
		assertThat(secondSequence).isGreaterThan(firstSequence);
	}
}
