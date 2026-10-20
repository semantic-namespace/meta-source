# meta-source

**clj-kondo for system specifications.**

clj-kondo checks your code. meta-source checks what your code *declares*:
your integrant system, your specs and malli schemas, your Pathom resolvers.
It reads them where they are, together, without running your app, and tells
you where they disagree.

```
$ meta-source check --brief
warning resources/system.edn:8             no config defines :shop/metrics
info    src/shop/handlers.clj:7            :order/discount is used here but is not in :fn/place-order's contract
info    src/shop/spec.clj:12               :user/emial has no spec of its own
info    src/shop/resolvers.clj:15          nothing produces :order/lines
info    src/shop/resolvers.clj:18          nothing produces :user/loyalty-tier
```

- Is every `#ig/ref` in your system actually configured? Find out before
  `ig/init` does.
- Did someone typo a key inside an `s/keys`? spec won't tell you; it accepts
  keys with no spec.
- Which resolvers and functions read or return `:user/email`, and is it PII?
- What depends on `:shop/db`?
- Does this function read keys its contract doesn't declare?

No migration, and nothing to annotate to get started: one `meta-source.edn`
and a babashka command. In Emacs, the findings are underlined where they are.

> **Experimental.** The file format, the pack interface and the CLI may change.

## Adopting it in an existing app

![Adopting meta-source in an existing app, in Emacs, one pack at a time](docs/images/meta-source-adoption.gif)

An app with an integrant system, specs and Pathom resolvers, open in Emacs,
adopting meta-source one line at a time (every edit is typed in the
recording):

1. **`{:packs {:integrant {:configs ["resources"]}}}`**: a ref to a
   component no config defines, underlined in `system.edn`.
2. **`:pathom {}`**: resolver inputs nothing produces.
3. **`:spec {}`, and one fact in EDN, `{:user/email {:data/privacy :pii}}`**:
   `C-c .` on `:user/email` puts three sources together:

   ```
   :user/email    data key

   spec       (s/and string? #(re-find #"@" %1))    src/shop/spec.clj:5
   :data/privacy  :pii
   member of
     :shop.spec/user    src/shop/spec.clj:12

   produced by
     :shop.resolvers/user-by-id    src/shop/resolvers.clj:11

   consumed by
     :shop.resolvers/user-by-email    src/shop/resolvers.clj:4
   ```
4. **A meta key on one `defn`, and its contract in two lines of EDN**: the
   code reading `:order/discount`, which the contract doesn't declare.

What it cost, by `git status`: one changed line of code, a
`meta-source.edn` and a `registry/` directory.

## Install

```sh
bbin install io.github.semantic-namespace/meta-source
```

Needs [babashka](https://babashka.org), [bbin](https://github.com/babashka/bbin),
and Java the first time, to resolve dependencies. clj-kondo comes as a pod.

Then, at your project's root:

```clojure
;; meta-source.edn
{:packs {:integrant {:configs ["resources"]}
         :spec {}
         :pathom {}}}
```

```sh
meta-source check --brief      # where your declarations disagree
meta-source uses :app/db       # what refers to it, and what calls its var
meta-source data :user/email   # a data key's shape, producers and consumers
```

Add `.meta-source/` (its cache) to `.gitignore`. For Emacs:

```elisp
;; emacs/meta-source.el, anywhere on your load path
(require 'meta-source)
(meta-source-setup)
```

Eldoc describes the keyword at point, `M-.` goes to each place that declares
it, `M-?` lists what uses it, `C-c .` opens the describe buffer, and Flymake
underlines what `check` finds.

## How it relates to clj-kondo and clojure-lsp

They know your code: namespaces, vars, calls, arities. meta-source knows
what your keywords *mean* in the libraries that declare them: that `:shop/db`
is an integrant component other components refer to, that `:user/email` is a
key a spec shapes and a resolver returns. The registries are already there, each
library's own; nothing answers the questions that cross them.

meta-source builds on clj-kondo: it runs clj-kondo's analysis to find the
forms each library uses to declare things, then reads those forms as data.

## How it works

- **Packs** read one library's registry each: **integrant** (components,
  refs, composite keys, `#env`, variant configs, duct profiles,
  `defmethod`s, `derive`), **clojure.spec and malli** (data keys and their
  shapes), **Pathom 3 and 2** (resolvers and mutations, inputs and outputs,
  declared or inferred, through your wrapper macros), and **your own**: a
  pack is one map. [docs/packs.md](docs/packs.md)
- **Facts merge per keyword.** A file says things about an id; it doesn't
  own it. An EDN file can add `:data/privacy :pii` to a key a spec defines.
  Two sources giving one key different values is a conflict, named with both
  places, never last-write-wins.
- **Nothing is evaluated.** The Clojure reader and clj-kondo, in babashka.
  Your code is never loaded. An option written as code is reported as not
  read rather than guessed.
- **What runs can be compared with what is written.** A running Pathom
  environment can write a snapshot; meta-source fills in what the code
  doesn't say and reports where the two disagree.
- **Plain functions opt in** with one meta key and a contract in your own
  vocabulary. [docs/reference.md](docs/reference.md)

## Why it's built this way

Decomplecting Clojure meta:

- **identity from place**: an id is a qualified keyword, wherever it is
  written;
- **facts from owners**: files contribute facts about an id; none of them
  owns it;
- **what the code says from what the process registered**: a snapshot of
  the running system, compared with what was read from the files;
- **reading from evaluating**: declarations are data, so they can be read
  without running anything.

Each library stays the authority on what it declares. meta-source is the
cross-reference, and it keeps the cross-reference true.

## Status

Working today: the three packs, `check`, the queries, the Emacs package, and
a project's own packs. Next: invariants of your own, written as data
(`{:when {...} :require [...]}`) over the same index, and more packs
(reitit, re-frame).

## More

- [docs/reference.md](docs/reference.md): every command, what `check`
  finds, EDN facts and the meta key, the Emacs package
- [docs/packs.md](docs/packs.md): each pack's options, and writing your own

```sh
bb smoke                   # the index, packs and diagnostics on test/fixtures/shop
bb emacs-smoke             # meta-source.el against a real meta-source serve
bb pathom-snapshot-check   # the fixture's Pathom snapshot, rebuilt from real Pathom
```

meta-source started as `atlas.source` in
[atlas](https://github.com/semantic-namespace/atlas).

## License

MIT
