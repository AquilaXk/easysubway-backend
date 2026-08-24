import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const verifier = join(repositoryRoot, "backend/tools/verify-egovframe-local-maven-mirror.mjs");
const manifest = join(repositoryRoot, "backend/quality/egovframe-local-maven-mirror.json");
const mirror = join(repositoryRoot, "backend/gradle/local-maven");
const build = join(repositoryRoot, "backend/build.gradle");
const lock = join(repositoryRoot, "backend/gradle.lockfile");

test("local eGovFrame Maven mirror는 exact 21-file inventory를 검증한다", () => {
  assert.match(run(), /verified 21 artifacts: sha256:[a-f0-9]{16}/);
});

test("Java compilation은 local eGovFrame Maven mirror 검증 뒤에 실행된다", () => {
  assert.match(readFileSync(build, "utf8"), /tasks\.withType\(JavaCompile\)\.configureEach\s*\{\s*dependsOn verifyEgovframeLocalMavenMirror\s*\}/u);
});

test("local eGovFrame Maven mirror는 주석과 문자열의 direct coordinate를 무시한다", () => {
  const fixture = createFixture();
  try {
    writeFileSync(fixture.build, `${readFileSync(fixture.build, "utf8")}\n/*\nruntimeOnly 'org.egovframe.rte:egovframe-rte-psl-dataaccess'\n*/\ndef example = \"\"\"\ncompileOnly 'org.egovframe.rte:egovframe-rte-psl-dataaccess'\n\"\"\"\n`);
    assert.match(run(fixture), /verified 21 artifacts/u);
  } finally {
    fixture.cleanup();
  }
});

for (const [name, mutate] of [
  ["JAR one-byte mutation", (root) => mutateByte(join(root, "org/egovframe/rte/egovframe-rte-bat-core/5.0.0/egovframe-rte-bat-core-5.0.0.jar"))],
  ["POM one-byte mutation", (root) => mutateByte(join(root, "org/egovframe/rte/egovframe-rte-bat-core/5.0.0/egovframe-rte-bat-core-5.0.0.pom"))],
  ["missing artifact", (root) => unlinkSync(join(root, "org/egovframe/rte/egovframe-rte-bat-core/5.0.0/egovframe-rte-bat-core-5.0.0.jar"))],
  ["extra artifact", (root) => writeFileSync(join(root, "unexpected.jar"), "x")],
  ["non-regular artifact", (root) => { const path = join(root, "org/egovframe/rte/egovframe-rte-bat-core/5.0.0/egovframe-rte-bat-core-5.0.0.jar"); unlinkSync(path); mkdirSync(path); }],
  ["path-coordinate mismatch", (_root, fixture) => writeFileSync(fixture.manifest, readFileSync(fixture.manifest, "utf8").replace("egovframe-rte-bat-core-5.0.0.jar", "renamed.jar"))],
  ["lock drift", (_root, fixture) => writeFileSync(fixture.lock, readFileSync(fixture.lock, "utf8").replace("org.egovframe.rte:egovframe-rte-bat-core:5.0.0", "org.egovframe.rte:egovframe-rte-bat-core:9.9.9"))],
  ["build declaration drift", (_root, fixture) => writeFileSync(fixture.build, readFileSync(fixture.build, "utf8").replace("egovframe-rte-bat-core'", "egovframe-rte-bat-core:9.9.9'"))],
  ["direct coordinate without mirror artifacts", (_root, fixture) => {
    const coordinate = "org.egovframe.rte:egovframe-rte-extra:5.0.0";
    const value = JSON.parse(readFileSync(fixture.manifest, "utf8"));
    value.directBuildCoordinates.push(coordinate);
    writeFileSync(fixture.manifest, `${JSON.stringify(value, null, 2)}\n`);
    writeFileSync(fixture.build, `${readFileSync(fixture.build, "utf8")}\nimplementation 'org.egovframe.rte:egovframe-rte-extra'\n`);
    writeFileSync(fixture.lock, `${readFileSync(fixture.lock, "utf8")}\n${coordinate}=compileClasspath\n`);
  }],
  ["untracked direct build coordinate", (_root, fixture) => {
    writeFileSync(fixture.build, `${readFileSync(fixture.build, "utf8")}\nimplementation 'org.egovframe.rte:egovframe-rte-psl-dataaccess'\n`);
  }],
  ["runtimeOnly direct build coordinate", (_root, fixture) => {
    writeFileSync(fixture.build, `${readFileSync(fixture.build, "utf8")}\nruntimeOnly 'org.egovframe.rte:egovframe-rte-psl-dataaccess'\n`);
  }],
  ["compileOnly direct build coordinate", (_root, fixture) => {
    writeFileSync(fixture.build, `${readFileSync(fixture.build, "utf8")}\ncompileOnly 'org.egovframe.rte:egovframe-rte-psl-dataaccess'\n`);
  }],
  ["testImplementation direct build coordinate with double-quoted call", (_root, fixture) => {
    writeFileSync(fixture.build, `${readFileSync(fixture.build, "utf8")}\ntestImplementation(\"org.egovframe.rte:egovframe-rte-psl-dataaccess\")\n`);
  }],
]) {
  test(`local eGovFrame Maven mirror rejects ${name}`, () => {
    const fixture = createFixture();
    try {
      mutate(fixture.mirror, fixture);
      assert.throws(() => run(fixture), /mirror|artifact|path|lock|build/i);
    } finally {
      fixture.cleanup();
    }
  });
}

function createFixture() {
  const directory = mkdtempSync(join(tmpdir(), "egovframe-mirror-"));
  const fixture = { directory, mirror: join(directory, "mirror"), manifest: join(directory, "manifest.json"), build: join(directory, "build.gradle"), lock: join(directory, "gradle.lockfile") };
  cpSync(mirror, fixture.mirror, { recursive: true });
  cpSync(manifest, fixture.manifest);
  cpSync(build, fixture.build);
  cpSync(lock, fixture.lock);
  fixture.cleanup = () => rmSync(directory, { recursive: true, force: true });
  return fixture;
}

function mutateByte(path) {
  const bytes = readFileSync(path);
  bytes[0] ^= 1;
  writeFileSync(path, bytes);
}

function run(fixture = { mirror, manifest, build, lock }) {
  return execFileSync("node", [verifier, "--manifest", fixture.manifest, "--mirror", fixture.mirror, "--build", fixture.build, "--lock", fixture.lock], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}
