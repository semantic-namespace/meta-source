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
| `reach <id>` | what it depends on, transitively, through entries, calls and the component that builds it, with the path to each (`--via deps,calls,provided-by`) |
| `reached-by <id>` | what depends on it, transitively: the reverse of `reach`, with the path from each to it (`--via` too). Which routes break if `:app/stripe` changes |
| `data <key>` | a data key's shape, producers and consumers, and the schemas it is a key of |
| `check` | where code and declarations disagree, most severe first |
| `diagnostics <file>` | `check`'s findings in one file, with the line and columns to underline |
| `summary` | counts: entities by source, forms each pack read and couldn't |
| `hover <keyword>` | what an editor shows for a keyword |
| `serve` | long-lived, one JSON request and reply per line: what the editor talks to |

Output is EDN, or JSON with `--json`.

`check` exits 0 whatever it finds. In CI, `--fail-on error` makes it exit 1
when a problem is an error (`warning`: a warning or an error; `info`: any
problem):

![check --fail-on: the exit status CI reads](images/meta-source-fail-on.gif)

## What `check` finds

| Finding | Severity |
|---|---|
| two sources give one key different values (`:conflicting-values`), naming each place | error |
| two vars carry the same meta key value | error |
| an integrant ref no config defines | warning |
| a link to a reitit route name nothing defines; a route handler its namespace doesn't define | warning |
| an aero `#include` not found, a tag with no reader, a `#profile` reading nil, a `#ref` to nothing, a number reader over a non-number | warning |
| an environment variable read with no default that no deployment file sets; a `#profile` branch no profile reads | info |
| a Datascript pull joining or reading in reverse on a non-ref, a lookup ref on a non-unique attribute, an attribute whose spec disagrees | warning |
| a tilakone transition to no state, an initial state that is no state, a state defined twice in a machine | error |
| a tilakone state no transition from the initial state reaches | info |
| a re-frame dispatch or subscription to an id nothing registers | warning |
| one id declared by two packs (a resolver and a component sharing a keyword) | warning |
| code and the running process disagree about an operation | warning |
| a key code uses that isn't in its function's declared contract | info |
| a resolver input nothing produces; an `s/keys` key with no spec; unqualified malli keys | info |
| an `init-key` for a key no config has; a resolver defined but not registered | info |
| an effect nothing registers; with `:report-unused`, a re-frame event never dispatched, a subscription never subscribed to | info |
| a form a pack couldn't read, and why | info |
| one of your own rules, broken (`:rule-violated`), at the rule's severity; a rule not understood | yours; error |
| one of your own rules that can't be checked on an entity, because what it follows through code is off the pattern there (`:rule-unverifiable`) | warning |

