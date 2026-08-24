import { createHash } from "node:crypto";
import { lstatSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, extname, relative, resolve, sep } from "node:path";

const options = parseArguments(process.argv.slice(2));
const manifest = readJson(options.manifest);
const mirrorRoot = resolve(options.mirror);
const entries = listRegularFiles(mirrorRoot);
validateManifest(manifest);
const expected = new Map(manifest.artifacts.map((artifact) => [artifact.path, artifact]));
if (entries.length !== expected.size) fail("mirror artifact count differs from manifest");
for (const path of entries) {
  if (!expected.has(path)) fail(`unexpected mirror artifact: ${path}`);
}
for (const artifact of manifest.artifacts) verifyArtifact(artifact);
verifyBuildAndLock(manifest, readFileSync(options.build, "utf8"), readFileSync(options.lock, "utf8"));
const digest = createHash("sha256").update(manifest.artifacts.map((entry) => entry.sha256).join("\n")).digest("hex");
console.log(`verified ${manifest.artifacts.length} artifacts: sha256:${digest.slice(0, 16)}`);

function parseArguments(args) {
  const values = {};
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!/^--(manifest|mirror|build|lock)$/.test(key) || !value || values[key]) fail("expected unique --manifest --mirror --build --lock arguments");
    values[key] = resolve(value);
  }
  if (Object.keys(values).length !== 4 || args.length !== 8) fail("expected --manifest --mirror --build --lock arguments");
  return { manifest: values["--manifest"], mirror: values["--mirror"], build: values["--build"], lock: values["--lock"] };
}

function readJson(path) { try { return JSON.parse(readFileSync(path, "utf8")); } catch { fail("mirror manifest is not readable JSON"); } }
function fail(message) { throw new Error(`eGovFrame local Maven mirror verification failed: ${message}`); }

function listRegularFiles(root) {
  const result = [];
  const recurse = (directory) => {
    const info = lstatSync(directory);
    if (info.isSymbolicLink() || !info.isDirectory()) fail("mirror contains symlink or non-directory ancestor");
    for (const name of readdirSync(directory).sort()) {
      const path = resolve(directory, name); const item = lstatSync(path);
      if (item.isDirectory()) recurse(path); else if (item.isSymbolicLink() || !item.isFile()) fail("mirror contains symlink or non-regular entry");
      else { const rel = relative(root, path).split(sep).join("/"); if (!/^[a-z0-9][a-z0-9._/-]*\.(jar|pom)$/.test(rel) || rel.includes("..")) fail("mirror contains invalid artifact path"); result.push(rel); }
    }
  };
  recurse(root);
  return result.sort();
}

function validateManifest(value) {
  if (!value || value.schemaVersion !== 1 || value.mirrorRoot !== "backend/gradle/local-maven" || value?.provenance?.repository !== "https://maven.egovframe.go.kr/maven" || value?.provenance?.identity !== "official-egovframe-maven" || !Array.isArray(value.artifacts) || value.artifacts.length !== 21 || !Array.isArray(value.directBuildCoordinates)) fail("mirror manifest shape is invalid");
  const paths = new Set();
  for (const artifact of value.artifacts) {
    if (!artifact || typeof artifact.path !== "string" || !/^[a-z0-9][a-z0-9._/-]*\.(jar|pom)$/.test(artifact.path) || paths.has(artifact.path) || !Number.isSafeInteger(artifact.size) || artifact.size <= 0 || !/^[a-f0-9]{64}$/.test(artifact.sha256) || !/^(org\.egovframe\.(boot|rte)):[a-z0-9.-]+:[0-9.]+$/.test(artifact.coordinate) || artifact.provenance !== "official-egovframe-maven") fail("mirror manifest artifact is invalid");
    if (artifact.path !== coordinatePath(artifact.coordinate, extname(artifact.path))) fail("mirror manifest path does not derive from coordinate");
    paths.add(artifact.path);
  }
  if (new Set(value.directBuildCoordinates).size !== value.directBuildCoordinates.length || value.directBuildCoordinates.some((coordinate) => !/^(org\.egovframe\.(boot|rte)):[a-z0-9.-]+:[0-9.]+$/.test(coordinate))) fail("mirror manifest direct build coordinates are invalid");
}

