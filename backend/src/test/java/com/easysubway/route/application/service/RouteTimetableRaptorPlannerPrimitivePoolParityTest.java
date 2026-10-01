package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileRaptorPort.ConnectionSlack;
import com.easysubway.journey.application.JourneyProfileRaptorPort.MinimumTransferSeconds;
import com.easysubway.journey.application.JourneyProfileRaptorPort.NoTransfer;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PrimitiveProfileLabelPool dominance parity differential test")
class RouteTimetableRaptorPlannerPrimitivePoolParityTest {

	private static final NoTransfer NO_TRANSFER = new NoTransfer();

	@Test
	@DisplayName("명시 사례: 다른 값이 모두 같고 left=NoTransfer, right=MinimumTransferSeconds(0) -> true")
	void explicitNoTransferDominatesZeroSlackWhenOtherValuesEqual() {
		var pool = new RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool(16);

		int left = pool.allocate(
			1000, 2000, 1, 0, 0, (byte) 0,
			100, 200, 0, 0, -1, -1, -1, -1, -1, null, null, NO_TRANSFER);
		int right = pool.allocate(
			1000, 2000, 1, 0, 0, (byte) 0,
			100, 200, 0, 0, -1, -1, -1, -1, -1, null, null, new MinimumTransferSeconds(0));

		boolean refResult = refDominates(
			1000, 1000,
			2000, 2000,
			1, 1,
			(byte) 0, (byte) 0,
			100, 100,
			200, 200,
			0, 0,
			NO_TRANSFER, new MinimumTransferSeconds(0));
		assertThat(refResult).isTrue();

		boolean poolResult = RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.dominates(pool, left, right);
		assertThat(poolResult).isTrue();
	}

