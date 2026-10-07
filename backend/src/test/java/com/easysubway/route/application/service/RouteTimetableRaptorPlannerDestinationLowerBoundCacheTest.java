package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.CompiledTimetable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #492: 도착역별 하한 배열 캐시의 계약. 캐시는 결과를 바꾸면 안 되므로 모든 도착역에서 새로 계산한 하한과 비트 단위로
 * 같아야 한다. 하한은 컴파일된 시간표와 도착역만의 함수라 캐시 수명은 시간표(런타임 세대) 수명과 같다.
 */
@DisplayName("#492 도착역별 하한 배열 캐시")
class RouteTimetableRaptorPlannerDestinationLowerBoundCacheTest {

	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("합성 번들의 모든 도착역에서 캐시한 하한이 새로 계산한 하한과 같다")
	void cachedBoundsEqualFreshBoundsForEveryDestinationOnSyntheticBundles() {
		for (long seed = 1; seed <= 40; seed += 1) {
			CompiledTimetable compiled = planner.compile(JourneyEngineSyntheticBundles.generate(seed).timetable());
			assertCachedEqualsFresh(compiled, "seed " + seed);
		}
	}

	@Test
	@DisplayName("수도권 실데이터 fixture의 모든 도착역에서 캐시한 하한이 새로 계산한 하한과 같다")
	void cachedBoundsEqualFreshBoundsForEveryDestinationOnCapitalFixture() {
		CompiledTimetable compiled = planner.compile(CapitalRealDerivedFixture.load());
		assertThat(compiled.stationCount()).isGreaterThan(600);
		assertCachedEqualsFresh(compiled, "capital");
	}

	@Test
	@DisplayName("같은 도착역을 다시 물으면 같은 배열을 돌려주고, 도착역마다 다른 배열이다")
	void returnsTheSameRowForTheSameDestination() {
		CompiledTimetable compiled = planner.compile(JourneyEngineSyntheticBundles.generate(7).timetable());
		int[] first = compiled.destinationLowerBounds(0);
		assertThat(compiled.destinationLowerBounds(0)).isSameAs(first);
		assertThat(compiled.destinationLowerBounds(1)).isNotSameAs(first);
		assertThat(compiled.destinationLowerBounds(0)).isSameAs(first);
	}

	@Test
	@DisplayName("범위를 벗어난 도착역은 캐시 행을 만들지 않고 명시적으로 실패한다")
	void rejectsOutOfRangeDestination() {
		CompiledTimetable compiled = planner.compile(JourneyEngineSyntheticBundles.generate(3).timetable());
		assertThatThrownBy(() -> compiled.destinationLowerBounds(-1)).isInstanceOf(IndexOutOfBoundsException.class);
		assertThatThrownBy(() -> compiled.destinationLowerBounds(compiled.stationCount()))
			.isInstanceOf(IndexOutOfBoundsException.class);
	}

	@Test
	@DisplayName("가상 스레드가 같은 도착역을 동시에 처음 물어도 모두 같은 행을 받고 값이 같다")
	void concurrentFirstAccessPublishesOneRow() throws Exception {
		CompiledTimetable compiled = planner.compile(CapitalRealDerivedFixture.load());
		int threads = 64;
		for (int destination : new int[] {0, compiled.stationCount() / 2, compiled.stationCount() - 1}) {
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				List<Future<int[]>> rows = new ArrayList<>();
				for (int index = 0; index < threads; index += 1) {
					int target = destination;
					rows.add(executor.submit(() -> {
						start.await();
						return compiled.destinationLowerBounds(target);
					}));
				}
				start.countDown();
				int[] winner = rows.getFirst().get();
				for (Future<int[]> row : rows) {
					assertThat(row.get()).isSameAs(winner);
				}
				assertThat(winner).containsExactly(RouteTimetableRaptorPlanner.computeStationLowerBounds(compiled, destination));
			}
		}
	}

	@Test
	@DisplayName("시간표(세대)를 놓으면 캐시한 행도 함께 회수된다")
	void rowsAreReleasedWithTheirCompiledTimetable() throws Exception {
		List<WeakReference<?>> references = cacheOneRowAndDropTimetable();
		for (int attempt = 0; attempt < 50 && references.stream().anyMatch(reference -> reference.get() != null);
			attempt += 1) {
			System.gc();
			Thread.sleep(20);
		}
		assertThat(references).allSatisfy(reference -> assertThat(reference.get()).isNull());
	}

	private List<WeakReference<?>> cacheOneRowAndDropTimetable() {
		CompiledTimetable compiled = planner.compile(JourneyEngineSyntheticBundles.generate(11).timetable());
		int[] row = compiled.destinationLowerBounds(0);
		assertThat(row).isNotEmpty();
		return List.of(new WeakReference<>(row), new WeakReference<>(compiled));
	}

	private static void assertCachedEqualsFresh(CompiledTimetable compiled, String label) {
		for (int destination = 0; destination < compiled.stationCount(); destination += 1) {
			int[] fresh = RouteTimetableRaptorPlanner.computeStationLowerBounds(compiled, destination);
			assertThat(compiled.destinationLowerBounds(destination))
				.as("%s destination %d", label, destination)
				.containsExactly(fresh);
		}
	}
}
