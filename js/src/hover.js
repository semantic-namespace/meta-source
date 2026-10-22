'use strict';
// What an editor shows, as meta-source.hover does: for a keyword, an eldoc
// line, a describe text and the locations for go to definition and find
// references; check's problems located in one file, with the columns to
// underline; check as lines for a terminal. And, as JavaScript has no
// keyword to put the point on, `at`: the id a position names — a tagged
// declaration's name or tag, or a reference to a tagged declaration.
const fs = require('fs');
const path = require('path');
const { kw, prStr, isQualifiedKeyword } = require('./edn');
const index = require('./index');

const pr = prStr;
const abs = (idx, f) => path.join(idx.root, f);
const at = (l) => (l && l.file ? `${l.file}:${l.line}${l['end-line'] && l['end-line'] !== l.line ? '-' + l['end-line'] : ''}` : '(no file)');
const names = (ks) => ks.map((k) => k.name || String(k)).join(' ');

function few(xs, n) {
  return xs.slice(0, n).map(pr).join(' ') + (xs.length > n ? ` +${xs.length - n}` : '');
}

function sourceLine(s) {
  return `${at(s)}    ${s.binding ? 'meta key    ' : ''}${names(s.keys)}`;
}

function describeWithPacks(idx, id, entry) {
  for (const { pack, opts } of idx.packs)
    if (pack.describe) {
      const d = pack.describe(index.context(idx, opts), id, entry);
      if (d) return d;
    }
  return null;
}

function entity(idx, id) {
  const w = index.where(idx, id);
  const u = index.uses(idx, id);
  const entry = w.entry;
  const problems = index.check(idx).filter((p) => p['dev-id'] === id);
  const { consumes, produces } = index.contractKeys(idx, entry);
  const cons = index.sortKeywords([...consumes]);
  const prod = index.sortKeywords([...produces]);
  const deps = index.dependencies(idx, id)['depends-on'].map((d) => d['dev-id']);
  const defining = w.sources;
  const nUsed = u['referenced-by'].length + u['called-from'].length;
  const kind = w.definition.pack || (w.definition.binding ? 'binding' : 'entry');
  const contract = describeWithPacks(idx, id, entry) || (cons.length || prod.length ? `[${few(cons, 4)}] → [${few(prod, 4)}]` : null);
  const line2 = [contract, deps.length ? `deps ${few(deps, 3)}` : null, `used by ${nUsed}`, problems.length ? `⚠ ${problems.length}` : null]
    .filter((x) => x !== null).join('  ·  ');
  const lines = [`${pr(id)}    ${kind}`, ''];
  if (cons.length) lines.push(`consumes   ${cons.map(pr).join(' ')}`);
  if (prod.length) lines.push(`produces   ${prod.map(pr).join(' ')}`);
  if (deps.length) lines.push(`deps       ${deps.map(pr).join(' ')}`);
  for (const k of index.sortKeywords([...entry.keys()])) lines.push(`${pr(k)}  ${pr(entry.get(k))}`);
  lines.push('');
  defining.forEach((s, i) => lines.push(`${i === 0 ? 'defined    ' : '           '}${sourceLine(s)}`));
  lines.push(w.impl ? `impl       ${w.impl.var}    ${at(w.impl)}` : 'impl       none');
  lines.push('');
  if (u['referenced-by'].length) {
    lines.push('used by');
    for (const r of u['referenced-by']) lines.push(`  ${pr(r['dev-id'])}  via ${pr(r.via)}    ${at(r.definition)}`);
  } else lines.push('used by    nothing names it');
  if (u['called-from'].length) {
    lines.push('called from');
    for (const c of u['called-from']) lines.push(`  ${c.var}${c['dev-id'] ? ' ' + pr(c['dev-id']) : ''}    ${at(c)}`);
  }
  if (u.coverage.note)
    lines.push(`  (${u.coverage['linked-vars']} of ${u.coverage['public-fns']} public fns linked; ${u.coverage.note})`);
  if (problems.length) {
    lines.push('');
    for (const p of problems) {
      const l = p.impl || p.definition || (p.file ? p : null);
      lines.push(`⚠ ${p.severity} ${p.problem.name}${p.keys ? ' ' + few(p.keys, 6) : ''}${p.calls ? ' calls ' + pr(p.calls) : ''}${l && l.file ? '    ' + at(l) : ''}`);
    }
  }
  return {
    kind: 'entity',
    eldoc: `${pr(id)}  ${kind}\n${line2}`,
    describe: lines.join('\n'),
    definitions: [
      ...defining.filter((s) => s.file).map((s) => ({ file: abs(idx, s.file), line: s.line, summary: `defined: ${names(s.keys)}` })),
      ...(w.impl ? [{ file: abs(idx, w.impl.file), line: w.impl.line, summary: `impl: ${w.impl.var}` }] : []),
    ],
    references: [
      ...u['referenced-by'].map((r) => ({ file: abs(idx, r.definition.file), line: r.definition.line, summary: `${pr(r['dev-id'])} via ${pr(r.via)}` })),
      ...u['called-from'].map((c) => ({ file: abs(idx, c.file), line: c.line, summary: `called from ${c.var}` })),
    ],
  };
}