	@Test
	@DisplayName("10만 개 무작위 쌍에 대해 풀 지배 판정과 참조 지배 판정이 완전히 일치한다")
	void random100kPairsParity() {
		Random rng = new Random(20260929L);
		var pool = new RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool(256);

		for (int i = 0; i < 100_000; i++) {
			int lStart = rng.nextInt(5000);
			int rStart = rng.nextInt(5000);
			int lArr = lStart + rng.nextInt(5000);
			int rArr = rStart + rng.nextInt(5000);
			int lBoard = rng.nextInt(4);
			int rBoard = rng.nextInt(4);
			byte lWarn = (byte) rng.nextInt(8);
			byte rWarn = (byte) rng.nextInt(8);
			int lSec = rng.nextInt(1000);
			int rSec = rng.nextInt(1000);
			int lDist = rng.nextInt(2000);
			int rDist = rng.nextInt(2000);
			int lStairs = rng.nextInt(5);
			int rStairs = rng.nextInt(5);

			ConnectionSlack lSlack = randomSlack(rng);
			ConnectionSlack rSlack = randomSlack(rng);

			int lSlackInt = lSlack instanceof MinimumTransferSeconds m ? (int) m.seconds() : 0;
			int rSlackInt = rSlack instanceof MinimumTransferSeconds m ? (int) m.seconds() : 0;

			int left = pool.allocate(
				lStart, lArr, lBoard, 0, 0, lWarn,
				lSec, lDist, lStairs, lSlackInt, -1, -1, -1, -1, -1, null, null, lSlack);
			int right = pool.allocate(
				rStart, rArr, rBoard, 0, 0, rWarn,
				rSec, rDist, rStairs, rSlackInt, -1, -1, -1, -1, -1, null, null, rSlack);

			boolean expected = refDominates(
				lStart, rStart,
				lArr, rArr,
				lBoard, rBoard,
				lWarn, rWarn,
				lSec, rSec,
				lDist, rDist,
				lStairs, rStairs,
				lSlack, rSlack);

			boolean actual = RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.dominates(pool, left, right);
			assertThat(actual)
				.as("Pair %d dominance mismatch", i)
				.isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("2만 개 무작위 쌍에 대해 풀 동일 벡터 판정과 참조 판정이 완전히 일치한다")
	void randomSameVectorParity() {
		Random rng = new Random(20260930L);
		var pool = new RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool(256);

		for (int i = 0; i < 20_000; i++) {
			int lStart = rng.nextInt(100);
			int rStart = rng.nextBoolean() ? lStart : rng.nextInt(100);
			int lArr = rng.nextInt(100);
			int rArr = rng.nextBoolean() ? lArr : rng.nextInt(100);
			int lBoard = rng.nextInt(3);
			int rBoard = rng.nextBoolean() ? lBoard : rng.nextInt(3);
			byte lWarn = (byte) rng.nextInt(4);
			byte rWarn = rng.nextBoolean() ? lWarn : (byte) rng.nextInt(4);
			int lSec = rng.nextInt(50);
			int rSec = rng.nextBoolean() ? lSec : rng.nextInt(50);
			int lDist = rng.nextInt(100);
			int rDist = rng.nextBoolean() ? lDist : rng.nextInt(100);
			int lStairs = rng.nextInt(3);
			int rStairs = rng.nextBoolean() ? lStairs : rng.nextInt(3);
			ConnectionSlack lSlack = randomSlack(rng);
			ConnectionSlack rSlack = rng.nextBoolean() ? lSlack : randomSlack(rng);

			int left = pool.allocate(
				lStart, lArr, lBoard, 0, 0, lWarn,
				lSec, lDist, lStairs, 0, -1, -1, -1, -1, -1, null, null, lSlack);
			int right = pool.allocate(
				rStart, rArr, rBoard, 0, 0, rWarn,
				rSec, rDist, rStairs, 0, -1, -1, -1, -1, -1, null, null, rSlack);

			boolean expected = lStart == rStart
				&& lArr == rArr
				&& lBoard == rBoard
				&& lSec == rSec
				&& lDist == rDist
				&& lStairs == rStairs
				&& lWarn == rWarn
				&& ConnectionSlack.compareSafety(lSlack, rSlack) == 0;

			boolean actual = RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.sameVector(pool, left, right);
			assertThat(actual)
				.as("Pair %d sameVector mismatch", i)
				.isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("1만 개 무작위 체인에 대해 풀 traceKey 및 compareTrace가 참조 문자열 비교와 완전히 일치한다")
	void randomCompareTraceParity() {
		Random rng = new Random(20261001L);
		java.time.LocalDate baseDate = java.time.LocalDate.of(2026, 9, 29);

		for (int i = 0; i < 10_000; i++) {
			var pool = new RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool(16);
			int depthA = rng.nextInt(6);
			int depthB = rng.nextInt(6);

			int currA = pool.allocate(0, 0, 0, 0, 0, (byte) 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, null, null, NO_TRANSFER);
			String refKeyA = "";
			for (int d = 0; d < depthA; d++) {
				java.time.LocalDate date = baseDate.plusDays(rng.nextInt(2));
				int tripIdx = rng.nextInt(100);
				int fromStop = rng.nextInt(20);
				int toStop = rng.nextInt(20);
				int trans = rng.nextInt(10);
				currA = pool.allocate(0, 0, d + 1, 0, 0, (byte) 0, 0, 0, 0, 0, currA, tripIdx, fromStop, toStop, trans, date, null, NO_TRANSFER);
				refKeyA = refKeyA + "/" + date + ":" + tripIdx + ":" + fromStop + ":" + toStop + ":" + trans;
			}

			int currB = pool.allocate(0, 0, 0, 0, 0, (byte) 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, null, null, NO_TRANSFER);
			String refKeyB = "";
			for (int d = 0; d < depthB; d++) {
				java.time.LocalDate date = baseDate.plusDays(rng.nextInt(2));
				int tripIdx = rng.nextInt(100);
				int fromStop = rng.nextInt(20);
				int toStop = rng.nextInt(20);
				int trans = rng.nextInt(10);
				currB = pool.allocate(0, 0, d + 1, 0, 0, (byte) 0, 0, 0, 0, 0, currB, tripIdx, fromStop, toStop, trans, date, null, NO_TRANSFER);
				refKeyB = refKeyB + "/" + date + ":" + tripIdx + ":" + fromStop + ":" + toStop + ":" + trans;
			}

			String poolKeyA = RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.traceKey(pool, currA);
			String poolKeyB = RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.traceKey(pool, currB);
			assertThat(poolKeyA).isEqualTo(refKeyA);
			assertThat(poolKeyB).isEqualTo(refKeyB);

			int poolComp = RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.compareTrace(pool, currA, currB);
			int refComp = refKeyA.compareTo(refKeyB);
			assertThat(Integer.signum(poolComp))
				.as("Chain %d compareTrace signum mismatch", i)
				.isEqualTo(Integer.signum(refComp));
		}
	}

	static ConnectionSlack randomSlack(Random rng) {
		if (rng.nextInt(5) == 0) {
			return NO_TRANSFER;
		}
		return new MinimumTransferSeconds(rng.nextInt(601));
	}

	static boolean refDominates(
		int lStart, int rStart,
		int lArrival, int rArrival,
		int lBoardings, int rBoardings,
		byte lWarnings, byte rWarnings,
		int lAccessSec, int rAccessSec,
		int lAccessMeters, int rAccessMeters,
		int lStairs, int rStairs,
		ConnectionSlack lSlack,
		ConnectionSlack rSlack
	) {
		boolean noWorse = lStart >= rStart
			&& lArrival <= rArrival
			&& lAccessSec <= rAccessSec
			&& lAccessMeters <= rAccessMeters
			&& lStairs <= rStairs
			&& ConnectionSlack.compareSafety(lSlack, rSlack) >= 0
			&& lBoardings <= rBoardings
			&& (lWarnings & rWarnings) == lWarnings;

		if (!noWorse) {
			return false;
		}

		return lStart > rStart
			|| lArrival < rArrival
			|| lAccessSec < rAccessSec
			|| lAccessMeters < rAccessMeters
			|| lStairs < rStairs
			|| ConnectionSlack.compareSafety(lSlack, rSlack) > 0
			|| lBoardings < rBoardings
			|| lWarnings != rWarnings;
	}
}
