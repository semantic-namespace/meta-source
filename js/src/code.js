'use strict';
// The code, read with TypeScript's parser (JavaScript too): nothing is
// compiled, loaded or run. For each file:
//
//   vars:  its declarations — functions, classes and their methods, consts,
//          and the properties of an object a const holds (ROUTES.receipt) —
//          each with the id its JSDoc names with the meta key, if any:
//            /** @meta-source/id :fn/render-receipt */
//   refs:  what refers to a declaration of this repo: a call, or any other
//          use of its name, resolved through imports (relative ones, and
//          the tsconfig baseUrl's), T.x for a property of an object
//
// A var is named <file>#<name>, <file>#<Class>.<method> or <file>#<T>.<key>.
const fs = require('fs');
const path = require('path');
const ts = require('typescript');

const EXTS = ['.ts', '.tsx', '.mts', '.cts', '.js', '.jsx', '.mjs', '.cjs'];
const isCode = (f) => EXTS.some((e) => f.endsWith(e)) && !f.endsWith('.d.ts');

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
/** The JSDoc tag naming an id with the meta key :ns/name: @ns/name :some/id */
const tagPattern = (idKey) => new RegExp(`@${escape(idKey.replace(/^:/, ''))}\\s+(:[\\w.\\-*+!?<>=]+\\/[\\w.\\-*+!?<>=]+)`);

function scriptKind(file) {
  if (file.endsWith('.tsx')) return ts.ScriptKind.TSX;
  if (file.endsWith('.jsx')) return ts.ScriptKind.JSX;
  if (/\.(m|c)?js$/.test(file)) return ts.ScriptKind.JS;
  return ts.ScriptKind.TS;
}

/** compilerOptions.baseUrl of root's tsconfig.json (or jsconfig.json), absolute, or null. */
function baseUrl(root) {
  for (const name of ['tsconfig.json', 'jsconfig.json']) {
    const f = path.join(root, name);
    if (!fs.existsSync(f)) continue;
    const { config } = ts.readConfigFile(f, (p) => fs.readFileSync(p, 'utf8'));
    const b = config && config.compilerOptions && config.compilerOptions.baseUrl;
    if (b) return path.resolve(root, b);
  }
  return null;
}

function resolveModule(fromFile, spec, base) {
  const bases = spec.startsWith('.') ? [path.resolve(path.dirname(fromFile), spec)] : base ? [path.join(base, spec)] : [];
  for (const b of bases) {
    const tries = [b, ...EXTS.map((e) => b + e), ...EXTS.map((e) => path.join(b, 'index' + e))];
    // ./x.js written for ./x.ts (ESM style)
    if (/\.(m|c)?js$/.test(b)) tries.push(...['.ts', '.tsx', '.mts', '.cts'].map((e) => b.replace(/\.(m|c)?js$/, e)));
    for (const t of tries) if (fs.existsSync(t) && fs.statSync(t).isFile()) return t;
  }
  return null;
}

function tagOf(sf, node, pattern) {
  for (const r of ts.getLeadingCommentRanges(sf.text, node.getFullStart()) || []) {
    const text = sf.text.slice(r.pos, r.end);
    const m = pattern.exec(text);
    if (m) {
      const at = sf.getLineAndCharacterOfPosition(r.pos + m.index + m[0].lastIndexOf(m[1]));
      return { id: m[1], line: at.line + 1, col: at.character + 1 };
    }
  }
  return null;
}

const isFnLike = (n) => n && (ts.isArrowFunction(n) || ts.isFunctionExpression(n));
const exported = (n) => !!(ts.getCombinedModifierFlags(n) & ts.ModifierFlags.Export) ||
  (ts.isVariableDeclaration(n) && n.parent && n.parent.parent && !!(ts.getCombinedModifierFlags(n.parent.parent) & ts.ModifierFlags.Export));
const nameText = (n) => n && (ts.isIdentifier(n) || ts.isStringLiteral(n) || ts.isPrivateIdentifier(n)) ? n.text : null;

