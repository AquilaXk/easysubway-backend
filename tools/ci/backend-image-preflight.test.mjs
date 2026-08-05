import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const workflowUrl = new URL('../../.github/workflows/release-artifacts.yml', import.meta.url);

test('image preflight는 source-free non-root read-only runtime isolation을 실측한다', async () => {
  const workflow = await readFile(workflowUrl, 'utf8');
  const preflight = workflow.slice(0, workflow.indexOf('\n  backend-release:'));
  const step = preflight.match(
    /- name: Verify source-free non-root read-only image\n([\s\S]*?)(?=\n      - name:|$)/,
  )?.[1];

  assert.ok(step, 'runtime isolation preflight step must exist');
  for (const contract of [
    'container="$(docker create "${image}")"',
    'rootfs_listing="${RUNNER_TEMP}/backend-image-rootfs.txt"',
    'docker export "${container}" | tar -tf - > "${rootfs_listing}"',
    "source_or_tool_pattern='(^|/)(gradle|gradlew(\\.bat)?|javac|gradle-wrapper\\.jar)$|(^|/)gradle/wrapper/|\\.(java|kt|kts|groovy|gradle|class)$'",
    'grep -Eq "${source_or_tool_pattern}" "${rootfs_listing}"',
    'elif [[ "$?" -ne 1 ]]; then',
    'backend runtime image source/build-tool scan failed',
    'docker rm "${container}"',
    'trap - EXIT',
    'docker run --rm',
    '--read-only',
    '--network none',
    '--cap-drop ALL',
    '--security-opt no-new-privileges:true',
    '--tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m,uid=10001,gid=10001',
    '--tmpfs /opt/easysubway/logs:rw,noexec,nosuid,nodev,size=64m,uid=10001,gid=10001',
    'test "$(id -u)" = "10001"',
    'test "$(id -g)" = "10001"',
    'find /app -mindepth 1 -maxdepth 1 -printf',
    'test "$(stat -c "%u:%g" /app/app.jar)" = "10001:10001"',
    '! touch /app/app.jar',
    'touch /tmp/runtime-write-check',
    'touch /opt/easysubway/logs/runtime-write-check',
    'test ! -e /var/run/docker.sock',
  ]) {
    assert.ok(step.includes(contract), `missing image isolation contract: ${contract}`);
  }

  assert.doesNotMatch(step, /--privileged|--volume|--cap-add(?:=|\s|$)|^\s+-v(?:\s|$)/m);
  assert.doesNotMatch(step, /find \/ -xdev|command -v (?:gradle|javac)|\/app\/read-only-check/);
  assert.ok(
    step.indexOf('docker create') < step.indexOf('docker run --rm'),
    'merged rootfs inspection must precede the runtime check',
  );
});
