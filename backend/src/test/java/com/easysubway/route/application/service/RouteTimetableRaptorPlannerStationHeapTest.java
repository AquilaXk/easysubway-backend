package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.StationHeap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #462: 하한 Dijkstra 우선순위 큐({@link StationHeap})의 순서 계약. 최단 거리 값은 꺼내는 순서와 무관해 엔진 차분
 * 검증으로는 힙이 깨져도 결과가 같다(재삽입이 label-correcting으로 메운다). 그래서 힙 자체의 계약을 따로 단언한다:
 * 꺼낸 역의 거리는 그 시점 큐에 남은 역 거리의 최솟값이고, 역은 큐에 한 번만 있으며, 꺼낸 뒤 거리가 줄면 다시 들어간다.
 */
@DisplayName("#462 하한 Dijkstra 역 힙 순서 계약")
class RouteTimetableRaptorPlannerStationHeapTest {

	@Test
	@DisplayName("동률이 많은 무작위 키를 넣으면 거리가 줄지 않는 순서로 각 역을 한 번씩 꺼낸다")
	void pollsEveryStationOnceInNonDecreasingOrderWithTies() {
		Random random = new Random(462L);
		for (int trial = 0; trial < 200; trial += 1) {
			int[] distance = new int[1 + random.nextInt(64)];
			StationHeap heap = new StationHeap(distance);
			for (int station = 0; station < distance.length; station += 1) {
				distance[station] = random.nextInt(8);
				heap.decreased(station);
			}
			List<Integer> polled = new ArrayList<>();
			int previous = Integer.MIN_VALUE;
			while (!heap.isEmpty()) {
				int station = heap.poll();
				assertThat(distance[station]).isGreaterThanOrEqualTo(previous);
				previous = distance[station];
				polled.add(station);
			}
			assertThat(polled).hasSize(distance.length).doesNotHaveDuplicates();
		}
	}

	@Test
	@DisplayName("큐 안에서 거리가 줄면 위치를 고치고, 꺼낸 뒤 거리가 줄면 다시 넣어 항상 남은 최솟값을 꺼낸다")
	void decreaseKeyAndReinsertAfterPollKeepTheMinimumOnTop() {
		Random random = new Random(4_620_001L);
		for (int trial = 0; trial < 200; trial += 1) {
			int[] distance = new int[2 + random.nextInt(48)];
			Arrays.fill(distance, Integer.MAX_VALUE / 2);
			StationHeap heap = new StationHeap(distance);
			TreeSet<Integer> queued = new TreeSet<>();
			for (int step = 0; step < 400; step += 1) {
				if (!queued.isEmpty() && random.nextInt(3) == 0) {
					int expectedMinimum = queued.stream().mapToInt(station -> distance[station]).min().orElseThrow();
					int station = heap.poll();
					assertThat(queued.remove(station)).as("꺼낸 역은 큐에 있던 역이다").isTrue();
					assertThat(distance[station]).as("꺼낸 역은 남은 거리의 최솟값이다").isEqualTo(expectedMinimum);
				} else {
					int station = random.nextInt(distance.length);
					int lowered = Math.min(distance[station], 1_000) - random.nextInt(50);
					distance[station] = lowered;
					heap.decreased(station);
					queued.add(station);
				}
				assertThat(heap.isEmpty()).isEqualTo(queued.isEmpty());
			}
			int previous = Integer.MIN_VALUE;
			while (!heap.isEmpty()) {
				int station = heap.poll();
				assertThat(queued.remove(station)).isTrue();
				assertThat(distance[station]).isGreaterThanOrEqualTo(previous);
				previous = distance[station];
			}
			assertThat(queued).isEmpty();
		}
	}
}
