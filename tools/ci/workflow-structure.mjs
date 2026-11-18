// workflow 구조 계약 테스트가 함께 쓰는 YAML 텍스트 도우미 (backend #516, #520).
import assert from 'node:assert/strict';

// 주석 문구가 구조 판정에 섞이지 않도록 주석 줄을 뺀 사본으로 판정한다.
export const withoutComments = (text) => text.replace(/^[ \t]*#.*\n/gm, '');

/** 6칸 들여쓴 `- name:` 줄마다 step 하나로 자른다. */
export const steps = (text) => text.split(/^(?=      - name: )/m).slice(1).map((block) => ({ name: /^      - name: ([^\n]+)/.exec(block)[1], text: block }));

export const stepNames = (text) => steps(text).map(({ name }) => name);

export const step = (text, name) => {
  const found = steps(text).filter((candidate) => candidate.name === name);
  assert.equal(found.length, 1, `step ${name} must appear exactly once`);
  return found[0].text;
};

/** step 블록의 8칸 들여쓴 `uses:` 값을 그대로 돌려준다(없으면 undefined). 부분 문자열이 아니라 값 전체로 비교하기 위한 도우미. */
export const stepUses = (stepText) => /^ {8}uses: ([^\n]+)$/m.exec(stepText)?.[1];