function dataKey(idx, k) {
  const { producers, consumers } = index.dataKey(idx, k);
  const ids = (xs) => xs.map((x) => pr(x['dev-id'])).join(', ');
  const problems = index.check(idx).filter((p) => p['dev-id'] === k);
  const lines = [`${pr(k)}    data key`, '', 'produced by', ...producers.map((p) => `  ${pr(p['dev-id'])}    ${at(p.definition)}`),
    '', 'consumed by', ...consumers.map((c) => `  ${pr(c['dev-id'])}    ${at(c.definition)}`), '', 'mentioned in code (0)'];
  if (problems.length) lines.push('', ...problems.map((p) => `⚠ ${p.severity} ${p.problem.name}`));
  return {
    kind: 'data-key',
    eldoc: `${pr(k)}  data key\nproduced by ${producers.length ? ids(producers) : 'nothing'}  ·  consumed by ${consumers.length ? ids(consumers) : 'nothing'}`,
    describe: lines.join('\n'),
    definitions: producers.map((p) => ({ file: abs(idx, p.definition.file), line: p.definition.line, summary: `produced by ${pr(p['dev-id'])}` })),
    references: consumers.map((c) => ({ file: abs(idx, c.definition.file), line: c.definition.line, summary: `consumed by ${pr(c['dev-id'])}` })),
  };
}

/** What to show for keyword k: {kind: "none"} when meta-source doesn't know it. */
function lookup(idx, k) {
  if (idx.entities.has(k)) return entity(idx, k);
  const d = index.dataKey(idx, k);
  if (d.producers.length || d.consumers.length) return dataKey(idx, k);
  return { kind: 'none' };
}

// =============================================================================
// A position in a JS/TS file
// =============================================================================

const within = (line, col, p, len) => p && p.line === line && col >= p.col && col <= p.col + len;

/** The id the position names: a tagged declaration (its name or its tag), or a reference to one. */
function idAt(idx, file, line, col) {
  const f = fs.existsSync(file) ? fs.realpathSync(file) : file;
  for (const v of idx.vars) {
    if (!v.devId || v.file !== f) continue;
    if (within(line, col, v.nameAt, v.name.split('.').pop().length) || within(line, col, v.tagAt, v.devId.length))
      return { id: kw(v.devId), range: within(line, col, v.tagAt, v.devId.length)
        ? { line, col: v.tagAt.col, 'end-col': v.tagAt.col + v.devId.length }
        : { line, col: v.nameAt.col, 'end-col': v.nameAt.col + v.name.split('.').pop().length } };
  }
  for (const r of idx.refs) {
    if (r.file !== f || r.line !== line) continue;
    const name = r.toName.split('.').pop();
    const len = r.toName.includes('.') ? r.toName.length : name.length;
    if (col < r.col || col > r.col + len) continue;
    const v = idx.vars.find((x) => x.devId && x.file === r.toFile && x.name === r.toName);
    if (v) return { id: kw(v.devId), range: { line, col: r.col, 'end-col': r.col + len } };
  }
  return null;
}

