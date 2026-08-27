import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(
  new URL('../../.github/workflows/ci.yml', import.meta.url),
  'utf8',
);

const scannerAction =
  'SonarSource/sonarqube-scan-action@22918119ff8e1ca75a623e15c8296b6ea4fbe28f';

test('binds one exact-head SonarQube Cloud scan to current Backend evidence', () => {
  const backendJob = /^  backend:\n([\s\S]*?)(?=^  [a-z][a-z0-9-]+:|$(?![\s\S]))/m
    .exec(workflow)?.[0];
  assert.ok(backendJob, 'Backend CI job is required');
  assert.match(backendJob, /persist-credentials: false\n          fetch-depth: 0/);
  assert.equal(workflow.split(`uses: ${scannerAction}`).length - 1, 1);

  const scan = /^      - name: Analyze with SonarQube Cloud\n([\s\S]*?)(?=^      - name:|$(?![\s\S]))/m
    .exec(backendJob)?.[0];
  assert.ok(scan, 'SonarQube Cloud scan step is required');
  assert.match(scan, new RegExp(`uses: ${scannerAction.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
  assert.match(scan, /SONAR_TOKEN: \$\{\{ secrets\.SONAR_TOKEN \}\}/);
  assert.match(scan, /projectBaseDir: backend/);
  assert.match(
    scan,
    /github\.event_name == 'push' \|\| \(github\.event_name == 'pull_request' && github\.event\.pull_request\.head\.repo\.full_name == github\.repository\)/,
  );
  assert.doesNotMatch(scan, /github\.event_name != 'pull_request'/);
  assert.doesNotMatch(scan, /continue-on-error|pull_request_target|echo|printf/);

  const coverageAt = backendJob.indexOf('name: Enforce backend critical coverage verdict');
  const scanAt = backendJob.indexOf('name: Analyze with SonarQube Cloud');
  const imageAt = backendJob.indexOf('name: Build image without push');
  assert.ok(coverageAt >= 0 && coverageAt < scanAt && scanAt < imageAt);
});

test('binds SonarQube Cloud to production Java, tests, binaries, and JaCoCo', () => {
  const properties = readFileSync(
    new URL('../../backend/sonar-project.properties', import.meta.url),
    'utf8',
  );
  const lines = new Set(
    properties.split('\n').filter((line) => line !== '' && !line.startsWith('#')),
  );

  for (const line of [
    'sonar.projectKey=AquilaXk_easysubway-backend',
    'sonar.organization=aquilaxk',
    'sonar.sources=src/main/java,src/main/resources',
    'sonar.tests=src/test/java',
    'sonar.java.binaries=build/classes/java/main',
    'sonar.java.test.binaries=build/classes/java/test',
    'sonar.coverage.jacoco.xmlReportPaths=build/reports/jacoco/test/jacocoTestReport.xml',
    'sonar.coverage.exclusions=src/main/resources/**',
    'sonar.qualitygate.wait=true',
    'sonar.qualitygate.timeout=300',
  ]) {
    assert.ok(lines.has(line), `missing Sonar property: ${line}`);
  }
  assert.doesNotMatch(properties, /token|password|secret|fallback|previous/i);
});
