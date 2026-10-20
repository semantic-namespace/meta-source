# Reference

The commands, what `check` finds, facts in EDN and the meta key, and the
Emacs package. Packs, and how to write one: [packs.md](packs.md).

![meta-source: installed with bbin, asked from the shell, then Flymake and hover in Emacs](images/meta-source-demo.gif)

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

