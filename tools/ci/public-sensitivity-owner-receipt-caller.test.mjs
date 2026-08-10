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

permissions:
  contents: read
  actions: read

jobs:
  receipt:
    uses: AquilaXk/easysubway/.github/workflows/public-sensitivity-owner-receipt.yml@3d1590baa98c929ceabd0d2d44414cebcc643c6f
    secrets:
      D20_SECRET_SCANNING_ALERTS_READ_TOKEN: \${{ secrets.D20_SECRET_SCANNING_ALERTS_READ_TOKEN }}
`;

test('public sensitivity owner receipt caller stays a pinned, least-privilege dispatch-only reusable call', async () => {
  const workflow = await readFile(workflowUrl, 'utf8');

  assert.equal(workflow, expectedWorkflow);
});
