'use strict';
// EXPERIMENTAL. meta-source in VS Code: hover, go to definition, find
// references and problems over the registry's ids, for each folder with a
// meta-source.edn. Two readers answer, with the same protocol (one JSON
// request per line, one reply per line):
//
//   meta-source serve      the Clojure reader (babashka), for .clj/.cljc/.cljs and .edn
//   meta-source-js serve   the JavaScript reader (node), for .ts/.tsx/.js/...
//
// A reader that isn't installed is left out. Go to definition and find
// references ask both and show every location: from an id in an EDN file,
// its Clojure var and its TypeScript declaration alike.
const vscode = require('vscode');
const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { spawn } = require('child_process');

const CLJ = ['clojure', 'edn'];
const JS = ['typescript', 'typescriptreact', 'javascript', 'javascriptreact'];
const SELECTOR = [...CLJ, ...JS].map((language) => ({ language, scheme: 'file' })).concat([{ pattern: '**/*.edn' }]);
const SEVERITY = { error: vscode.DiagnosticSeverity.Error, warning: vscode.DiagnosticSeverity.Warning, note: vscode.DiagnosticSeverity.Information };
const KW = /:[\w.\-*+!?<>=]+\/[\w.\-*+!?<>=]+/g;

class Reader {
  constructor(name, command, root, log) {
    this.name = name;
    this.n = 0;
    this.waiting = new Map();
    const [cmd, ...args] = command;
    this.proc = spawn(cmd, [...args, 'serve'], { cwd: root, shell: process.platform === 'win32' });
    this.alive = true;
    this.proc.on('error', (e) => { this.alive = false; log.appendLine(`${name}: ${e.message}`); });
    this.proc.on('exit', (c) => { this.alive = false; log.appendLine(`${name} exited (${c})`); this.waiting.forEach((w) => w(null)); });
    this.proc.stderr.on('data', (d) => log.append(`${name}: ${d}`));
    readline.createInterface({ input: this.proc.stdout }).on('line', (l) => {
      try {
        const r = JSON.parse(l);
        const w = this.waiting.get(r.id);
        if (w) this.waiting.delete(r.id), w(r.error ? null : r.result);
      } catch (_) {}
    });
  }
  ask(req) {
    if (!this.alive) return Promise.resolve(null);
    const id = ++this.n;
    return new Promise((resolve) => {
      this.waiting.set(id, resolve);
      setTimeout(() => this.waiting.has(id) && (this.waiting.delete(id), resolve(null)), 60000);
      this.proc.stdin.write(JSON.stringify({ id, ...req }) + '\n');
    });
  }
  dispose() {
    this.proc.kill();
  }
}

