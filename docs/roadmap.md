# Roadmap

meta-source reads each library's registry by its conventions, statically.
Every pack so far met real apps that broke it, and was fixed by them:
re-frame's never-dispatched events on Athens (43 down to 20), function
contracts' undeclared calls on hermes (75 false), reitit's ids on sepal (45
false conflicts). Those apps were run by hand. The next work makes what they
taught checked on every change, then measures how much each pack reads, then
adds packs.

## 1. A pinned corpus of real apps, in CI

The open-source apps each pack was validated on, pinned at a commit, read
(never run), with their results kept as a snapshot:

| App | Packs it exercises |
|---|---|
| [athensresearch/athens](https://github.com/athensresearch/athens) | re-frame, Datascript |
| [wardle/hermes](https://github.com/wardle/hermes) | spec, function contracts, Pathom |
| [repath-studio](https://github.com/repath-studio/repath-studio) | re-frame, malli function schemas |
| [jacekschae/conduit](https://github.com/jacekschae/conduit) | re-frame |
| [lipas](https://github.com/lipas-liikuntapaikat/lipas) | reitit (backend and frontend) |
| [sepal](https://github.com/sepal-app/sepal) | reitit |
| [Olical/clojure-dap](https://github.com/Olical/clojure-dap), [clj-commons/antq](https://github.com/clj-commons/antq) | malli `mx/defn`, `:malli/schema` |
| [mauricioszabo/duck-repled](https://github.com/mauricioszabo/duck-repled) | Pathom through a wrapper macro, malli registry |
| [Swirrl/datahost-prototypes](https://github.com/Swirrl/datahost-prototypes), [walkable-server/realworld-fulcro](https://github.com/walkable-server/realworld-fulcro) | integrant, duct |
| [Zetawar/zetawar](https://github.com/Zetawar/zetawar) | Datascript |

- `corpus.edn`: each app's repo, commit, `meta-source.edn` (the packs and
  options it needs), and what it is there for.
- `bb corpus`: shallow-clones each at its commit into a cache, builds the
  index, and compares a summary against `corpus/<app>.edn`: the read and
  not-read counts per pack, and each finding as `[problem dev-id file line]`.
  A difference fails, printed as what appeared and what went away.
- `bb corpus --update` rewrites the snapshots, so accepting a change is a
  reviewed diff.
- CI runs it on a schedule and on pull requests that touch a pack, since
  cloning a dozen apps is slower than the smoke tests.

A finding that is wrong stays in the snapshot until it is fixed, marked as
known: the snapshot says what the tool does, not what it should do.

## 2. A conformance suite every pack passes

The same checks over every pack, on every fixture and corpus app:

- what `:pack/read` and `:pack/read-data` return is data: it round-trips
  through EDN (it is cached);
- every result has a `:row` and a `:kind` of the pack's own kinds, and either
  a `:dev-id` (a keyword) and an `:entry`, or a `:skipped` reason;
- an entry's keys are in the pack's `:pack/vocabulary` namespaces;
- a result with `:refers true` is never an id's primary definition;
- the keys `:pack/dataflow` names hold collections of keywords;
- a pack's checks return findings with a `:severity`, a `:problem` and a
  location;
- reading the same files twice gives the same results, in the same order.

`bb conformance`, in CI with the smoke tests. A pack of a project's own can
run it too.

## 3. What the running system registered, for every pack

Pathom has it already: a snapshot of what the running environment
registered, compared with what the code says, filling what static reading
can't read and reporting where they disagree. The same for:

| Pack | The running system's own registry |
|---|---|
| integrant | the system map's keys after `ig/prep` / `ig/expand` |
| re-frame | `re-frame.registrar/kind->id->handler` |
| reitit | `(reitit.core/routes router)`: every compiled route, `:expand` ones included |
| Datascript | `(d/schema db)` of the live connection |
| spec, malli | `(s/registry)`, `(m/function-schemas)` |

With a snapshot, each pack reports a measured coverage, what static reading
found of what registered ("97% of the routes, 3 built by `:expand`"),
instead of a claim.

## 4. One reading library, and written conventions

The same helpers live in several packs: qualifying a symbol, collecting
qualified keywords, following `let`, `if`, `case`, `cond->` to what a form
returns, resolving a def in the same file. They move to `meta-source.read`,
tested once. And the conventions packs follow, written in
[packs.md](packs.md):

- **ids**: a library's own id when it has one (a qualified keyword, a var);
  otherwise one scoped to where it is written (reitit's unnamed routes);
- **references**: a place that refers to an id is a result with
  `:refers true` and a marker key (re-frame, reitit, Datascript); references
  inside an entry (integrant refs, Pathom inputs) are entry keys;
- **`:provided`**: ids a library or another system registers, for every
  pack that checks references;
- **severity**: a warning when what the code does fails at runtime, info when
  it may be intended or the pack read only part of it.

## 5. More packs

| Pack | Reads | What it checks |
|---|---|---|
| component, mount, donut.system, clip | systems as data | refs to components no system defines, as integrant |
| lacinia | GraphQL schema EDN, resolver attachments | fields with no resolver, resolvers attached to no field |
| fulcro, fulcro-rad | `defsc` queries and idents, mutations, `defattr` | attributes as data keys |
| pedestal | routes and interceptors | as reitit |
| clara, O'Doyle | rules' facts | dataflow between rules |
| Datomic | the peer and client APIs' pulls and lookup refs | as Datascript (its schema files are read already) |

## Done

integrant, aero, clojure.spec and malli, function contracts (`s/fdef`, `m/=>`,
`:malli/schema`, `mx/defn`), Pathom (with its runtime snapshot), re-frame,
reitit, Datascript, a project's own packs, a project's own rules as data, the
project's clj-kondo config, the Emacs package.