/** hover at a position: a keyword written there, or the id the code there names. */
function lookupAt(idx, { file, line, col, text }) {
  const k = keywordIn(text, col);
  if (k) return { ...lookup(idx, kw(k.kw)), range: { line, col: k.col, 'end-col': k.endCol } };
  const a = idAt(idx, file, line, col);
  return a ? { ...lookup(idx, a.id), range: a.range } : { kind: 'none' };
}

const KW = /:[\w.\-*+!?<>=]+\/[\w.\-*+!?<>=]+/g;
function keywordIn(text, col) {
  if (!text) return null;
  for (const m of text.matchAll(KW)) {
    const start = m.index + 1;
    if (col >= start && col <= start + m[0].length) return { kw: m[0], col: start, endCol: start + m[0].length };
  }
  return null;
}

// =============================================================================
// Problems
// =============================================================================

function locations(p) {
  return [
    ...(p.file && p.line ? [{ file: p.file, line: p.line }] : []),
    ...['definition', 'impl'].map((k) => p[k]).filter((l) => l && l.file),
    ...(p.vars || []).filter((l) => l.file),
    ...(p.definitions || []).filter((l) => l.file),
    ...(p.values || []).flatMap((v) => v.sources).filter((s) => s.file),
  ];
}

function message(p) {
  const id = p['dev-id'] ? pr(p['dev-id']) : null;
  const name = p.problem.name;
  let m;
  if (p.message && p.pack) m = p.message;
  else if (name === 'conflicting-values')
    m = `${id} ${pr(p.key)}: the sources give different values: ${p.values.flatMap((v) => v.sources).map((s) => `${s.file}:${s.line}`).join(', ')}`;
  else if (name === 'binding-only') m = `${id}: the meta key names it, no source says anything about it yet`;
  else if (name === 'unreadable-registry-file') m = `unreadable-registry-file: ${p.message}`;
  else m = `${name}${id ? ' ' + id : ''}${p.keys ? ' ' + p.keys.map(pr).join(' ') : ''}${p.reason ? ': ' + p.reason : ''}${p.message ? ': ' + p.message : ''}`;
  return `meta-source: ${m}`;
}

function brief(idx) {
  return index.check(idx).map((p) => {
    const l = locations(p)[0];
    return `${p.severity.padEnd(7)} ${(l ? `${l.file}:${l.line}` : '').padEnd(34)} ${message(p).slice('meta-source: '.length)}`;
  });
}

function tokenAt(lines, text, from, to) {
  const esc = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`(?<![\\w:/.*+!?<>=-])${esc}(?![\\w/.*+!?<>=-])`);
  for (let n = Math.max(1, from); n <= Math.min(to, lines.length); n++) {
    const m = re.exec(lines[n - 1]);
    if (m) return [n, m.index + 1, m.index + 1 + text.length];
  }
  return null;
}

/** check's problems located in file, for an editor: [{line, col, end-col, severity, problem, message}]. */
function diagnostics(idx, file) {
  const full = path.isAbsolute(file) ? file : path.join(idx.root, file);
  const real = fs.existsSync(full) ? fs.realpathSync(full) : full;
  const rel = path.relative(idx.root, real);
  const lines = fs.existsSync(real) ? fs.readFileSync(real, 'utf8').split('\n') : [];
  const out = [];
  const seen = new Set();
  for (const p of index.check(idx)) {
    for (const l of locations(p)) {
      if (l.file !== rel) continue;
      if (p.col) {
        const d = { line: l.line, col: p.col, 'end-col': p['end-col'] || p.col + 1 };
        push(d, p);
        continue;
      }
      // the dev-id as written on the line, or in the JSDoc above a declaration
      const t = p['dev-id'] && (tokenAt(lines, pr(p['dev-id']), l.line, l.line) || tokenAt(lines, pr(p['dev-id']), l.line - 6, l.line - 1));
      push(t ? { line: t[0], col: t[1], 'end-col': t[2] } : { line: l.line }, p);
    }
  }
  function push(d, p) {
    const r = { ...d, severity: { error: 'error', warning: 'warning' }[p.severity] || 'note', problem: p.problem.name, message: message(p) };
    const sig = JSON.stringify(r);
    if (!seen.has(sig)) seen.add(sig), out.push(r);
  }
  return out;
}

module.exports = { lookup, lookupAt, idAt, diagnostics, brief, message, locations, keywordIn, isQualifiedKeyword };
