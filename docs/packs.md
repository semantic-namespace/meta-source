# Packs

A pack reads one library's own registry, where it is. This page describes the built-in packs, how a key several sources describe is identified, which keys carry data, and how to write a pack of your own. Enable packs under `:packs` in `meta-source.edn`.

## Data keys from clojure.spec and malli: the schema pack

A project that already shapes its data keys with clojure.spec or malli can
have the index read those shapes. Name the pack in `meta-source.edn`:

```clojure
{:registry ["registry"] :src ["src"]
 :packs {:spec {}                                    ; every s/def call
         :malli {:registries [shop.schema/registry]}}} ; vars holding a malli registry map
```

Each `s/def` and each qualified registry entry becomes an entity keyed by its data key (an unqualified one, like `:map`, overrides a malli schema and is skipped),
in the pack's own vocabulary: `:spec/form`, and for `s/keys` the
`:spec/req`, `:spec/opt`, `:spec/req-un` and `:spec/opt-un` keys; `:malli/form`,
and for a `:map` its qualified `:malli/keys` and its `:malli/unqualified-keys`.
Forms are kept as text. Nothing is loaded; `.cljc` reader conditionals are
read as `:clj`.

A data key is one entity whatever describes it, so a registry file can add
facts the schema has no place for:

```clojure
;; registry/privacy.edn
{:user/email {:data/privacy :pii}}
```

`meta-source data :user/email` then gives its shape and where it is written,
`:data/privacy :pii`, the `s/keys` it belongs to, and who produces and
consumes it. The hover shows the same. Data keys are not listed as
dependencies.

`check` adds, all as info:

- `:schema-key-without-shape`: an `s/keys` names a key with no spec of its own.
  spec allows it, and it is also how a typo looks;
- `:unqualified-schema-keys`: a malli `:map` with unqualified keys, which no
  dataflow query can follow;
- `:data-key-without-schema`: keys in an entity's contract that no schema shapes;
- `:pack-form-not-read`: an `s/def` or registry the pack couldn't read (any pack reports what it couldn't read this way, with `:pack` and `:reason`).

Keys a schema shapes also count as data keys for `:keys-outside-contract`, so
code that reads a spec'd key its contract doesn't name is reported even when
no other contract names that key.

Not read yet: specs or schemas declared through a project's own wrapper fn or
macro, malli schemas held in vars rather than a registry, and function schemas
(`s/fdef`, `m/=>`). Every entry of a malli registry gets the line of its
`def`. On a 535-file app the pack read 1,770 `s/def` calls, none unreadable;
`test/fixtures/shop` is a small project using both libraries, checked
by `bb smoke`, which also covers the Pathom pack below.

## Resolvers and mutations: the Pathom pack

Pathom resolvers already say what they consume and produce. With
`:packs {:pathom {}}`, each `defresolver` and `defmutation` (Pathom 3,
`com.wsscode.pathom3.connect.operation`, and Pathom 2,
`com.wsscode.pathom.connect`) becomes an entity keyed by its op-name, `ns/name`
as a keyword:

```clojure
:shop.resolvers/order-total
#:pathom{:op :resolver
         :input [:order/lines]
         :output [:order/total]
         :inferred #{:input :output}}
```

