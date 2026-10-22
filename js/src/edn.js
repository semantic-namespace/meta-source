'use strict';
// EDN as data: the registry files and meta-source.edn. Keywords and symbols
// are interned (one object per name, so they work as Map keys and compare
// with ===), maps read as Map, vectors as arrays, sets as EdnSet, lists as
// EdnList, #tag forms as Tagged. prStr writes a value as Clojure prints it;
// toJson as cheshire encodes it (a keyword as its text without the colon).

class Keyword {
  constructor(text) {
    this.text = text; // ":ns/name"
  }
  get namespace() {
    const i = this.text.indexOf('/');
    return i > 0 ? this.text.slice(1, i) : null;
  }
  get name() {
    const i = this.text.indexOf('/');
    return i > 0 ? this.text.slice(i + 1) : this.text.slice(1);
  }
  toString() {
    return this.text;
  }
}
class Sym {
  constructor(text) {
    this.text = text;
  }
  toString() {
    return this.text;
  }
}
class EdnSet {
  constructor(items) {
    this.items = [];
    for (const x of items) if (!this.items.some((y) => equal(x, y))) this.items.push(x);
  }
}
class EdnList {
  constructor(items) {
    this.items = items;
  }
}
class Tagged {
  constructor(tag, value) {
    this.tag = tag;
    this.value = value;
  }
}

const keywords = new Map();
const symbols = new Map();
/** The keyword written `text` (":ns/name", or "ns/name"). */
function kw(text) {
  const t = text.startsWith(':') ? text : ':' + text;
  let k = keywords.get(t);
  if (!k) keywords.set(t, (k = new Keyword(t)));
  return k;
}
function sym(text) {
  let s = symbols.get(text);
  if (!s) symbols.set(text, (s = new Sym(text)));
  return s;
}
const isKeyword = (x) => x instanceof Keyword;
const isQualifiedKeyword = (x) => x instanceof Keyword && x.namespace !== null;

function equal(a, b) {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => equal(x, b[i]));
  if (a instanceof EdnList && b instanceof EdnList) return equal(a.items, b.items);
  if (a instanceof EdnSet && b instanceof EdnSet)
    return a.items.length === b.items.length && a.items.every((x) => b.items.some((y) => equal(x, y)));
  if (a instanceof Map && b instanceof Map) {
    if (a.size !== b.size) return false;
    for (const [k, v] of a) {
      const bk = [...b.keys()].find((x) => equal(x, k));
      if (bk === undefined || !equal(v, b.get(bk))) return false;
    }
    return true;
  }
  if (a instanceof Tagged && b instanceof Tagged) return a.tag === b.tag && equal(a.value, b.value);
  return false;
}

// ---------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------

