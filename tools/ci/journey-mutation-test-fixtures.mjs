// 경로 뮤테이션 추세 테스트들이 같이 쓰는 PIT 결과·컨텍스트 fixture (backend #523).
import { context } from './journey-benchmark-test-fixtures.mjs';
import { buildMutationResult, parseMutations } from './journey-mutation-result.mjs';

export const OVERLAY = 'com.easysubway.route.application.service.JourneyFacilityBlockOverlay';
export const DISPOSITION = 'com.easysubway.journey.application.JourneyExecutionDisposition';
const MUTATOR_PACKAGE = 'org.pitest.mutationtest.engine.gregor.mutators';

export const RUN = {
  pitestVersion: '1.30.0',
  junit5PluginVersion: '1.2.3',
  targetClasses: [`${OVERLAY}*`, `${DISPOSITION}*`],
  threads: 2,
  elapsedSeconds: 254,
};

/** PIT mutations.xml의 <mutation> 한 건. 생존·미커버 mutant는 killingTest가 빈 요소다. */
export const mutantXml = ({
  status = 'KILLED', mutatedClass = OVERLAY, method = 'capture', line = 10,
  mutator = `${MUTATOR_PACKAGE}.ConditionalsBoundaryMutator`, description = 'changed conditional boundary',
} = {}) => {
  const detected = ['KILLED', 'TIMED_OUT', 'MEMORY_ERROR'].includes(status);
  const sourceFile = `${mutatedClass.split('$')[0].split('.').at(-1)}.java`;
  const killing = status === 'KILLED' ? '<killingTest>SomeTest.[method:works()]</killingTest>' : '<killingTest/>';
  return `<mutation detected='${detected}' status='${status}' numberOfTestsRun='3'><sourceFile>${sourceFile}</sourceFile><mutatedClass>${mutatedClass}</mutatedClass>`
    + `<mutatedMethod>${method}</mutatedMethod><methodDescription>(I)V</methodDescription><lineNumber>${line}</lineNumber><mutator>${mutator}</mutator>`
    + `<indexes><index>1</index></indexes><blocks><block>0</block></blocks>${killing}<description>${description}</description></mutation>`;
};

export const mutationsXml = (mutants) => `<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="true">\n${mutants.map((mutant) => `${mutantXml(mutant)}\n`).join('')}</mutations>\n`;

/** 클래스마다 killed·survived 개수를 정해 주는 mutant 목록. */
export const mutantsFor = (className, { killed = 0, survived = 0, noCoverage = 0 } = {}) => [
  ...Array.from({ length: killed }, (_, index) => ({ mutatedClass: className, line: 10 + index })),
  ...Array.from({ length: survived }, (_, index) => ({ mutatedClass: className, status: 'SURVIVED', line: 100 + index })),
  ...Array.from({ length: noCoverage }, (_, index) => ({ mutatedClass: className, status: 'NO_COVERAGE', line: 200 + index })),
];

export const result = (specs = { [OVERLAY]: { killed: 8, survived: 2 }, [DISPOSITION]: { killed: 4 } }, { run = RUN, contextOverrides = {} } = {}) => buildMutationResult({
  mutants: parseMutations(mutationsXml(Object.entries(specs).flatMap(([className, counts]) => mutantsFor(className, counts)))),
  run,
  context: context(contextOverrides),
});

/** 직전 run n번째 결과. runId와 sha가 서로 달라야 summary 링크를 구분할 수 있다. */
export const previousRun = (index, specs, options = {}) => result(specs, {
  ...options,
  contextOverrides: { commitSha: String(index).repeat(40), runId: String(900 + index), runUrl: `https://github.com/AquilaXk/easysubway-backend/actions/runs/${900 + index}`, ...options.contextOverrides },
});
