# meta-source

**Decomplecting Clojure meta.** What a Clojure project declares about itself
(its integrant system, its specs and malli schemas, its Pathom resolvers, its
own registries) is read where it is declared and cross-referenced by its
qualified keywords. With babashka and the Clojure reader; nothing is loaded or
run.

![meta-source: installed with bbin, asked from the shell, then Flymake and hover in Emacs](docs/images/meta-source-demo.gif)

> **Experimental.** The file format, the pack interface and the CLI may change.

## Why

Data-oriented Clojure libraries each keep a registry, named by qualified
keywords, with the references between entries as data:

| Library | Identity | References | Contract |
|---|---|---|---|
| integrant | config keys | `#ig/ref` | the component's spec |
| clojure.spec, malli | spec and schema names | nested keys | the shape |
| Pathom | resolver op-names | inputs and outputs | the EQL |
| your project | an EDN file, a var's meta key | whatever it names | what you declare |

Each library answers questions about its own registry. None answers the
questions that cross them: *this handler needs this component, returns
`:user/email` shaped by this spec, and that key is consumed by this resolver.*
meta-source is that cross-reference, and it keeps it true: `check` reports
where what one place declares and another uses disagree.

Nothing migrates. Each library stays the authority on what it declares;
meta-source reads it in place. That decomplects:

- **identity from place**: an id is a qualified keyword, wherever it is written;
- **facts from owners**: files contribute facts about an id, they don't own it,
  and two sources giving the same key different values is a named conflict,
  never last-write-wins;
- **what the code says from what the process registered**: a library's running
  process can write a snapshot, compared with what was read from the files;
- **reading from evaluating**: the Clojure reader and clj-kondo, in babashka.
  Your code is never loaded.

## Install

```sh
bbin install io.github.semantic-namespace/meta-source
```

