# meta-source for JavaScript and TypeScript

EXPERIMENTAL — the module is new; names and formats may change.

The same registry, read from a JavaScript or TypeScript codebase. A
declaration says which id it implements in its JSDoc, with the meta key,
as a Clojure var does in its metadata:

```ts
/** @meta-source/id :fn/render-receipt */
export function renderReceipt(order: Order): string { ... }
```

```clojure
;; (defn ^{:meta-source/id :fn/price} price [lines] ...)
```

Everything else is shared with the Clojure reader: `meta-source.edn`, the
EDN registry files, the questions and the shape of their answers, and the
`serve` protocol editors use. A repo with both languages has one registry;
each reader indexes the code in its own language, and about the registry
both answer the same. `conformance/` holds that promise: its expected
answers come from the Clojure reader, and both readers' tests check them.

Files are parsed with TypeScript's parser. Nothing is compiled, loaded or
run.

## Use

```sh
npx github:semantic-namespace/meta-source check --brief
npx github:semantic-namespace/meta-source check --fail-on error   # in CI
npx github:semantic-namespace/meta-source where :fn/render-receipt
```

The command is `meta-source-js`, with the Clojure command's commands and
flags: `where`, `uses`, `deps`, `data`, `check` (`--brief`, `--fail-on`),
`summary`, `hover`, `diagnostics`, `serve`, and `--root`, `--registry`,
`--src`. Answers are JSON, as the Clojure command's `--json`.

`meta-source.edn` is the one the Clojure reader reads. This reader uses
`:registry`, `:src`, `:id-key`, `:dataflow` and `:merge`, and `:js` for its
own settings; it ignores the rest. In `:src`, it reads `.ts`, `.tsx`, `.mts`,
`.cts`, `.js`, `.jsx`, `.mjs` and `.cjs` files (not `.d.ts`), and skips
`node_modules`.

```clojure
{:registry ["registry"]
 :src ["src" "web"]                       ; Clojure in src/, TypeScript in web/
 :id-key :meta-source/id                  ; the JSDoc tag is @meta-source/id
 :js {:packs {"./tools/route-pack.js" {:prefix "/shop"}}}}
```

## What it reads

- **Declarations** carrying the tag: functions, classes and their methods,
  consts, and the properties of an object a const holds. A declaration is
  named `<file>#<name>`, `<file>#<Class>.<method>` or `<file>#<T>.<key>`:
  `web/money.ts#ROUTES.receipt`.
- **References** to declarations of the repo, resolved through imports
  (relative ones and the tsconfig `baseUrl`'s): `uses` lists what refers to
  an id's declaration, and from which declaration, with its id when it has
  one.

What `check` reports from code: `dev-id-on-several-vars` (an id tagged on
two declarations, error), `binding-only` (a tag whose id no source says
anything about yet, info), and what packs report. From the registry, the
same as the Clojure reader: `unreadable-registry-file`,
`conflicting-values`.

Not yet: `undeclared-call` and `keys-outside-contract` (they need what a
function reads and calls, which TypeScript code says differently from
Clojure's keywords), and data keys mentioned in code.

## Editors

`serve` answers the Clojure reader's ops (`hover`, `check`, `diagnostics`,
`summary`) and, because JavaScript has no keyword to put the point on,
`hover-at`: what the id a position names — a tagged declaration's name or
tag, or a reference to one — says.

```json
{"id":1,"op":"hover-at","file":"/abs/web/receipt.ts","line":6,"col":34,"text":"<the line>"}
```

[`vscode/`](../vscode) is a VS Code extension that runs both readers for a
folder with `meta-source.edn`, and asks both for go to definition and find
references: from an id in an EDN file, its Clojure var and its TypeScript
declaration alike.

## Packs

A JS pack is a module exporting an object with an `id` and any of:

- `entities(ctx)` → `[{id, file, line, entry}]`: entities it reads, one more
  source each, merged with the registry files' (`entry` a `Map` of keyword
  to value, `ctx.kw(':ns/name')` for keywords);
- `check(ctx)` → `[{severity, problem, 'dev-id', file, line, message}]`;
- `describe(ctx, id, entry)` → the line a hover shows under the id.

`ctx` gives `opts` (the pack's options from `meta-source.edn`), `vars()`,
`refs()`, `entities()`, `implOf(id)`, `refsTo(var)`, `sourceFile(file)` (the
TypeScript AST), `ts` (the TypeScript module that parsed it), `kw`, `edn`
and `relative(file)`. `js/test/fixtures/routes` has one.

A pack named by a path (`"./tools/route-pack.js"`) is the project's own. One
named by a module is looked up from the project's root, then from where
meta-source is installed, so a pack in another package runs without adding
either to the project:

```sh
npx -p github:semantic-namespace/meta-source -p <the pack's package> meta-source-js check
```

## Tests

```sh
npm install && npm test   # conformance, and this reader's own tests
bb conformance            # the Clojure reader against the same answers
```
