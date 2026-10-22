'use strict';
// What the JavaScript reader finds in code, on conformance/polyglot's web/
// (the registry's part is conformance.test.js's), and the pack interface,
// on fixtures/routes.
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { execFileSync } = require('child_process');
const edn = require('../src/edn');
const index = require('../src/index');
const hover = require('../src/hover');

const { kw, toJson } = edn;
const polyglot = path.join(__dirname, '..', '..', 'conformance', 'polyglot');
const routes = path.join(__dirname, 'fixtures', 'routes');
const idx = index.build(index.config(polyglot));
const bin = path.join(__dirname, '..', 'bin', 'meta-source-js.js');

test('a tagged declaration implements its id', () => {
  assert.deepStrictEqual(toJson(index.where(idx, kw(':fn/render-receipt')).impl),
                         { file: 'web/receipt.ts', line: 4, 'end-line': 7, var: 'web/receipt.ts#renderReceipt' });
  assert.strictEqual(index.where(idx, kw(':page/receipt')).impl.var, 'web/money.ts#ROUTES.receipt');
});

test('code referring to it, in its file and through an import, with the referring id', () => {
  const from = (id) => toJson(index.uses(idx, kw(id))['called-from']);
  assert.deepStrictEqual(from(':fn/render-receipt'), [{ var: 'web/receipt.ts#receiptPage', file: 'web/receipt.ts', line: 10 }]);
  assert.deepStrictEqual(from(':fn/format-money'),
                         [{ var: 'web/receipt.ts#renderReceipt', file: 'web/receipt.ts', line: 6, 'dev-id': 'fn/render-receipt' }]);
});

test('an id on two declarations is an error; a tag with no source saying anything is binding-only', () => {
  const ps = toJson(index.check(idx)).filter((p) => p.problem !== 'conflicting-values');
  assert.deepStrictEqual(ps.map((p) => [p.severity, p.problem, p['dev-id']]),
                         [['error', 'dev-id-on-several-vars', 'fn/format-money'], ['info', 'binding-only', 'fn/format-money']]);
});

test('diagnostics underline the id in the JSDoc', () => {
  const ds = hover.diagnostics(idx, 'web/money.ts');
  assert.deepStrictEqual(ds.map((d) => [d.line, d.col, d['end-col'], d.severity, d.problem]),
                         [[1, 21, 37, 'error', 'dev-id-on-several-vars'], [9, 23, 39, 'error', 'dev-id-on-several-vars'],
                          [1, 21, 37, 'note', 'binding-only']]);
});

test('hover at a tagged name, at its tag, and at a reference to it', () => {
  const at = (file, line, col) => {
    const text = require('fs').readFileSync(path.join(polyglot, file), 'utf8').split('\n')[line - 1];
    return hover.lookupAt(idx, { file: path.join(polyglot, file), line, col, text });
  };
  assert.match(at('web/receipt.ts', 4, 20).eldoc, /^:fn\/render-receipt {2}entry/);
  assert.match(at('web/receipt.ts', 3, 25).eldoc, /^:fn\/render-receipt/);
  assert.match(at('web/receipt.ts', 6, 34).eldoc, /^:fn\/format-money {2}binding/);
  assert.deepStrictEqual(at('web/receipt.ts', 6, 34).range, { line: 6, col: 33, 'end-col': 44 });
  assert.strictEqual(at('web/receipt.ts', 5, 3).kind, 'none');
});

test('the entity hover says where it is implemented and who calls it', () => {
  const h = hover.lookup(idx, kw(':fn/render-receipt'));
  assert.match(h.describe, /\nimpl {7}web\/receipt\.ts#renderReceipt {4}web\/receipt\.ts:4-7\n/);
  assert.match(h.describe, /\ncalled from\n {2}web\/receipt\.ts#receiptPage {4}web\/receipt\.ts:10/);
  assert.deepStrictEqual(h.definitions.map((d) => d.summary), ['defined: calls doc reads returns', 'impl: web/receipt.ts#renderReceipt']);
});

test('a JS pack: its entities are one more source, its problems are check\'s', () => {
  const r = index.build(index.config(routes));
  const w = toJson(index.where(r, kw(':route/cart')));
  assert.deepStrictEqual(w.entry, { 'route/title': 'Your cart', 'route/path': '/shop/cart' });
  assert.deepStrictEqual(w.sources.map((s) => s.pack || 'edn'), ['edn', 'routes']);
  assert.deepStrictEqual(toJson(index.check(r)).map((p) => [p.problem, p['dev-id'], p.message]),
                         [['route-outside-prefix', 'route/legacy', '/v1/legacy is outside /shop']]);
  assert.match(hover.lookup(r, kw(':route/cart')).eldoc, /^:route\/cart {2}entry\n\/shop\/cart/);
});

test('the command: --fail-on exits 1 at that severity, --brief prints one line each', () => {
  const run = (...args) => {
    try {
      return { out: execFileSync('node', [bin, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }), code: 0 };
    } catch (e) {
      return { out: e.stdout, code: e.status };
    }
  };
  assert.strictEqual(run('check', '--root', routes, '--fail-on', 'error').code, 0);
  assert.strictEqual(run('check', '--root', routes, '--fail-on', 'warning').code, 1);
  assert.match(run('check', '--root', routes, '--brief').out, /^warning src\/routes\.ts:3 +\/v1\/legacy is outside \/shop\n$/);
  assert.strictEqual(run('check', '--fail-on', 'loud').code, 1);
});

test('serve: one JSON reply per request', () => {
  const out = execFileSync('node', [bin, 'serve', '--root', polyglot], {
    encoding: 'utf8',
    input: ['{"id":1,"op":"hover","keyword":":fn/price"}', '{"id":2,"op":"summary"}', '{"id":3,"op":"nope"}'].join('\n') + '\n',
  });
  const [a, b, c] = out.trim().split('\n').map((l) => JSON.parse(l));
  assert.strictEqual(a.result.kind, 'entity');
  assert.strictEqual(b.result.entities, 5);
  assert.deepStrictEqual(c, { id: 3, result: { error: 'unknown op nope' } });
});

test('EDN: lines of top-level keys, sets, tags, discards, and what is not readable', () => {
  const rows = edn.keyedLines(';; c\n{:a/b {:x #{:y :y} :s "q\\"z" :t #inst "2026"}\n #_ :gone #_ 1\n :c/d [1 nil true \\a]}');
  assert.deepStrictEqual(rows.map((r) => [r.key.text, r.line]), [[':a/b', 2], [':c/d', 4]]);
  assert.strictEqual(edn.prStr(rows[0].value), '{:x #{:y}, :s "q\\"z", :t #inst "2026"}');
  assert.deepStrictEqual(toJson(rows[1].value), [1, null, true, 'a']);
  assert.strictEqual(edn.keyedLines('[1 2]'), null);
  assert.throws(() => edn.keyedLines('{:a 1} :b'), /something after the map/);
  assert.throws(() => edn.keyedLines('{:a}'), /a key with no value/);
});
