package com.easysubway.common.web.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("eGovFrame pagination view")
class EgovPaginationViewTest {

	@Test
	@DisplayName("0-based URL page를 1-based page link로 변환한다")
	void convertsZeroBasedPageToOneBasedLinks() {
		EgovPaginationView view = EgovPaginationView.from(0, 10, 42);

		assertThat(view.page()).isZero();
		assertThat(view.hasPrevious()).isFalse();
		assertThat(view.hasNext()).isTrue();
		assertThat(view.pageLinks())
			.extracting(EgovPaginationView.PageLink::page, EgovPaginationView.PageLink::label)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(0, "1"),
				org.assertj.core.groups.Tuple.tuple(1, "2"),
				org.assertj.core.groups.Tuple.tuple(2, "3"),
				org.assertj.core.groups.Tuple.tuple(3, "4"),
				org.assertj.core.groups.Tuple.tuple(4, "5")
			);
		assertThat(view.pageLinks().get(0).current()).isTrue();
	}

	@Test
	@DisplayName("중간 페이지는 eGovFrame page block과 이전 다음 링크를 계산한다")
	void calculatesMiddlePageBlock() {
		EgovPaginationView view = EgovPaginationView.from(6, 10, 120);

		assertThat(view.page()).isEqualTo(6);
		assertThat(view.previousPage()).isEqualTo(5);
		assertThat(view.nextPage()).isEqualTo(7);
		assertThat(view.pageLinks())
			.extracting(EgovPaginationView.PageLink::page)
			.containsExactly(5, 6, 7, 8, 9);
		assertThat(view.pageLinks().get(1).current()).isTrue();
	}

	@Test
	@DisplayName("마지막 페이지는 다음 링크를 만들지 않는다")
	void calculatesLastPage() {
		EgovPaginationView view = EgovPaginationView.from(4, 10, 42);

		assertThat(view.page()).isEqualTo(4);
		assertThat(view.hasPrevious()).isTrue();
		assertThat(view.hasNext()).isFalse();
		assertThat(view.nextPage()).isEqualTo(4);
		assertThat(view.pageLinks().get(view.pageLinks().size() - 1).current()).isTrue();
	}

	@Test
	@DisplayName("빈 결과는 번호 링크를 만들지 않는다")
	void hidesLinksForEmptyResult() {
		EgovPaginationView view = EgovPaginationView.from(0, 10, 0);

		assertThat(view.page()).isZero();
		assertThat(view.hasPages()).isFalse();
		assertThat(view.pageLinks()).isEmpty();
		assertThat(view.hasPrevious()).isFalse();
		assertThat(view.hasNext()).isFalse();
	}

	@Test
	@DisplayName("범위를 벗어난 page는 마지막 page로 보정한다")
	void clampsOutOfRangePage() {
		EgovPaginationView view = EgovPaginationView.from(99, 10, 42);

		assertThat(view.page()).isEqualTo(4);
		assertThat(view.hasNext()).isFalse();
		assertThat(view.pageLinks().get(view.pageLinks().size() - 1).page()).isEqualTo(4);
	}
}
