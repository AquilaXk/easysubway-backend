import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';

const workflowUrl = new URL(
  '../../.github/workflows/public-sensitivity-owner-receipt-caller.yml',
  import.meta.url,
);

const expectedWorkflow = `name: Public Sensitivity Owner Receipt Caller

on:
  workflow_dispatch:
    inputs:
      observed_at:
        required: true
        type: string

permissions:
  contents: read
  actions: read

jobs:
  receipt:
    uses: AquilaXk/easysubway/.github/workflows/public-sensitivity-owner-receipt.yml@fa2f2602573651af6694e7f56077414b685987b9
    with:
      observed_at: \${{ inputs.observed_at }}
    secrets:
      D20_SECRET_SCANNING_ALERTS_READ_TOKEN: \${{ secrets.D20_SECRET_SCANNING_ALERTS_READ_TOKEN }}
`;

function assertExactWorkflow(workflow) {
  assert.equal(workflow, expectedWorkflow);
}

test('public sensitivity owner receipt caller stays a pinned, least-privilege dispatch-only reusable call', async () => {
  const workflow = await readFile(workflowUrl, 'utf8');

  assertExactWorkflow(workflow);
});

test('public sensitivity owner receipt caller rejects contract mutations', () => {
  const mutations = {
    'missing observed_at input': expectedWorkflow.replace(
      '    inputs:\n      observed_at:\n        required: true\n        type: string\n',
      '',
    ),
    'wrong observed_at input type': expectedWorkflow.replace('        type: string', '        type: boolean'),
    'default observed_at input': expectedWorkflow.replace(
      '        required: true\n',
      '        required: true\n        default: now\n',
    ),
    'missing observed_at forwarding': expectedWorkflow.replace(
      '    with:\n      observed_at: \${{ inputs.observed_at }}\n',
      '',
    ),
    'extra observed_at forwarding': expectedWorkflow.replace(
      '      observed_at: \${{ inputs.observed_at }}\n',
      '      observed_at: \${{ inputs.observed_at }}\n      extra: value\n',
    ),
    'wrong observed_at forwarding': expectedWorkflow.replace(
      '      observed_at: \${{ inputs.observed_at }}',
      '      observed_at: \${{ github.run_id }}',
    ),
    'old pin': expectedWorkflow.replace(
      'fa2f2602573651af6694e7f56077414b685987b9',
      '3d1590baa98c929ceabd0d2d44414cebcc643c6f',
    ),
    'mutable pin': expectedWorkflow.replace(
      'fa2f2602573651af6694e7f56077414b685987b9',
      'main',
    ),
    'extra secret': expectedWorkflow.replace(
      '      D20_SECRET_SCANNING_ALERTS_READ_TOKEN: \${{ secrets.D20_SECRET_SCANNING_ALERTS_READ_TOKEN }}\n',
      '      D20_SECRET_SCANNING_ALERTS_READ_TOKEN: \${{ secrets.D20_SECRET_SCANNING_ALERTS_READ_TOKEN }}\n      EXTRA_TOKEN: \${{ secrets.EXTRA_TOKEN }}\n',
    ),
    'extra permission': expectedWorkflow.replace(
      '  actions: read\n',
      '  actions: read\n  issues: write\n',
    ),
    'extra job': expectedWorkflow.replace(
      '    secrets:\n',
      '  extra:\n    runs-on: ubuntu-latest\n    steps: []\n    secrets:\n',
    ),
  };

  for (const [name, workflow] of Object.entries(mutations)) {
    assert.throws(() => assertExactWorkflow(workflow), name);
  }
});
