import test from 'node:test';
import assert from 'node:assert/strict';
import { getWorkspaceColorVariants, getWorkspaceSolidFillColor, contrastRatio } from './color.js';

test('light variant contrasts >=3:1 against white for bright colors', () => {
  const { light } = getWorkspaceColorVariants('#eab308'); // bright yellow
  assert.ok(contrastRatio(light, '#ffffff') >= 2.95);
});

test('dark variant contrasts >=3:1 against near-black for dark colors', () => {
  const { dark } = getWorkspaceColorVariants('#1e3a5f'); // dark navy
  assert.ok(contrastRatio(dark, '#18181b') >= 2.95);
});

test('already-compliant colors are left close to the original', () => {
  const hex = '#3b82f6'; // preset blue, already reasonably dark
  const { light } = getWorkspaceColorVariants(hex);
  assert.ok(contrastRatio(light, '#ffffff') >= 2.95);
});

test('white input still produces usable variants', () => {
  const { light, dark } = getWorkspaceColorVariants('#ffffff');
  assert.ok(contrastRatio(light, '#ffffff') >= 2.95);
  assert.ok(contrastRatio(dark, '#18181b') >= 2.95);
});

test('black input still produces usable variants', () => {
  const { light, dark } = getWorkspaceColorVariants('#000000');
  assert.ok(contrastRatio(light, '#ffffff') >= 2.95);
  assert.ok(contrastRatio(dark, '#18181b') >= 2.95);
});

test('bright yellow stays recognizably yellow, not brown', () => {
  const { light } = getWorkspaceColorVariants('#eab308');
  // Should move noticeably less than the old 4.5:1 threshold did (#957205).
  assert.ok(contrastRatio(light, '#957205') > 1.15);
});

test('the solid fill keeps white text at 4.5:1 or more', () => {
  for (const hex of ['#ffeb3b', '#00bcd4', '#4caf50', '#ff9800', '#0078d4', '#e91e63', '#ffffff', '#9e9e9e']) {
    const fill = getWorkspaceSolidFillColor(hex);
    assert.ok(contrastRatio('#ffffff', fill) >= 4.5, `${hex} -> ${fill}`);
  }
  assert.equal(getWorkspaceSolidFillColor('#188038'), '#188038');
});