/** {vars, refs, imports, sf} for one file. */
function analyseFile(file, rel, pattern) {
  const sf = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, scriptKind(file));
  const pos = (offset) => {
    const { line, character } = sf.getLineAndCharacterOfPosition(offset);
    return { line: line + 1, col: character + 1 };
  };
  const vars = [];
  const add = (node, name, extra = {}) => {
    const t = tagOf(sf, node, pattern);
    vars.push({
      name, var: `${rel}#${name}`, file, line: pos(node.getStart(sf)).line, endLine: pos(node.end).line,
      nameAt: pos((node.name || node).getStart(sf)), devId: t ? t.id : null, tagAt: t, ...extra,
    });
  };

  // imports: local name -> {module spec, imported name ('*' for a namespace, 'default')}
  const imports = new Map();
  for (const st of sf.statements) {
    if (!ts.isImportDeclaration(st) || !st.importClause || !ts.isStringLiteral(st.moduleSpecifier)) continue;
    const spec = st.moduleSpecifier.text;
    const c = st.importClause;
    if (c.name) imports.set(c.name.text, { spec, name: 'default' });
    if (c.namedBindings && ts.isNamespaceImport(c.namedBindings)) imports.set(c.namedBindings.name.text, { spec, name: '*' });
    if (c.namedBindings && ts.isNamedImports(c.namedBindings))
      for (const el of c.namedBindings.elements) imports.set(el.name.text, { spec, name: (el.propertyName || el.name).text });
  }

  function declare(node) {
    if ((ts.isFunctionDeclaration(node) || ts.isClassDeclaration(node)) && node.name) {
      add(node, node.name.text, { fn: ts.isFunctionDeclaration(node), exported: exported(node) });
      if (ts.isClassDeclaration(node))
        for (const m of node.members)
          if ((ts.isMethodDeclaration(m) || ts.isPropertyDeclaration(m)) && nameText(m.name))
            add(m, `${node.name.text}.${nameText(m.name)}`, { fn: ts.isMethodDeclaration(m), exported: false });
    } else if (ts.isVariableStatement(node)) {
      for (const d of node.declarationList.declarations) {
        if (!ts.isIdentifier(d.name)) continue;
        // the tag is on the statement: /** ... */ export const x = ...
        const host = node.declarationList.declarations.length === 1 ? node : d;
        const t = tagOf(sf, host, pattern);
        vars.push({
          name: d.name.text, var: `${rel}#${d.name.text}`, file, line: pos(node.getStart(sf)).line, endLine: pos(node.end).line,
          nameAt: pos(d.name.getStart(sf)), devId: t ? t.id : null, tagAt: t, fn: isFnLike(d.initializer), exported: exported(d),
        });
        let init = d.initializer;
        while (init && (ts.isAsExpression(init) || ts.isSatisfiesExpression?.(init) || ts.isParenthesizedExpression(init))) init = init.expression;
        if (init && ts.isObjectLiteralExpression(init))
          for (const p of init.properties)
            if ((ts.isPropertyAssignment(p) || ts.isMethodDeclaration(p) || ts.isShorthandPropertyAssignment(p)) && nameText(p.name))
              add(p, `${d.name.text}.${nameText(p.name)}`, {
                table: d.name.text, key: nameText(p.name), fn: ts.isMethodDeclaration(p) || isFnLike(p.initializer), exported: false,
              });
      }
    } else if (ts.isExportAssignment(node) || ts.isExpressionStatement(node)) {
      // module.exports = ..., export default ...: not a named declaration
    }
  }
  for (const st of sf.statements) declare(st);

  // what each place refers to, and from which declaration
  const local = new Set(vars.filter((v) => !v.name.includes('.')).map((v) => v.name));
  const refs = [];
  const enclosing = (node) => {
    for (let n = node.parent; n; n = n.parent) {
      if ((ts.isFunctionDeclaration(n) || ts.isClassDeclaration(n)) && n.name) {
        const m = node.parent && findMethod(n, node);
        return m ? `${n.name.text}.${m}` : n.name.text;
      }
      if (ts.isPropertyAssignment(n) && n.parent && ts.isObjectLiteralExpression(n.parent) && ts.isVariableDeclaration(n.parent.parent) &&
          ts.isIdentifier(n.parent.parent.name) && nameText(n.name))
        return `${n.parent.parent.name.text}.${nameText(n.name)}`;
      if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && ts.isVariableStatement(n.parent.parent) && n.parent.parent.parent === sf)
        return n.name.text;
    }
    return null;
  };
  function findMethod(cls, node) {
    for (let n = node.parent; n && n !== cls; n = n.parent)
      if ((ts.isMethodDeclaration(n) || ts.isPropertyDeclaration(n) || ts.isConstructorDeclaration(n)) && n.parent === cls)
        return ts.isConstructorDeclaration(n) ? 'constructor' : nameText(n.name);
    return null;
  }
  function visit(node) {
    if (ts.isIdentifier(node) && !isDeclarationName(node) && !(ts.isPropertyAccessExpression(node.parent) && node.parent.name === node)) {
      const name = node.text;
      let target = null;
      if (local.has(name)) target = { toFile: file, name };
      else if (imports.has(name)) {
        const imp = imports.get(name);
        target = { spec: imp.spec, name: imp.name };
      }
      if (target) {
        // T.x and ns.x: the property is what is referred to
        const parent = node.parent;
        if (ts.isPropertyAccessExpression(parent) && parent.expression === node) {
          const prop = parent.name.text;
          target = target.name === '*' ? { ...target, name: prop } : { ...target, name: `${target.name}.${prop}` };
        }
        if (target.name !== '*' && !ts.isImportSpecifier(node.parent) && !ts.isImportClause(node.parent) && !ts.isNamespaceImport(node.parent)) {
          const at = pos(node.getStart(sf));
          const call = ts.isCallExpression(parent) && parent.expression === node ||
            (ts.isPropertyAccessExpression(parent) && ts.isCallExpression(parent.parent) && parent.parent.expression === parent);
          refs.push({ toFile: target.toFile || null, spec: target.spec || null, toName: target.name,
                      from: enclosing(node), file, line: at.line, col: at.col, call: !!call });
        }
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(sf);
  return { vars, refs, imports, sf };
}

function isDeclarationName(node) {
  const p = node.parent;
  return !!p && (p.name === node) && (ts.isFunctionDeclaration(p) || ts.isClassDeclaration(p) || ts.isVariableDeclaration(p) ||
    ts.isMethodDeclaration(p) || ts.isPropertyDeclaration(p) || ts.isPropertyAssignment(p) || ts.isParameter(p) ||
    ts.isBindingElement(p) || ts.isImportSpecifier(p) || ts.isImportClause(p) || ts.isNamespaceImport(p) || ts.isPropertySignature(p));
}

const cache = new Map(); // file -> {mtime, idKey, result}

/**
 * {files: Map(file -> {vars, refs, sf}), analysed: n} for the code files
 * `files`, refs resolved to files. Unchanged files come from the cache.
 */
function analyse(root, files, idKey) {
  const pattern = tagPattern(idKey);
  const base = baseUrl(root);
  const out = new Map();
  let analysed = 0;
  for (const file of files) {
    const mtime = fs.statSync(file).mtimeMs;
    const hit = cache.get(file);
    if (hit && hit.mtime === mtime && hit.idKey === idKey) {
      out.set(file, hit.result);
      continue;
    }
    const rel = path.relative(root, file);
    const r = analyseFile(file, rel, pattern);
    for (const ref of r.refs) if (ref.spec) ref.toFile = resolveModule(file, ref.spec, base);
    cache.set(file, { mtime, idKey, result: r });
    out.set(file, r);
    analysed++;
  }
  return { files: out, analysed };
}

module.exports = { analyse, isCode, EXTS, tagPattern };