Findings are reported, never guessed around: an option written as code is
reported as not read, rather than read wrongly. Your own invariants are
rules over the same index: [Your own rules](#your-own-rules).

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

A function with an `s/fdef` or a malli `m/=>` needs neither: the contract it
already has is read, keyed by its var
([docs/packs.md](packs.md#function-contracts-sfdef-and-m)).

A var with the meta key and no entry is valid: an entity with its binding
only, reported as info. `:id-key` in `meta-source.edn` chooses another meta
key; `:merge {key :union}` unions a set-valued key across files instead of
reporting a conflict.

## Your own rules

![A team writes its own rules as data, in Emacs](images/meta-source-rules.gif)

The packs check what each library means. A project's own invariants are
rules, written as data in EDN files that `:rules` in `meta-source.edn` names:

```clojure
;; meta-source.edn
{:rules ["rules"] ...}

;; rules/shop.edn: rule id → rule
{:rule/pii-readers-audited
 {:doc "Whatever reads a PII key is audited"
  :severity :warning                       ; :error, :warning (the default), :info
  :when {:= {:data/privacy :pii}}          ; the entities it is about
  :require [{:consumers {:all {:has :audit/logged}}}]}

 :rule/components-halt
 {:doc "A component that starts something stops it"
  :when {:kind :integrant :has :integrant/init-key}
  :require [{:has :integrant/halt-key!}]}}
```

A pack can give rules too, derived from what it read (`:pack/rules-of`, see
[packs](packs.md)): atlas's takes the ones a project's invariants carry as
data. They are checked with the project's own.

![A pack's rules, from atlas's invariants and the project's own, in Emacs](images/meta-source-pack-rules.gif)

`:when` selects entities; each `:require` condition must hold on every one of
them. A condition is a map, and every key in it must hold:

| Key | Holds when |
|---|---|
| `:kind :integrant` (or a set) | a source of that kind gives the entity: `:integrant`, `:spec`, `:pathom`, `:re-frame`, `:edn`... |
| `:has :k` (or a vector) | its merged entry has the keys |
| `:has-in [:k ...]` | its merged entry has a value at this path: `{:has-in [:reitit/data :auth/roles]}` |
| `:missing :k` (or a vector) | it has none of them |
| `:= {:k v}` | its `:k` is `v`, or contains it |
| `:in {:k #{v ...}}` | its `:k` is one of them |
| `:matches {:k "regex"}` | its `:k` is a string the regex finds (`{:reitit/path "^/api/admin"}`) |
| | for `:=`, `:in` and `:matches`, `:k` may be a path: `{:= {[:reitit/data :roles] :admin}}` |
| `:id-ns "shop.admin"` | its id's namespace is that one or under it |
| `:data-key true` | a schema shapes it |
| `:not cond`, `:or [cond ...]` | |
| `:id :k` (or a set) | it is one of these entities |
| `:consumers`, `:producers` | a data key's consumers or producers... |
| `:deps`, `:used-by` | ...what its entry names, or what names it... |
| `:calls`, `:called-by` | ...whose linked var its code calls, or whose code calls its... |
| `:provided-by`, `:provides` | ...the entity whose code calls the var it is defined in (a route's component: its `init-key` calls the fn building the route), or what it so builds... |
| `:handed` | ...what that component hands it: the components behind the config keys its `init-key` passes to the call, or all it holds when that isn't known... |
| | ...with `{:all cond}`, `{:some cond}` or `{:none cond}` |
| `:reaches {q cond}` | everything it depends on, transitively, through `:deps`, `:calls` and `:provided-by` (`:via #{:deps}` follows only those): `{:reaches {:none {:id :infra.db/xtdb}}}`. A violation shows the path to each offender |
| `:reached-by {q cond}` | everything that depends on it, transitively, `:via` as for `:reaches`: `{:reached-by {:none {:kind :reitit}}}` on the database. A violation shows the path from each offender |
| `:refs {:k q}` | what its `:k` names (the qualified keywords in its value), `q` as above: `{:refs {:fn/deps {:none {:has :kind/component}}}}`. `:deps` follows every key; this, one |
| `:referenced-by {:k q}` | the entities whose `:k` names it: `{:referenced-by {:vp/pattern {:some {:has :kind/vp}}}}` |
| `:resolves :k` (or a vector) | everything its `:k` names is an entity some source defines; what doesn't is named |

![Rules about one property, :resolves and :refs, in Emacs](images/meta-source-rule-refs.gif)

A violation is reported at the entity's definition and at each offender's.
When the offender comes through a key whose values are unioned across
sources (`:merge` in `meta-source.edn`, or a pack's), it is also reported
where that value was given: the other file, or the relation drawn, that
broke the rule. With `{:merge {:app/deps :union}}` and `wiring.edn` adding
`{:app/domain {:app/deps [:app/adapter]}}`:

```
warning registry/app.edn:1   …: :app/domain (:app/adapter), given at registry/wiring.edn:2  [:rule/layers]
```

The editor marks `wiring.edn:2` too. For `:reaches`, the place is where the
path's first step was given.

A rule over what an entity depends on, through code as well as entries:

```clojure
{:rule/public-routes-stay-off-the-db
 {:doc "No route under /public reaches the database"
  :when {:kind :reitit :matches {:reitit/path "^/public"}}
  :require [{:reaches {:none {:id :infra.db/xtdb}}}]}}
```

```
$ meta-source check --brief
warning src/app/home.clj:3   No route under /public reaches the database: :home/get (:app.routes/drivers → :core.services/user → :app.repos/user → :infra.db/xtdb)  [:rule/public-routes-stay-off-the-db]
```

The code links come from the packs, with nothing to annotate: an integrant
`init-key` method is its component's code, and a route defined inside a fn
(or written in the `init-key` itself) is provided by the component whose
`init-key` calls that fn. What the route reaches through it is exact when
the system follows the pattern ([pattern.md](pattern.md), I5 and R4): what
the route fn's call is handed, as the method's destructuring names it.
With `(defmethod ig/init-key ::routes [_ {:keys [user-service]}] (->auth-routes user-service))`,
the route reaches the component `:user-service` refers to, not everything
`::routes` holds.

Off the pattern, nothing is guessed. A route fn handed the whole config or a
value bound in the body, an `init-key` using its config outside the route fns'
calls (a middleware, an option), or a component that takes the routes and
holds others (which may wrap them): the route's reach isn't exact, `reach`
says so (`:not-exact`, with the rule broken and where), and a rule that
follows it is `:rule-unverifiable`, a warning, instead of passing:

```
warning src/app/home.clj:3  No route under /public reaches the database: :home/get can't be checked: off the pattern at :infra.router/core (:pattern/reitit-router-holds-routes)  [:rule/public-routes-stay-off-the-db]
```

Which parameters reach a call is clj-kondo's to say (its locals); meta-source
follows no value through code. A var with the meta key is linked to its
entity, and its calls count too. `meta-source reach <id>` lists what an
entity reaches, with the path to each.

Rules see every source at once: a fact from EDN (`:data/privacy`), a pack's
(`:integrant/init-key`), and the relations between them. A violation is a
`:rule-violated` finding at the entity, with the rule's `:doc`, its id and
where it is written (`:rule-at`), the clauses that failed, and for a relation
the entities that broke it. Those are marked in the editor too, where each is
defined. A rule meta-source can't understand (an unknown condition, a
missing `:when`) is an error at the rule, saying why, and so is anything
written after a rules file's map. Rules files are read again when they
change, as registry files are.

## Calls by id: `:calls-by-id`

Some systems call an implementation by its id instead of its var: a
workflow engine running `(exec :fn/load ...)`, an `(exec-fn :fn/load arg)`
that looks the entity up in a registry. `:calls-by-id` names those fns:

```clojure
{:calls-by-id [app.engine/exec app.registry/exec-fn] ...}
```

A call to one of them whose first argument is a literal keyword counts as a
call to that entity, as a direct call to its var does:

- `undeclared-call` reports it when the caller's entry doesn't name the
  entity (`:by-id true` on the problem);
- `uses` lists the place under "called from";
- an integrant key called that way (`(exec :app.step/fetch)` running
  `(ig/init-key :app.step/fetch deps)` itself) counts as used, so it isn't
  reported as an init-key without config.

## Libraries' registrations: `:libs`

A project can refer to entities a library registers: a prompt naming a
tool the library's own source registers. `:libs` names those libraries:

```clojure
{:libs [io.github.semantic-namespace/atlas] ...}
```

- **The coordinate is the project's:** meta-source reads it from the
  project's `deps.edn` (`:deps`, else an alias's `:extra-deps`). It resolves
  it as the clojure CLI would, so a Maven jar, a git dep or a `:local/root`
  all work. A lib `deps.edn` doesn't name is an error.
- **The sources are read like the project's:** they are copied under
  `.meta-source/libs/<lib>@<version>/`, once per version, and the packs read
  them. The library's entities, and its ontologies, join the index.
- **Its findings are its own:** a finding located in a library's sources, or
  about an entity only a library defines, is not reported.

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

