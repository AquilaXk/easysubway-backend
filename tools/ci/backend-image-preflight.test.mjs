import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const workflowUrl = new URL('../../.github/workflows/release-artifacts.yml', import.meta.url);
const dockerfileUrl = new URL('../../backend/Dockerfile', import.meta.url);
const dockerignoreUrl = new URL('../../backend/.dockerignore', import.meta.url);

test('image preflight는 source-free non-root read-only runtime isolation을 실측한다', async () => {
  const [workflow, dockerfile, dockerignore] = await Promise.all([
    readFile(workflowUrl, 'utf8'),
    readFile(dockerfileUrl, 'utf8'),
    readFile(dockerignoreUrl, 'utf8'),
  ]);
  assert.equal(dockerignore, '*\n!Dockerfile\n!build\n!build/libs\n!build/libs/*.jar\n');
  assert.deepEqual(dockerfile.match(/^[\t ]*COPY[\t ]+.+$/gim), [
    'COPY --chown=10001:10001 build/libs/ /tmp/jars/',
  ]);
  assert.doesNotMatch(dockerfile, /^[\t ]*ADD\b/im);

  const preflight = workflow.slice(0, workflow.indexOf('\n  backend-release:'));
  const step = preflight.match(
    /- name: Verify source-free non-root read-only image\n([\s\S]*?)(?=\n      - name:|$)/,
  )?.[1];

  assert.ok(step, 'runtime isolation preflight step must exist');
  for (const contract of [
    'container="$(docker create "${image}")"',
    'rootfs_listing="${RUNNER_TEMP}/backend-image-rootfs.txt"',
    'docker export "${container}" | tar -tf - > "${rootfs_listing}"',
    "source_or_build_pattern='(^|/)(gradle|gradlew(\\.bat)?|javac|gradle-wrapper\\.jar)$|(^|/)gradle/wrapper/|\\.(java|kt|kts|groovy|gradle)$'",
    'grep -Eiq "\\.class$|${source_or_build_pattern}" "${rootfs_listing}"',
    'elif [[ "$?" -ne 1 ]]; then',
    'backend runtime image source/build-tool scan failed',
    'app_archive="${RUNNER_TEMP}/backend-image-app.jar"',
    'archive_listing="${RUNNER_TEMP}/backend-image-app-archive.txt"',
    'archive_dir="${RUNNER_TEMP}/backend-image-app-archive"',
    'nested_archives="${RUNNER_TEMP}/backend-image-nested-archives.txt"',
    'nested_archive_listing="${RUNNER_TEMP}/backend-image-nested-archive.txt"',
    'docker cp "${container}:/app/app.jar" "${app_archive}"',
    'jar tf "${app_archive}" > "${archive_listing}"',
    'mkdir -p "${archive_dir}"',
    '(cd "${archive_dir}" && jar xf "${app_archive}")',
    'find "${archive_dir}" -type f -iname \'*.jar\' -print0 > "${nested_archives}"',
    ': > "${nested_archive_listing}"',
    'while IFS= read -r -d \'\' nested_archive; do',
    'jar tf "${nested_archive}" >> "${nested_archive_listing}"',
    'done < "${nested_archives}"',
    'grep -Eiq "${source_or_build_pattern}|\\.(zip|war|ear|jmod)$" "${archive_listing}"',
    'backend runtime application archive contains source or build files',
    'backend runtime application archive scan failed',
    'grep -Eiq "${source_or_build_pattern}|\\.(jar|zip|war|ear|jmod)$" "${nested_archive_listing}"',
    'backend runtime dependency archive contains source, build files, or a deeper archive',
    'backend runtime dependency archive scan failed',
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
  assert.ok(
    step.indexOf('docker cp') < step.indexOf('docker rm "${container}"'),
    'application archive inspection must finish before container cleanup',
  );
});