function activate(context) {
  const log = vscode.window.createOutputChannel('meta-source');
  const problems = vscode.languages.createDiagnosticCollection('meta-source');
  const folders = new Map(); // root -> {clj, js}
  context.subscriptions.push(log, problems, { dispose: () => folders.forEach((f) => (f.clj && f.clj.dispose(), f.js && f.js.dispose())) });

  const cfg = () => vscode.workspace.getConfiguration('metaSource');
  for (const wf of vscode.workspace.workspaceFolders || []) {
    const root = wf.uri.fsPath;
    if (!fs.existsSync(path.join(root, 'meta-source.edn'))) continue;
    const f = {};
    if (cfg().get('clojure.enabled')) f.clj = new Reader('meta-source', cfg().get('clojure.command'), root, log);
    if (cfg().get('javascript.enabled')) f.js = new Reader('meta-source-js', cfg().get('javascript.command'), root, log);
    folders.set(root, f);
    // the first answer builds a reader's index: build it now, not on the first hover
    for (const r of [f.clj, f.js]) if (r) r.ask({ op: 'summary' });
  }
  // a file outside every folder (one go to definition opened) asks the first folder's readers
  const folderOf = (file) => [...folders].find(([root]) => file.startsWith(root + path.sep)) || [...folders][0] || [null, {}];
  const isJs = (doc) => JS.includes(doc.languageId);
  /** The reader answering for doc first, then the other. */
  const readersFor = (doc) => {
    const [, f] = folderOf(doc.uri.fsPath);
    return (isJs(doc) ? [f.js, f.clj] : [f.clj, f.js]).filter((r) => r && r.alive);
  };

  function keywordAt(doc, position) {
    const text = doc.lineAt(position.line).text;
    for (const m of text.matchAll(KW))
      if (position.character >= m.index && position.character <= m.index + m[0].length)
        return { kw: m[0], range: new vscode.Range(position.line, m.index, position.line, m.index + m[0].length) };
    return null;
  }

  /** [{reader, result, range}] for what is at position: a keyword, or in JS/TS code the id a name there names. */
  async function lookups(doc, position) {
    const k = keywordAt(doc, position);
    const rs = readersFor(doc);
    // both readers at once: the slower one doesn't hold the other's answer
    const results = await Promise.all(rs.map((r) =>
      k ? r.ask({ op: 'hover', keyword: k.kw })
        : r.name === 'meta-source-js' && isJs(doc)
          ? r.ask({ op: 'hover-at', file: doc.uri.fsPath, line: position.line + 1, col: position.character + 1, text: doc.lineAt(position.line).text })
          : null));
    return results.flatMap((result, i) => {
      if (!result || !result.kind || result.kind === 'none') return [];
      const rg = result.range;
      return [{ reader: rs[i].name, result, range: k ? k.range : rg && new vscode.Range(rg.line - 1, rg.col - 1, rg.line - 1, rg['end-col'] - 1) }];
    });
  }

  const root = () => [...folders.keys()][0] || '';
  const link = (l) => {
    const rel = l.file.startsWith(root() + path.sep) ? path.relative(root(), l.file) : l.file;
    return `[${rel}:${l.line}](${vscode.Uri.file(l.file).with({ fragment: `L${l.line}` })})`;
  };
  const esc = (s) => s.replace(/([\\`*_{}[\]<>|])/g, '\\$1');

  /**
   * The hover: the id and its summary line, then where it is defined,
   * implemented and used — every reader's locations, as links — then the
   * entry and any problem, from the reader of the file's language.
   */
  function render(ls) {
    const main = ls[0].result;
    const md = new vscode.MarkdownString();
    md.isTrusted = true;
    const [head, line2] = (main.eldoc || '').split('\n');
    md.appendMarkdown(`**${esc(head.trim())}**${line2 ? '  \n' + esc(line2) : ''}\n\n`);
    const defs = union(ls.flatMap((l) => l.result.definitions || []));
    const refs = union(ls.flatMap((l) => l.result.references || []));
    for (const d of defs) md.appendMarkdown(`${esc(d.summary.replace(/: .*/, ''))} ${link(d)}${/^impl/.test(d.summary) ? ' `' + d.summary.replace(/^impl: /, '') + '`' : ''}  \n`);
    for (const r of refs) md.appendMarkdown(`${esc(r.summary)} ${link(r)}  \n`);
    // the entry, and problems, as the describe text has them
    const lines = (main.describe || '').split('\n');
    const end = lines.findIndex((l, i) => i > 0 && /^(defined|impl|produced by)/.test(l));
    const entry = lines.slice(1, end < 0 ? lines.length : end).filter((l) => l.trim());
    const warn = lines.filter((l) => l.startsWith('⚠'));
    if (entry.length) md.appendCodeblock(entry.join('\n'), 'clojure');
    for (const w of warn) md.appendMarkdown(`${esc(w)}  \n`);
    return md;
  }

  const key = (l) => `${l.file}:${l.line}`;
  const union = (ls) => [...new Map(ls.map((l) => [key(l), l])).values()];
  const location = (l) => new vscode.Location(vscode.Uri.file(l.file), new vscode.Position(Math.max(0, l.line - 1), 0));

  async function refresh(doc) {
    if (!doc || doc.uri.scheme !== 'file') return;
    const [first] = readersFor(doc);
    if (!first) return;
    const ds = (await first.ask({ op: 'diagnostics', file: doc.uri.fsPath })) || [];
    problems.set(doc.uri, ds.map((d) => {
      const line = d.line - 1;
      const range = d.col
        ? new vscode.Range(line, d.col - 1, line, d['end-col'] - 1)
        : doc.lineAt(Math.min(line, doc.lineCount - 1)).range;
      const v = new vscode.Diagnostic(range, d.message.replace(/^meta-source: /, ''), SEVERITY[d.severity] ?? SEVERITY.note);
      v.source = 'meta-source';
      v.code = d.problem;
      return v;
    }));
  }

  context.subscriptions.push(
    vscode.languages.registerHoverProvider(SELECTOR, {
      async provideHover(doc, position) {
        const ls = await lookups(doc, position);
        if (!ls.length) return null;
        return new vscode.Hover(render(ls), ls[0].range);
      },
    }),
    vscode.languages.registerDefinitionProvider(SELECTOR, {
      async provideDefinition(doc, position) {
        return union((await lookups(doc, position)).flatMap((l) => l.result.definitions || [])).map(location);
      },
    }),
    vscode.languages.registerReferenceProvider(SELECTOR, {
      async provideReferences(doc, position) {
        return union((await lookups(doc, position)).flatMap((l) => l.result.references || [])).map(location);
      },
    }),
    vscode.workspace.onDidSaveTextDocument((doc) => vscode.workspace.textDocuments.forEach(refresh)),
    vscode.workspace.onDidOpenTextDocument(refresh),
  );
  vscode.workspace.textDocuments.forEach(refresh);
}

function deactivate() {}

module.exports = { activate, deactivate };
