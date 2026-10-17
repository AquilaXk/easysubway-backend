import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { RUN_OUTPUT_FILES, SERVER_MAIN_CLASS, clearPreviousRun, parseArguments, serverCommand, waitForPortFile } from './journey-load-run.mjs';

const withDirectory = async (run) => {
  const directory = mkdtempSync(join(tmpdir(), 'journey-load-run-'));
  try { return await run(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
};

test('arguments require an absolute output directory and reject anything unknown', () => {
  assert.deepEqual(parseArguments(['--output-dir', '/tmp/out']), { outputDir: '/tmp/out', backendDir: 'backend' });
  assert.deepEqual(parseArguments(['--output-dir', '/tmp/out', '--backend-dir', 'other']), { outputDir: '/tmp/out', backendDir: 'other' });
  assert.throws(() => parseArguments([]), /--output-dir/);
  assert.throws(() => parseArguments(['--output-dir', 'relative/out']), /absolute/);
  assert.throws(() => parseArguments(['--output-dir', '/tmp/out', '--base-url', 'https://example.com']), /unknown option/);
  assert.throws(() => parseArguments(['--output-dir']), /named options/);
});

test('the server command runs the scenario server with a fixed heap and the port file', () => {
  const { command, args } = serverCommand({ javaExecutable: '/jdk/bin/java', classpath: ['/a.jar', '/b/classes'] }, '/out/server.port');
  assert.equal(command, '/jdk/bin/java');
  assert.deepEqual(args.slice(0, 3), ['-Xms1g', '-Xmx1g', '-Dfile.encoding=UTF-8']);
  assert.deepEqual(args.slice(3, 5), ['-cp', ['/a.jar', '/b/classes'].join(process.platform === 'win32' ? ';' : ':')]);
  assert.deepEqual(args.slice(5), [SERVER_MAIN_CLASS, '--port-file', '/out/server.port']);
});

test('the port file wait returns the port once the server wrote it', () => withDirectory(async (directory) => {
  const portFile = join(directory, 'server.port');
  setTimeout(() => writeFileSync(portFile, '45123'), 30);
  assert.equal(await waitForPortFile(portFile, { isServerAlive: () => true, timeoutMs: 2000, pollMs: 5 }), 45123);
}));

test('the port file wait fails fast when the server died, on a malformed port, and on timeout', () => withDirectory(async (directory) => {
  const portFile = join(directory, 'server.port');
  await assert.rejects(waitForPortFile(portFile, { isServerAlive: () => false, timeoutMs: 2000, pollMs: 5 }), /exited before/);
  writeFileSync(portFile, 'not a port');
  await assert.rejects(waitForPortFile(portFile, { isServerAlive: () => true, timeoutMs: 2000, pollMs: 5 }), /invalid port/);
  writeFileSync(portFile, '70000');
  await assert.rejects(waitForPortFile(portFile, { isServerAlive: () => true, timeoutMs: 2000, pollMs: 5 }), /invalid port/);
  await assert.rejects(waitForPortFile(join(directory, 'never'), { isServerAlive: () => true, timeoutMs: 40, pollMs: 5 }), /did not write/);
}));

test('a new run clears the previous run outputs so a stale port or summary can never be read', () => withDirectory((directory) => {
  for (const name of RUN_OUTPUT_FILES) writeFileSync(join(directory, name), 'stale');
  writeFileSync(join(directory, 'unrelated.txt'), 'keep');
  clearPreviousRun(directory);
  for (const name of RUN_OUTPUT_FILES) assert.equal(existsSync(join(directory, name)), false, name);
  assert.equal(existsSync(join(directory, 'unrelated.txt')), true);
  assert.doesNotThrow(() => clearPreviousRun(directory), 'clearing an already clean directory is fine');
  assert.ok(RUN_OUTPUT_FILES.includes('server.port') && RUN_OUTPUT_FILES.includes('k6-distilled.json'));
}));
