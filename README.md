# meta-source

**clj-kondo for system specifications.**

clj-kondo checks your code. meta-source checks what your code *declares*:
your integrant system, your specs and malli schemas, your Pathom resolvers,
your re-frame events and subscriptions.
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
- Does every `dispatch` reach a registered event? re-frame only logs it,
  when it runs.
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
4. **Nothing**: the `s/fdef` the app already has for `place-order` is its
   contract, so the code reading `:order/discount`, which the fdef doesn't
   declare, is underlined. A function with no fdef can opt in with a meta
   key and a contract in EDN.

What it cost, by `git status`: a `meta-source.edn` and a `registry/`
directory. No line of code changed.

### On a re-frame app

![A re-frame app adopting meta-source in Emacs](docs/images/meta-source-re-frame.gif)

1. **`{:packs {:re-frame {:provided [:http-xhrio]}}}`**: an `:on-failure`
   callback to an event nothing registers, and a subscription with a typo.
   re-frame would only log them, when they run.
2. **`C-c .` on `::events/checkout`**: where it is registered and
   dispatched from, what it dispatches, the effects it returns, the keys it
   writes.
3. **`:spec {}` and `{:user/email {:data/privacy :pii}}`**: `C-c .` on
   `:user/email` gives its spec, that it is PII, the event that writes it and
   the subscription that reads it.
4. **The typo fixed**: the underline goes.

### Your own rules

![A team writes its own rules as data, in Emacs](docs/images/meta-source-rules.gif)

A shop meta-source already reads gets its team's rules, in a `rules/` file:

1. **Whatever reads PII is audited**: the resolver reading `:user/email`
   is marked where it is defined.
2. **`{:shop.resolvers/user-by-email {:audit/logged true}}`** in EDN: the mark
   goes. The rule sees the EDN fact and the Pathom resolver as one entity.
3. **What `init-key` starts, `halt-key!` stops**: the two components with an
   `init-key` and no `halt-key!`, marked at their `defmethod`s.

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
meta-source check --brief --fail-on error   # in CI: exit 1 on an error
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

## JavaScript and TypeScript

EXPERIMENTAL. A second reader, for JavaScript and TypeScript code, shares
the registry, `meta-source.edn`, the questions and the editor protocol. A
declaration names the id it implements in its JSDoc,
`/** @meta-source/id :fn/render-receipt */`, and a repo in both languages
has one registry, each reader indexing its own language's code:

```sh
npx github:semantic-namespace/meta-source check --brief
```

The [VS Code extension](vscode) runs both readers: here a hover on a
TypeScript function, an EDN id whose implementation the Clojure reader
finds, go to definition from the EDN to the TypeScript, and an id tagged on
two declarations. See [js/README.md](js/README.md).

![VS Code: one registry, a Clojure and a TypeScript implementation](docs/images/vscode-polyglot.gif)

## How it relates to clj-kondo and clojure-lsp

They know your code: namespaces, vars, calls, arities. meta-source knows
what your keywords *mean* in the libraries that declare them: that `:shop/db`
is an integrant component other components refer to, that `:user/email` is a
key a spec shapes and a resolver returns. The registries are already there, each
library's own; nothing answers the questions that cross them.

meta-source builds on clj-kondo: it runs clj-kondo's analysis to find the
forms each library uses to declare things, then reads those forms as data.
That analysis uses your project's `.clj-kondo` config, as your editor's
clj-kondo does: a macro you've taught clj-kondo (`:lint-as`, a hook, a
library's imported config) is read as what it defines.

## How it's built

meta-source builds one index of the ids your code shares with its
registries. It reads clj-kondo's analysis of your code, with each library's
pack reading that library's forms as data, plus your EDN files and any
snapshots of the running system, and merges what they say per id. The
CLI, `serve` (and the editors through it) and CI all ask that one index.
[docs/architecture.md](docs/architecture.md) explains the pieces and how
they fit together: the pipeline, the merge, the questions and checks, the
two readers, and where each piece lives.

## What it reads

- **Packs** read one library's registry each: **aero** (the environment
  variables a config needs and which deployment sets them, includes,
  profiles, tags with no reader), **integrant** (components,
  refs, composite keys, `#env`, variant configs, duct profiles,
  `defmethod`s, `derive`), **clojure.spec and malli** (data keys and their
  shapes), **Pathom 3 and 2** (resolvers and mutations, inputs and outputs,
  declared or inferred, through your wrapper macros), **re-frame** (events,
  subscriptions, effects and coeffects, what dispatches and subscribes to
  them, the keys handlers read and write), **reitit** (routes, their
  handlers, inherited middleware, the keys their parameters take, links to
  route names), **Datascript** (attributes from schemas and Datomic-style
  schema files, the pulls and lookup refs that need them to be refs or
  unique), **tilakone** (state machines, their states and transitions, a
  transition to no state), **grain** (a grain app's event model: commands,
  queries, read models, processors, events, what each reads, produces and
  consumes, where the def sites and the event model disagree), **namespaces**
  (your own namespaces as entities,
  by a naming convention you state, and what each requires: rules on which
  may require which), and **your own**: a pack is one map.
  [docs/packs.md](docs/packs.md)
- **Facts merge per keyword.** A file says things about an id; it doesn't
  own it. An EDN file can add `:data/privacy :pii` to a key a spec defines.
  Two sources giving one key different values is a conflict, named with both
  places, never last-write-wins.
- **Nothing is evaluated.** The Clojure reader and clj-kondo, in babashka.
  Your code is never loaded. (clj-kondo runs your `.clj-kondo` hooks, if you
  have any, in its own interpreter, as it does in your editor.) An option written as code is reported as not
  read rather than guessed.
- **What runs can be compared with what is written.** A running Pathom
  environment can write a snapshot; meta-source fills in what the code
  doesn't say and reports where the two disagree.
- **Your own rules, as data**, over every source at once:
  `{:when {:= {:data/privacy :pii}} :require [{:consumers {:all {:has :audit/logged}}}]}`
  reports each PII key read by something not audited, and where.
  [docs/reference.md](docs/reference.md#your-own-rules)
- **Plain functions** with an `s/fdef` or a malli `m/=>` are checked against
  it: code reading a key the contract's specs don't list is reported. Others
  opt in with one meta key and a contract in your own vocabulary.
  [docs/reference.md](docs/reference.md)

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

Working today: the integrant, aero, spec/malli (with `s/fdef` and `m/=>`),
Pathom, re-frame, reitit and Datascript packs, `check`, the queries, the Emacs
package, a project's own packs, and a project's own rules. New and
experimental: the JavaScript/TypeScript reader and the VS Code extension. Next: snapshots of
the running system for every pack, and a pinned corpus of real apps checked
in CI.

## More

- [review/](review/README.md): on [sdiff](https://github.com/semantic-namespace/diff)'s
  review page, the findings a change introduces and those it fixes
- [docs/reference.md](docs/reference.md): every command, what `check`
  finds, EDN facts and the meta key, the Emacs package
- [docs/architecture.md](docs/architecture.md): how it's built, and how the
  pieces fit together
- [docs/packs.md](docs/packs.md): each pack's options, and writing your own

```sh
bb smoke                   # the index, packs and diagnostics on test/fixtures/shop
bb emacs-smoke             # meta-source.el against a real meta-source serve
bb pathom-snapshot-check   # the fixture's Pathom snapshot, rebuilt from real Pathom
bb conformance             # the answers every reader must give (conformance/)
npm test                   # the JavaScript reader, against the same answers
```

meta-source started as `atlas.source` in
[atlas](https://github.com/semantic-namespace/atlas).

## License

MIT