- `:pathom/input`, `:pathom/params` (a mutation's) and `:pathom/output` come
  from `::pco/input`, `::pco/params` and `::pco/output`. Output joins
  contribute their key and the keys inside them.
- When they aren't written, they are inferred as Pathom 3 does: the input from
  the destructuring of the input argument, the output from a map literal
  returned last. `:pathom/inferred` says which.
- An option may name a `def` in the same file whose value is data
  (`{::pco/output concept-properties}`); it is read through it.
- An option written as code (`(->> registry keys vec)`) is not guessed:
  `:pathom/not-read` lists it, and `check` reports it.
- A project's own wrapper macro that takes `defresolver`'s or `defmutation`'s
  arguments is read when named: `:pathom {:resolver-macros [app.connect/defresolver]
  :mutation-macros [...]}`.

Resolvers are producers and consumers next to everything else.
`meta-source data :user/email` lists the resolvers that need it alongside the
functions whose contracts produce it, its spec and its privacy class. `check`
adds, as info:

- `:input-without-producer`: a resolver needs a key that nothing produces, no
  resolver and no other contract. The query can still pass it in (that is how
  a graph's entry points look); when nothing does, the resolver is
  unreachable. When some operation's output couldn't be read,
  `:unread-outputs` names those operations: any of them may produce the key;
- `:operation-option-not-read`: an input, params or output written as code;
- `:pack-form-not-read` with `:pack :pathom`: a form the pack couldn't read.

On two open-source Pathom 3 apps:

| | Operations read | Notes |
|---|---|---|
| [hermes](https://github.com/wardle/hermes) (SNOMED CT server) | 38 of 38, plus 227 `s/def` | one unproduced input: `:info.snomed/id`, the graph's entry point; one `s/keys` naming optional keys with no spec |
| [duck-repled](https://github.com/mauricioszabo/duck-repled) | 29 of 29 through its wrapper macro, plus 38 malli registry entries | the seed resolver's output is code, so unproduced inputs name it; two resolver outputs (`:var/spec`, `:repl/cljs`) are missing from the registry its wrapper validates against, so they are never validated |

Not read from the code: `pco/resolver` and `pco/mutation` calls (resolvers
built as values), dynamic resolvers, and options naming a def in another
namespace. kondo doesn't attribute the keywords inside a resolver's body to it,
so `:keys-outside-contract` doesn't cover resolvers. The running environment
covers the first three:

### What the running environment registered: the Pathom snapshot

A running Pathom environment holds the exact index of what it registered,
however each operation was built. Write it from the app's REPL, or wherever
the env is built:

```clojure
(meta-source.pack.pathom/snapshot! [env])   ; Pathom 3 env, or Pathom 2 indexes; several at once
;; => ".meta-source/packs/pathom.edn"
```

Pathom need not be on meta-source's classpath, nor meta-source on the app's
beyond this one call: the index is read by keyword. The file is
`{:pack :pathom :written <ms> :entities {op-id entry}}`, in the pack's
vocabulary; `:pathom {:snapshot "path"}` in `meta-source.edn` reads it from
elsewhere. With it, the index:

- **fills** what reading the code couldn't: an output written as code takes
  the registered one, listed under `:pathom/from-runtime`, so the inputs it
  produces are no longer reported as unproduced;
- **adds** what only the environment has, such as a `pco/resolver` value, as
  an entity the hover marks "running process";
- **checks**, beside the static checks:
  - `:runtime-disagrees` (warning): an operation whose inputs, params or
    outputs differ between the code and the environment, with the keys only
    each side has. `:changed-since-snapshot` is true when the file changed after
    the snapshot was written, so the snapshot may be the stale side;
  - `:not-in-runtime` (info): defined in the code, never registered;
  - `:runtime-only` (info): registered, in no file the pack read.

`meta-source summary` gives the snapshot's file, time and count under
`:pack-snapshots`. On the shop fixture, a snapshot from real Pathom 3 and 2
agrees with the code on all nine operations both have. `bb pathom-snapshot-check`
rebuilds it from real Pathom and fails if the operations change.

## A system's components: the integrant pack

```clojure
:integrant {:configs ["resources/config"]   ; config files, or directories of .edn
            :vars [app.system/config]       ; configs written in code
            :provided [:duct/logger]}       ; keys something else adds, like a duct module
```

Each config key becomes a component, in the pack's own vocabulary:

```clojure
:shop/db
#:integrant{:config-in ["resources/system.edn:2" "resources/system.test.edn:2"]
            :env ["DATABASE_URL"]           ; aero's #env, from any branch
            :varies true                    ; the files configure it differently
            :init-key true :halt-key! true} ; the defmethods the code has
```

- `:integrant/refs` comes from `#ig/ref` and `#ig/refset` anywhere in the
  config, `(ig/ref k)` in a config written in code. Refs are references, so
  `meta-source uses :shop/db` lists the components that need it, and an EDN
  contract naming a component in its deps links a function to it.
- Config files are read all at once: a key that a `.mock.edn` or test variant
  configures too is one component with every file listed, not a conflict.
  Tags no reader knows (`#env`, `#or`, `#profile`) are kept as data.
- A composite key is named by the keyword no other config key uses, the others
  being what it derives from (`:integrant/derives`): `:app/web` in duct's
  `[:duct/server :app/web]`, and `:password.slack/webhook` in
  `[:password.slack/webhook :app/rotable-password]` when every password shares
  the second. Projects write them both ways round. When the config doesn't single
  one out, the last keyword is the name.
- A duct profile key (`:duct.profile/base {...}`) holds components, each read
  as one with `:integrant/profiles`.
- `(derive child parent)` calls are read in files that use integrant
  (`:integrant/isa`): there they build component hierarchies, while elsewhere
  `derive` also builds hierarchies of exceptions and the like.
- `defmethod`s of integrant's multimethods (`init-key`, `halt-key!`,
  `pre-init-spec`…) are read from the form, so `::keywords` get the file's
  namespace. clj-kondo's own dispatch string gives them the wrong one.

`check` adds:

- `:ref-to-undefined` (warning): a ref to a key no config read defines,
  directly, as a composite's parent or by `derive`, and not `:provided`;
- `:init-key-without-config` (info): an `init-key` for a key nothing configures
  or derives from: unused, or configured somewhere not read.

On real systems:

| | Read | What was left |
|---|---|---|
| a production app, 18 config files | 409 components, defmethods and derives | 16 `init-key`s no config read configures; of the four checked, two are configured nowhere in the repo, one only in tests, one in code |
| [Swirrl/datahost-prototypes](https://github.com/Swirrl/datahost-prototypes), two services | 55 | none from integrant |
| [walkable-server/realworld-fulcro](https://github.com/walkable-server/realworld-fulcro) (duct and Pathom) | 27 components, 16 resolvers and mutations | `:duct/logger`, which duct's logging module adds, declared with `:provided` |

Those systems shaped the pack. Each of these was found on one of them:

- composite keys written name-first;
- `::keyword` dispatch values;
- duct profiles;
- `derive` used to share an `init-key`;
- a `:refer` of a pack's macro.

## One id, two packs

Data keys are global: a spec and a malli schema shaping `:user/email` are one
entity. A spec under a component's own key, as integrant systems often have, is
that component's shape: the entity stays a component, with `:spec/form` beside
its `:integrant/*` keys. Two packs that declare things giving one id (a Pathom
resolver and an integrant component, say) are two things under one name, and
`check` reports `:id-in-several-packs` (warning).

## Which keys carry data: `:dataflow`

An entry consumes or produces data keys through the keys `:dataflow` lists, in
`meta-source.edn`, in any vocabulary:

```clojure
{:dataflow {:consumes [:job/reads] :produces [:job/writes]}}
```

Each pack adds its own (the Pathom pack: `:pathom/input`, `:pathom/params`,
`:pathom/output`). With this, a nightly job written as
`{:job/nightly-digest {:job/reads [:user/email]}}` is a consumer of
`:user/email`. The shop fixture describes its plain functions this way
(`:fn/reads`, `:fn/returns`).

## Writing a pack

A pack is a map; the built-in ones are `meta-source.pack.schema/pack`,
`meta-source.pack.pathom/pack` and `meta-source.pack.integrant/pack`. A project enables its own by naming the var
under `:packs`, with its options as the value:

```clojure
{:packs {:spec {} app.source/job-pack {}}
 :deps {app/source-packs {:local/root "tools/source"}}}   ; when the pack isn't under :src
```

`:deps` puts the pack's code on meta-source's classpath, resolved as a
`deps.edn` would; a pack in the project's own `src` doesn't need it.

A pack needs `:pack/find` and `:pack/read` (forms in code), or
`:pack/data-files` and `:pack/read-data` (data files), or both. This one reads a project's
own `defjob` macro, and makes a job a consumer of the data keys it lists:

```clojure
(def job-pack
  {:pack/id :jobs
   :pack/kinds #{:job}                       ; the :kind of its sources; one pack per kind
   :pack/vocabulary #{"job"}                 ; the key namespaces its entries use
   :pack/dataflow {:consumes [:job/reads]}
   ;; where its forms are, from clj-kondo's analysis
   :pack/find (fn [analysis opts]
                (for [u (:var-usages analysis)
                      :when (and (= 'app.scheduler (:to u)) (= 'defjob (:name u)))]
                  (select-keys u [:filename :row :name-row :name-col])))
   ;; one file's forms, read as data; what it returns is cached, so it is data
   :pack/read (fn [sites {:keys [form-at own-ns]} opts]
                (vec (for [{:keys [row name-row name-col]} sites
                           :let [[_ nm job] (form-at name-row name-col)]]
                       {:row row :kind :job :dev-id (keyword own-ns (name nm)) :entry job})))
   :pack/describe (fn [entry] (some->> (:job/schedule entry) (str "job  ")))})
```

The optional keys:

| Key | What it gives |
|---|---|
| `:pack/config-keys` | for a built-in pack, the `:packs` keys that enable it |
| `:pack/data-keys?` | true when its entities are data keys, as a schema's are |
| `:pack/checks` | `(fn [view])` → problems; `view` has `:entities`, `:of-kinds`, `:data-key?`, `:loc` and `:flow` |
| `:pack/inline-data-keys` | `(fn [entities])` → data keys its entities define with no entity of their own |
| `:pack/shape`, `:pack/members` | a data key's shape as text, and the keys a schema is made of |
| `:pack/describe` | one line for the hover |
| `:pack/merge` | `{key :union}`: keys whose values it unions across sources, rather than reporting different values as a conflict |
| `:pack/data-files`, `:pack/read-data` | a pack that reads data files rather than code: `(fn [opts])` gives paths (files, or directories of `.edn`), and `(fn [files opts])` reads all of them at once, so a key several files define can be one result. `meta-source.read/keyed-lines` reads EDN with each key's line, keeping unknown tags |
| `:pack/runtime-fill`, `:pack/runtime-diff` | its runtime half: `(fn [entry runtime])`, filling what static reading couldn't read, and saying where code and the running process disagree. The process writes `{:pack id :written ms :entities {...}}` to `.meta-source/packs/<id>.edn` (or the pack's `:snapshot` option) |

`form-at` reads the form whose head symbol is at a row and column, with
`::keywords` resolved and `.cljc` reader conditionals read as `:clj`. A
result is `{:row :kind :dev-id :entry}`, or `{:row :kind :skipped reason}` when
the form can't be read; the index reports those as `:pack-form-not-read` and
counts each kind's reads in `meta-source summary`. A pack that throws on a
file is reported the same way, for that file. Two packs giving the same
`:kind` are refused; a `:refer` in an `ns` form is never handed to a pack. `bb smoke` runs a pack like this one.