const DELIM = /[\s,()[\]{}";]/;

function reader(text) {
  let i = 0;
  let line = 1;
  const peek = () => text[i];
  const next = () => {
    const c = text[i++];
    if (c === '\n') line++;
    return c;
  };
  const fail = (msg) => {
    const e = new Error(`${msg}, at line ${line}`);
    e.line = line;
    throw e;
  };

  function skip() {
    for (;;) {
      const c = peek();
      if (c === undefined) return;
      if (/[\s,]/.test(c)) next();
      else if (c === ';') while (peek() !== undefined && peek() !== '\n') next();
      else if (c === '#' && text[i + 1] === '_') {
        next();
        next();
        read();
      } else return;
    }
  }

  function seq(close) {
    const out = [];
    for (;;) {
      skip();
      if (peek() === undefined) fail(`EOF while reading, expected ${close}`);
      if (peek() === close) {
        next();
        return out;
      }
      out.push(read());
    }
  }

  function string() {
    next();
    let s = '';
    for (;;) {
      const c = next();
      if (c === undefined) fail('EOF while reading a string');
      if (c === '"') return s;
      if (c === '\\') {
        const e = next();
        if (e === 'u') {
          s += String.fromCharCode(parseInt(text.slice(i, i + 4), 16));
          i += 4;
        } else s += { n: '\n', t: '\t', r: '\r', b: '\b', f: '\f', '"': '"', '\\': '\\' }[e] ?? e;
      } else s += c;
    }
  }

  function token() {
    let t = '';
    while (peek() !== undefined && !DELIM.test(peek())) t += next();
    return t;
  }

  function read() {
    skip();
    const c = peek();
    if (c === undefined) fail('EOF while reading');
    if (c === '{') {
      next();
      const items = seq('}');
      if (items.length % 2) fail('a map with an odd number of forms');
      const m = new Map();
      for (let k = 0; k < items.length; k += 2) m.set(items[k], items[k + 1]);
      return m;
    }
    if (c === '[') return next(), seq(']');
    if (c === '(') return next(), new EdnList(seq(')'));
    if (c === '"') return string();
    if (c === '\\') {
      next();
      const t = next() + token();
      return { newline: '\n', space: ' ', tab: '\t', return: '\r' }[t] ?? t;
    }
    if (c === '#') {
      next();
      if (peek() === '{') return next(), new EdnSet(seq('}'));
      if (peek() === '"') return new Tagged('regex', string());
      const tag = token();
      return new Tagged(tag, read());
    }
    if (c === '}' || c === ']' || c === ')') fail(`unmatched ${c}`);
    const t = token();
    if (t === 'nil') return null;
    if (t === 'true') return true;
    if (t === 'false') return false;
    if (/^[-+]?\d+(\.\d+)?([eE][-+]?\d+)?M?$/.test(t)) return Number(t.replace(/M$/, ''));
    if (/^[-+]?\d+N$/.test(t)) return Number(t.slice(0, -1));
    if (t.startsWith('::')) fail(`${t}: an auto-resolved keyword has no namespace in EDN`);
    if (t.startsWith(':')) return kw(t);
    return sym(t);
  }

  return { read, skip, peek, next, line: () => line, at: () => i };
}

/** The value of `text`'s single form. */
function readString(text) {
  return reader(text).read();
}

/**
 * [{key, value, line}] for the top-level map in `text`, line being where
 * the key is written, or null when the text doesn't start with a map.
 * Anything but whitespace and comments after the map is an error: it would
 * be read by nobody.
 */
function keyedLines(text) {
  const r = reader(text);
  r.skip();
  if (r.peek() !== '{') return null;
  r.next();
  const out = [];
  for (;;) {
    r.skip();
    if (r.peek() === undefined) {
      const e = new Error(`EOF while reading, expected }, at line ${r.line()}`);
      throw e;
    }
    if (r.peek() === '}') {
      r.next();
      r.skip();
      if (r.peek() !== undefined) throw new Error(`something after the map, at line ${r.line()}`);
      return out;
    }
    const line = r.line();
    const key = r.read();
    r.skip();
    if (r.peek() === '}') throw new Error(`a key with no value, at line ${line}`);
    const value = r.read();
    out.push({ key, value, line });
  }
}

// ---------------------------------------------------------------------------
// Writing
// ---------------------------------------------------------------------------

function prStr(x) {
  if (x === null || x === undefined) return 'nil';
  if (x instanceof Keyword || x instanceof Sym) return x.text;
  if (typeof x === 'string') return JSON.stringify(x);
  if (typeof x === 'number' || typeof x === 'boolean') return String(x);
  if (Array.isArray(x)) return '[' + x.map(prStr).join(' ') + ']';
  if (x instanceof EdnList) return '(' + x.items.map(prStr).join(' ') + ')';
  if (x instanceof EdnSet) return '#{' + x.items.map(prStr).join(' ') + '}';
  if (x instanceof Map) return '{' + [...x].map(([k, v]) => prStr(k) + ' ' + prStr(v)).join(', ') + '}';
  if (x instanceof Tagged) return x.tag === 'regex' ? '#' + JSON.stringify(x.value) : '#' + x.tag + ' ' + prStr(x.value);
  return String(x);
}

/** As cheshire encodes it: keywords and symbols as their text without the colon, collections as arrays. */
function toJson(x) {
  if (x === undefined) return null;
  if (x === null || typeof x === 'string' || typeof x === 'number' || typeof x === 'boolean') return x;
  if (x instanceof Keyword) return x.text.slice(1);
  if (x instanceof Sym) return x.text;
  if (Array.isArray(x)) return x.map(toJson);
  if (x instanceof EdnList || x instanceof EdnSet) return x.items.map(toJson);
  if (x instanceof Map) {
    const o = {};
    for (const [k, v] of x) o[typeof k === 'string' ? k : toJson(k)] = toJson(v);
    return o;
  }
  if (x instanceof Tagged) return prStr(x);
  if (typeof x === 'object') {
    const o = {};
    for (const [k, v] of Object.entries(x)) o[k] = toJson(v);
    return o;
  }
  return String(x);
}

/** Every value in `x`, x included, depth first (tree-seq coll? seq). */
function* walk(x) {
  yield x;
  if (Array.isArray(x)) for (const y of x) yield* walk(y);
  else if (x instanceof EdnList || x instanceof EdnSet) for (const y of x.items) yield* walk(y);
  else if (x instanceof Map) for (const [k, v] of x) yield* walk(k), yield* walk(v);
  else if (x instanceof Tagged) yield* walk(x.value);
}

/** The items of a collection value, [] for anything else. */
const itemsOf = (x) => (Array.isArray(x) ? x : x instanceof EdnList || x instanceof EdnSet ? x.items : []);

module.exports = {
  Keyword, Sym, EdnSet, EdnList, Tagged,
  kw, sym, isKeyword, isQualifiedKeyword, equal,
  readString, keyedLines, prStr, toJson, walk, itemsOf,
};
