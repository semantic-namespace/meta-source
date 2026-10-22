'use strict';
// The JavaScript reader against conformance/expected.json, the Clojure
// reader's answers about conformance/polyglot's registry (bb conformance
// writes and checks it). `project` keeps what every reader must give, as
// conformance/conformance.clj's does.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { toJson, kw } = require('../src/edn');
const index = require('../src/index');
const hover = require('../src/hover');

const root = path.join(__dirname, '..', '..', 'conformance', 'polyglot');
const expected = JSON.parse(fs.readFileSync(path.join(root, '..', 'expected.json'), 'utf8'));
const idx = index.build(index.config(root));
const REGISTRY_PROBLEMS = new Set(['conflicting-values', 'unreadable-registry-file']);

const scalar = (x) => x === null || ['string', 'number', 'boolean'].includes(typeof x);
function deepSort(x) {
  if (Array.isArray(x)) {
    const xs = x.map(deepSort);
    return xs.every(scalar) ? xs.sort((a, b) => String(a).localeCompare(String(b), 'en', { sensitivity: 'variant' }) || 0) : xs;
  }
  if (x && typeof x === 'object') return Object.fromEntries(Object.keys(x).sort().map((k) => [k, deepSort(x[k])]));
  return x;
}

function project(question, answer) {
  const [cmd] = question.split(' ');
  const rootPath = fs.realpathSync(root) + '/';
  const rel = (x) => (typeof x === 'string' ? x.split(rootPath).join('') : Array.isArray(x) ? x.map(rel)
    : x && typeof x === 'object' ? Object.fromEntries(Object.entries(x).map(([k, v]) => [k, rel(v)])) : x);
  const a = rel(answer);
  let p = a;
  if (cmd === 'where') {
    const { impl, ...rest } = a;
    p = rest;
  } else if (cmd === 'uses') p = { 'dev-id': a['dev-id'], 'referenced-by': a['referenced-by'] };
  else if (cmd === 'check') p = a.filter((x) => REGISTRY_PROBLEMS.has(x.problem));
  else if (cmd === 'hover' && a.kind === 'entity')
    p = { kind: 'entity', definitions: a.definitions.filter((d) => d.summary.startsWith('defined')),
          references: a.references.filter((r) => !r.summary.startsWith('called from')) };
  return deepSort(p);
}

function answer(question) {
  const [cmd, arg] = question.split(' ');
  const k = arg && kw(arg);
  const out = { where: () => index.where(idx, k), uses: () => index.uses(idx, k), deps: () => index.dependencies(idx, k),
                data: () => index.dataKey(idx, k), check: () => index.check(idx), hover: () => hover.lookup(idx, k) }[cmd]();
  return toJson(out);
}

// clojure's sort-by str and JS's localeCompare differ on punctuation: compare sorted the same way
const canon = (x) => JSON.stringify(deepSort(x));

for (const [q, e] of Object.entries(expected))
  test(`conformance: ${q}`, () => assert.deepStrictEqual(JSON.parse(canon(project(q, answer(q)))), JSON.parse(canon(e))));
