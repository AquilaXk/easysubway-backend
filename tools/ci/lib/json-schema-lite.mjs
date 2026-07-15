const SUPPORTED = new Set([
  "$id",
  "$schema",
  "additionalProperties",
  "const",
  "description",
  "enum",
  "format",
  "items",
  "minItems",
  "minimum",
  "pattern",
  "properties",
  "required",
  "title",
  "type",
]);

export function validateSchema(schema, value) {
  const errors = [];
  walk(schema, value, "$", errors);
  return { ok: errors.length === 0, errors };
}

function walk(schema, value, path, errors) {
  assertSupported(schema, path);
  if (validateScalar(schema, value, path, errors)) return;
  validateObject(schema, value, path, errors);
  validateArray(schema, value, path, errors);
}

function assertSupported(schema, path) {
  for (const key of Object.keys(schema)) {
    if (!SUPPORTED.has(key)) throw new Error(`json-schema-lite: 미지원 키워드 '${key}' (${path})`);
  }
}

function validateScalar(schema, value, path, errors) {
  if (schema.const !== undefined && value !== schema.const) {
    errors.push(`${path}: const ${JSON.stringify(schema.const)} 불일치`);
    return true;
  }
  if (schema.enum && !schema.enum.includes(value)) {
    errors.push(`${path}: enum ${JSON.stringify(schema.enum)} 밖의 값`);
    return true;
  }
  if (schema.type && !matchesType(schema.type, value)) {
    errors.push(`${path}: type ${schema.type} 불일치`);
    return true;
  }
  if (schema.type === "string" && schema.pattern && !new RegExp(schema.pattern).test(value)) {
    errors.push(`${path}: pattern ${schema.pattern} 불일치`);
  }
  if (schema.type === "string" && schema.format && !matchesFormat(schema.format, value)) {
    errors.push(`${path}: format ${schema.format} 불일치`);
  }
  if (typeof value === "number" && schema.minimum !== undefined && value < schema.minimum) {
    errors.push(`${path}: minimum ${schema.minimum} 미만`);
  }
  return false;
}

function matchesFormat(format, value) {
  if (format === "date") {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const date = new Date(`${value}T00:00:00.000Z`);
    return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
  }
  if (format === "date-time") {
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})$/.exec(value);
    if (!match) return false;
    const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
    const offset = match[7];
    const maxDay = [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
    if (month < 1 || month > 12 || day < 1 || day > maxDay
      || hour > 23 || minute > 59 || second > 59) return false;
    if (offset !== "Z") {
      const [offsetHour, offsetMinute] = offset.slice(1).split(":").map(Number);
      if (offsetHour > 23 || offsetMinute > 59) return false;
    }
    return true;
  }
  if (format === "uri") {
    if (!isRawUri(value)) return false;
    try {
      const parsed = new URL(value);
      return parsed.protocol !== "";
    } catch {
      return false;
    }
  }
  throw new Error(`json-schema-lite: 미지원 format '${format}'`);
}

function isRawUri(value) {
  return /^[A-Za-z0-9:/?#\[\]@!$&'()*+,;=._~%\-]+$/.test(value)
    && !/%(?![0-9A-Fa-f]{2})/.test(value);
}

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function validateObject(schema, value, path, errors) {
  if (schema.type !== "object") {
    if (schema.properties || schema.required) {
      throw new Error(`json-schema-lite: properties/required 사용 시 type: object 명시 필요 (${path})`);
    }
    return;
  }
  if (value === null || typeof value !== "object" || Array.isArray(value)) return;
  for (const req of schema.required ?? []) {
    if (!(req in value)) errors.push(`${dot(path, req)}: 필수 필드 누락`);
  }
  for (const [key, child] of Object.entries(value)) {
    const propSchema = schema.properties?.[key];
    if (propSchema) walk(propSchema, child, dot(path, key), errors);
    else if (schema.additionalProperties === false) errors.push(`${dot(path, key)}: 허용되지 않은 필드`);
  }
}

function validateArray(schema, value, path, errors) {
  if (schema.type !== "array") {
    if (schema.items || schema.minItems !== undefined) {
      throw new Error(`json-schema-lite: items/minItems 사용 시 type: array 명시 필요 (${path})`);
    }
    return;
  }
  if (!Array.isArray(value)) return;
  if (schema.minItems !== undefined && value.length < schema.minItems) {
    errors.push(`${path}: minItems ${schema.minItems} 미만`);
  }
  if (schema.items) value.forEach((item, i) => walk(schema.items, item, dot(path, String(i)), errors));
}

function matchesType(type, value) {
  if (Array.isArray(type)) {
    if (type.length === 0) throw new Error("json-schema-lite: type 배열은 비어 있을 수 없습니다");
    return type.map((candidate) => matchesType(candidate, value)).some(Boolean);
  }
  switch (type) {
    case "object":
      return value !== null && typeof value === "object" && !Array.isArray(value);
    case "array":
      return Array.isArray(value);
    case "string":
      return typeof value === "string";
    case "integer":
      return Number.isInteger(value);
    case "number":
      return typeof value === "number" && Number.isFinite(value);
    case "boolean":
      return typeof value === "boolean";
    case "null":
      return value === null;
    default:
      throw new Error(`json-schema-lite: 미지원 type '${type}'`);
  }
}

function dot(path, key) {
  return `${path}.${key}`;
}
