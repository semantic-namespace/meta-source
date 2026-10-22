#!/usr/bin/env node
'use strict';
// EXPERIMENTAL (see js/README.md). meta-source for JavaScript and
// TypeScript: the meta-source command's questions, answered from the
// project's files, nothing loaded or run. Settings come from the flags, or
// from <root>/meta-source.edn, the file the Clojure reader reads.
//
//   meta-source-js <command> [args] [--root dir] [--registry dir]... [--src dir]...
//                  [--brief] [--fail-on error|warning|info]
//
// Answers are JSON, as the Clojure command's --json.
const readline = require('readline');
const edn = require('../src/edn');
const index = require('../src/index');
const hover = require('../src/hover');

const USAGE = `Commands:
  where <id>          where it is defined (EDN entries, packs) and implemented (a tagged declaration), with its entry
  uses <id>           entries that name it, and code that refers to its declaration
  deps <id>           entities its entry names
  data <data-key>     entities producing and consuming the key
  check               where code and registry files disagree (--brief: one line each;
                      --fail-on error: exit 1 when a problem is that severe or more, as CI wants)
  summary             counts, coverage, files re-analysed by this call
  hover <keyword>     what an editor shows for a keyword: eldoc line, describe text, locations
  diagnostics <file>  check's problems in one file, with the line and columns to underline
  serve               long-lived: one JSON request per line on stdin, one JSON reply per line
                      {"id":1,"op":"hover","keyword":":fn/x"} -> {"id":1,"result":{...}}
                      ops: hover, check, diagnostics (file), summary, where/uses/deps/data (keyword),
                      and hover-at (file, line, col, text: the id a position in JS/TS code names)`;

function parse(args) {
  const o = { registry: [], src: [], positional: [] };
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--root') o.root = args[++i];
    else if (a === '--registry') o.registry.push(args[++i]);
    else if (a === '--src') o.src.push(args[++i]);
    else if (a === '--fail-on') o.failOn = args[++i];
    else if (a === '--brief') o.brief = true;
    else if (a === '--json' || a === '--no-cache') {
      // answers are always JSON; parsed files are cached in memory only
    } else o.positional.push(a);
  }
  return o;
}

const RANK = { info: 0, warning: 1, error: 2 };
function failing(problems, failOn) {
  if (!(failOn in RANK)) throw new Error(`--fail-on takes error, warning or info, not ${JSON.stringify(failOn)}`);
  return problems.filter((p) => (RANK[p.severity] ?? 0) >= RANK[failOn]);
}

const kwArg = (s) => edn.kw(String(s || ''));
const show = (x) => console.log(JSON.stringify(edn.toJson(x), null, 2));

function answer(idx, op, req) {
  switch (op) {
    case 'where': return index.where(idx, kwArg(req.keyword)) || { 'not-found': kwArg(req.keyword) };
    case 'uses': return index.uses(idx, kwArg(req.keyword));
    case 'deps': return index.dependencies(idx, kwArg(req.keyword)) || { 'not-found': kwArg(req.keyword) };
    case 'data': return index.dataKey(idx, kwArg(req.keyword));
    case 'check': return index.check(idx);
    case 'summary': return index.summary(idx);
    case 'hover': return hover.lookup(idx, kwArg(req.keyword));
    case 'hover-at': return hover.lookupAt(idx, req);
    case 'diagnostics': return hover.diagnostics(idx, req.file);
    default: return undefined;
  }
}

function serve(opts) {
  const root = opts.root || '.';
  readline.createInterface({ input: process.stdin }).on('line', (line) => {
    let req;
    try {
      req = JSON.parse(line);
    } catch (_) {
      return;
    }
    let reply;
    try {
      // meta-source.edn and the files are read again before each answer:
      // parsed code is cached by modification time
      const idx = index.build(index.config(root, opts));
      const result = answer(idx, req.op, req);
      reply = result === undefined ? { id: req.id, result: { error: `unknown op ${req.op}` } } : { id: req.id, result: edn.toJson(result) };
    } catch (e) {
      reply = { id: req.id, error: e.message };
    }
    process.stdout.write(JSON.stringify(reply) + '\n');
  });
}

function main(args) {
  const opts = parse(args);
  const [cmd, arg] = opts.positional;
  if (cmd === 'serve') return serve(opts);
  if (!cmd || !['where', 'uses', 'deps', 'data', 'check', 'summary', 'hover', 'diagnostics'].includes(cmd)) {
    console.log(USAGE);
    process.exit(cmd ? 1 : 0);
  }
  if (opts.failOn) failing([], opts.failOn); // a bad value fails before any output
  const idx = index.build(index.config(opts.root || '.', opts));
  if (cmd === 'check' && opts.brief) hover.brief(idx).forEach((l) => console.log(l));
  else show(answer(idx, cmd, { keyword: arg, file: arg }));
  if (cmd === 'check' && opts.failOn) {
    const n = failing(index.check(idx), opts.failOn).length;
    if (n > 0) {
      console.error(`meta-source: ${n} problem(s) at ${opts.failOn} or above`);
      process.exit(1);
    }
  }
}

try {
  main(process.argv.slice(2));
} catch (e) {
  console.error(`meta-source: ${e.message}`);
  process.exit(1);
}