Needs [babashka](https://babashka.org), [bbin](https://github.com/babashka/bbin),
and Java the first time, to resolve dependencies. clj-kondo comes as a pod.

## A project in four lines

```clojure
;; meta-source.edn, at the project root
{:src ["src"]
 :packs {:integrant {:configs ["resources"]}
         :spec {}
         :pathom {}}}
```

```sh
meta-source summary            # what each pack read
meta-source check              # where declarations and code disagree
meta-source uses :app/db       # what refers to it, and what calls its var
```

The first run analyses the code; later runs re-read only the files that
changed. The cache lives in `.meta-source/`: add it to `.gitignore`.

## Adopting it, step by step

![Adopting meta-source in an existing app, in Emacs, one pack at a time](docs/images/meta-source-adoption.gif)

An existing app with an integrant system, specs and Pathom resolvers, open
in Emacs, with no meta-source in it. Every edit is typed in the recording;
each step pays off where it lands:

1. **A `meta-source.edn` with the integrant pack.** In `system.edn`, opened
   before the config existed, Flymake underlines a ref to a component no
   config defines, found without starting the system.
2. **Add `:pathom {}`.** In `resolvers.clj`: resolver inputs nothing produces.
3. **Add `:spec {}`, and one fact no library has a place for**
   (`{:user/email {:data/privacy :pii}}`). `C-c .` on `:user/email` gives its
   spec, that it is PII, and the resolvers that produce and consume it, from
   three sources at once.
4. **One plain function opts in**: a meta key on the `defn`, its contract
   in two lines of EDN, and `:dataflow` in the config. Flymake underlines the
   code reading `:order/discount`, which the contract doesn't declare.
5. **What it cost** (`git status`): one changed line of code, a
   `meta-source.edn` and a `registry/` directory. No dependency added, no
   code loaded, nothing migrated.

## Commands

| | |
|---|---|
| `where <id>` | each file that declares it, its var, its merged entry |
| `uses <id>` | entries that name it, and code that calls its var |
| `deps <id>` | the entities its entry names |
| `data <key>` | a data key's shape, producers and consumers, and the schemas it is a key of |
| `check` | where code and declarations disagree, most severe first |
| `diagnostics <file>` | `check`'s findings in one file, with the line and columns to underline |
| `summary` | counts: entities by source, forms each pack read and couldn't |
| `hover <keyword>` | what an editor shows for a keyword |
| `serve` | long-lived, one JSON request and reply per line: what the editor talks to |

Output is EDN, or JSON with `--json`.

## What `check` finds

| Finding | Severity |
|---|---|
| two sources give one key different values (`:conflicting-values`), naming each place | error |
| two vars carry the same meta key value | error |
| an integrant ref no config defines | warning |
| one id declared by two packs (a resolver and a component sharing a keyword) | warning |
| code and the running process disagree about an operation | warning |
| a key code uses that isn't in its function's declared contract | info |
| a resolver input nothing produces; an `s/keys` key with no spec; unqualified malli keys | info |
| an `init-key` for a key no config has; a resolver defined but not registered | info |
| a form a pack couldn't read, and why | info |

Findings are reported, never guessed around: an option written as code is
reported as not read, rather than read wrongly. Next: invariants of your own,
written as data (`{:when {...} :require [...]}`) over the same index.

## Packs

- **integrant**: components, `#ig/ref`s, composite keys, `#env`, variant and
  test configs, duct profiles, `defmethod`s, `derive`.
- **clojure.spec and malli**: data keys and their shapes. A spec under a
  component's key is that component's shape.
- **Pathom 3 and 2**: resolvers and mutations, inputs and outputs, declared
  or inferred as Pathom 3 does, through your wrapper macros. A running
  environment can write a snapshot: what it registered fills what the code
  doesn't say, and disagreements are reported.
- **your own**: a pack is one map, `:pack/find` and `:pack/read` over
  clj-kondo's analysis, or `:pack/data-files` and `:pack/read-data` over EDN.
  Name it under `:packs`.

Each pack, its options, and how to write one: [docs/packs.md](docs/packs.md).

## Facts in EDN, and plain functions

EDN files hold the facts no library has a place for: privacy classes,
ownership, contracts for plain functions. An entry adds to whatever else
describes the same id:

```clojure
;; registry/privacy.edn
{:user/email {:data/privacy :pii}}
```

A plain function says which id it implements with one meta key:

```clojure
(defn ^{:meta-source/id :fn/place-order} place-order [ctx] ...)
```

```clojure
;; registry/functions.edn, in your own words
{:fn/place-order {:fn/reads [:user/id :order/lines]
                  :fn/returns [:order/id :order/total]}}
```

```clojure
;; meta-source.edn: which of your words list data
{:dataflow {:consumes [:fn/reads] :produces [:fn/returns]}}
```

A var with the meta key and no entry is valid: an entity with its binding
only, reported as info. `:id-key` in `meta-source.edn` chooses another meta
key; `:merge {key :union}` unions a set-valued key across files instead of
reporting a conflict.

## Emacs

```elisp
;; emacs/meta-source.el, anywhere on your load path
(require 'meta-source)
(meta-source-setup)   ; Clojure buffers of projects with a meta-source.edn
```

On any qualified keyword, in code or EDN: eldoc describes it, `M-.` goes to
each place that declares it, `M-?` lists what uses it, `C-c .` opens a
describe buffer. Flymake underlines `check`'s findings where they are. The
package runs the `meta-source` command, or `bb meta-source` from the checkout
it is in. Emacs 27 or later.

## Development

```sh
bb smoke                   # the index, packs and diagnostics on test/fixtures/shop
bb emacs-smoke             # meta-source.el against a real meta-source serve
bb pathom-snapshot-check   # the fixture's Pathom snapshot, rebuilt from real Pathom
```

meta-source started as `atlas.source` in
[atlas](https://github.com/semantic-namespace/atlas), which is to read its own
registry through an atlas pack.

## License

MIT
