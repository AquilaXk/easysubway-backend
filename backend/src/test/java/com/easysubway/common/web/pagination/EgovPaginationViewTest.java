package com.easysubway.common.web.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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

	@Test
	@DisplayName("pagination link는 filter와 page size를 보존하고 빈 filter를 버린다")
	void buildsLinksWithFilters() {
		EgovPaginationView view = EgovPaginationView.from(1, 10, 42);

		EgovPaginationView.PaginationLinks links = view.links(
			"/admin/reports/page",
			Map.of("status", "SUBMITTED", "empty", "")
		);

		assertThat(links.previousHref()).isEqualTo("/admin/reports/page?status=SUBMITTED&page=0&size=10");
		assertThat(links.nextHref()).isEqualTo("/admin/reports/page?status=SUBMITTED&page=2&size=10");
		assertThat(links.pages().get(1).href()).isEqualTo("/admin/reports/page?status=SUBMITTED&page=1&size=10");
		assertThat(links.pages().get(1).current()).isTrue();
	}

	@Test
	@DisplayName("pagination link는 null filter를 버린다")
	void skipsNullFilters() {
		EgovPaginationView view = EgovPaginationView.from(0, 20, 21);

		EgovPaginationView.PaginationLinks links = view.links(
			"/admin/reports/page",
			Collections.singletonMap("status", null)
		);

		assertThat(links.nextHref()).isEqualTo("/admin/reports/page?page=1&size=20");
	}

	@Test
	@DisplayName("pagination link는 filter 값을 URL encode한다")
	void encodesFilterValues() {
		EgovPaginationView view = EgovPaginationView.from(0, 20, 21);

		EgovPaginationView.PaginationLinks links = view.links(
			"/admin/stations/page",
			Collections.singletonMap("query", "A&B 역")
		);

		assertThat(links.nextHref()).isEqualTo("/admin/stations/page?query=A%26B%20%EC%97%AD&page=1&size=20");
	}

	@Test
	@DisplayName("slice 조회는 size보다 많이 가져온 경우 다음 링크를 만든다")
	void buildsSliceWithNextPage() {
		EgovPaginationView view = EgovPaginationView.fromSlice(0, 20, 21);

		assertThat(view.hasNext()).isTrue();
		assertThat(view.nextPage()).isEqualTo(1);
	}

	@Test
	@DisplayName("slice 조회는 범위 밖 빈 page도 요청 page를 유지한다")
	void keepsRequestedPageForEmptySlice() {
		EgovPaginationView view = EgovPaginationView.fromSlice(3, 20, 0);

		assertThat(view.page()).isEqualTo(3);
		assertThat(view.hasPrevious()).isTrue();
		assertThat(view.hasNext()).isFalse();
		assertThat(view.previousPage()).isEqualTo(2);
		assertThat(view.pageLinks().get(view.pageLinks().size() - 1).current()).isTrue();
	}

	@Test
	@DisplayName("전체 목록에서 현재 page 항목만 반환한다")
	void returnsCurrentPageItems() {
		EgovPaginationView view = EgovPaginationView.from(1, 2, 5);

		assertThat(view.pageItems(List.of("a", "b", "c", "d", "e")))
			.containsExactly("c", "d");
	}

	@Test
	@DisplayName("관리자 page request는 size 상한과 offset overflow를 막는다")
	void adminPageRequestCapsSizeAndOffset() {
		AdminPageRequest request = AdminPageRequest.of(Integer.MAX_VALUE, 999);

		assertThat(request.size()).isEqualTo(AdminPageRequest.MAX_SIZE);
		assertThat(request.offset()).isGreaterThanOrEqualTo(0);
	}
}