function coordinatePath(coordinate, extension) {
  const [group, artifact, version] = coordinate.split(":");
  return `${group.replaceAll(".", "/")}/${artifact}/${version}/${artifact}-${version}${extension}`;
}

function verifyArtifact(artifact) {
  const path = resolve(mirrorRoot, artifact.path);
  if (!path.startsWith(`${mirrorRoot}${sep}`) || lstatSync(path).isSymbolicLink() || !lstatSync(path).isFile()) fail(`missing or non-regular artifact: ${artifact.path}`);
  if (statSync(path).size !== artifact.size) fail(`artifact size differs: ${artifact.path}`);
  const sha256 = createHash("sha256").update(readFileSync(path)).digest("hex");
  if (sha256 !== artifact.sha256) fail(`artifact digest differs: ${artifact.path}`);
  if (artifact.path.endsWith(".pom")) {
    const pom = readFileSync(path, "utf8"); const [, group, module, version] = artifact.coordinate.match(/^(.+):(.+):(.+)$/);
    for (const [tag, value] of [["groupId", group], ["artifactId", module], ["version", version]]) if (!new RegExp(`<${tag}>\\s*${escapeRegExp(value)}\\s*</${tag}>`).test(pom)) fail(`POM coordinate differs: ${artifact.path}`);
  }
}

function verifyBuildAndLock(manifest, build, lock) {
  const artifactCoordinates = new Set(manifest.artifacts.map((item) => item.coordinate));
  const manifestRteCoordinates = new Set([...artifactCoordinates].filter((coordinate) => coordinate.startsWith("org.egovframe.rte:")));
  const lockRteCoordinates = new Set([...lock.matchAll(/^(org\.egovframe\.rte:[a-z0-9.-]+:[0-9.]+)=/gmu)].map((match) => match[1]));
  if (manifestRteCoordinates.size !== lockRteCoordinates.size
    || [...manifestRteCoordinates].some((coordinate) => !lockRteCoordinates.has(coordinate))) {
    fail("mirror artifact and lock coordinate sets differ");
  }

  const lockedCoordinatesByModule = new Map();
  for (const coordinate of lockRteCoordinates) {
    const [, artifact] = coordinate.split(":");
    if (lockedCoordinatesByModule.has(artifact)) fail(`lock has multiple versions for direct module: ${artifact}`);
    lockedCoordinatesByModule.set(artifact, coordinate);
  }
  const directBuildCoordinates = new Set();
  for (const line of build.split("\n")) {
    const declaration = line.replace(/\/\/.*$/u, "");
    const bom = declaration.match(/^\s*mavenBom\s+'(org\.egovframe\.boot:[a-z0-9.-]+:[0-9.]+)'\s*$/u);
    if (bom) directBuildCoordinates.add(bom[1]);
    const implementation = declaration.match(/^\s*implementation\s+'org\.egovframe\.rte:([a-z0-9.-]+)(?::([0-9.]+))?'\s*$/u);
    if (implementation) {
      const [, artifact, version] = implementation;
      const coordinate = version ? `org.egovframe.rte:${artifact}:${version}` : lockedCoordinatesByModule.get(artifact);
      if (!coordinate) fail(`lock declaration missing for direct module: ${artifact}`);
      directBuildCoordinates.add(coordinate);
    }
  }
  const expectedDirectCoordinates = new Set(manifest.directBuildCoordinates);
  if (directBuildCoordinates.size !== expectedDirectCoordinates.size
    || [...directBuildCoordinates].some((coordinate) => !expectedDirectCoordinates.has(coordinate))) {
    fail("build and manifest direct coordinate sets differ");
  }
  for (const coordinate of expectedDirectCoordinates) {
    if (!artifactCoordinates.has(coordinate)) fail(`mirror artifacts missing direct coordinate: ${coordinate}`);
  }
}
function escapeRegExp(value) { return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }
