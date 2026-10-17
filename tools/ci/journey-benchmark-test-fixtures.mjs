// 경로 엔진 벤치마크 추세 테스트들이 같이 쓰는 측정·컨텍스트 fixture (backend #516).
import { buildResult } from './journey-benchmark-result.mjs';

export const SHA = 'a'.repeat(40);
export const OTHER_SHA = 'e'.repeat(40);
export const BUNDLE = 'b'.repeat(64);
export const QUERIES = 'c'.repeat(64);
export const RUN_URL = 'https://github.com/AquilaXk/easysubway-backend/actions/runs/101';

// JourneyEnginePerformanceGateTest.measure()가 쓰는 JSON 모양의 최소 사본.
export const measurement = ({ calibration = 100_000_000, p50 = 1_000, p99 = 4_000, query = 10_000, compile = 50_000_000, compileBytes = 20_000_000, bundle = BUNDLE, queries = QUERIES, work = 7, method = {} } = {}) => ({
  schemaVersion: 1,
  method: {
    warmupRounds: 2,
    measuredRounds: 5,
    aggregation: 'median over measured rounds of per-round nearest-rank p50 and p99',
    profileLimits: { maxEstimatedWork: 5_000_000, maxLabelsPerState: 64, maxDestinationProfileLabels: 64, maxProfileBreakpoints: 64 },
    java: 'Eclipse Adoptium 21.0.11',
    arch: 'amd64',
    processors: 4,
    ...method,
  },
  calibrationNanos: calibration,
  workloads: {
    'metro-grid-v1': {
      bundle: { sha256: bundle, stations: 192, trips: 7298, stopTimes: 110384, transferRules: 128 },
      querySet: { id: 'metro-grid-queries-v1', count: 160, sha256: queries },
      compile: { nanos: compile, allocatedBytes: compileBytes, retainedBytes: 3_000_000 },
      modes: { DEPART_AT: { queries: 160, p50Nanos: p50, p99Nanos: p99, samplesPerRound: 160, allocatedBytesPerQuery: query, work: { expandedRoutes: work } } },
      candidatePolicyReplay: { source: 'platform@sha', modes: { ARRIVE_BY: { queries: 12, failClosed: 0 } } },
    },
  },
});

export const context = (overrides = {}) => ({
  commitSha: SHA, ref: 'main', event: 'push', runId: '101', runAttempt: '1', runUrl: RUN_URL,
  measuredAt: '2026-10-17T00:00:00.000Z',
  runner: { name: 'GitHub Actions 7', os: 'Linux', arch: 'X64', image: 'ubuntu24', imageVersion: '20260101.1', cpuModel: 'AMD EPYC 7763', cpuCount: 4, totalMemoryBytes: 16_777_216_000 },
  ...overrides,
});

export const result = (m = measurement(), overrides = {}) => buildResult({ measurement: m, repeats: 3, context: context(overrides) });

/** 직전 run n번째 결과. runId와 sha가 서로 달라야 summary 링크를 구분할 수 있다. */
export const previousRun = (index, m = measurement()) => result(m, {
  commitSha: String(index).repeat(40), runId: String(900 + index), runUrl: `https://github.com/AquilaXk/easysubway-backend/actions/runs/${900 + index}`,
});
