package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.datapack.application.service.DatapackReleaseRequestService.CreateReleaseRequestCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("DatapackReleaseRequestService")
class DatapackReleaseRequestServiceTest {

	private static final String SHA = "a".repeat(64);

	@Autowired
	private DatapackReleaseRequestService service;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_request");
	}

	private CreateReleaseRequestCommand cmd(String requester) {
		return new CreateReleaseRequestCommand("cand-1", "scope-1", "staging", SHA, SHA, SHA, requester);
	}

	@Test
	@DisplayName("create는 REQUESTED 요청을 저장하고 approvalId를 발급한다")
	void createsRequest() {
		var created = service.create(cmd("alice"));
		assertThat(created.approvalId()).startsWith("release-request-");
		assertThat(created.requestedBy()).isEqualTo("alice");
		assertThat(created.approvedBy()).isNull();
	}

	@Test
	@DisplayName("승인자=요청자이면 거부")
	void approveRejectsSameActor() {
		var created = service.create(cmd("alice"));
		assertThatThrownBy(() -> service.approve(created.approvalId(), "alice"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("approvedBy");
	}

	@Test
	@DisplayName("승인 성공 시 findApproved가 반환, 미승인은 empty")
	void findApprovedGatesOnApproval() {
		var created = service.create(cmd("alice"));
		assertThat(service.findApproved(created.approvalId())).isEmpty();
		service.approve(created.approvalId(), "bob");
		assertThat(service.findApproved(created.approvalId())).isPresent();
	}
}
