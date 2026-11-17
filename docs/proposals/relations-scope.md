# Who says what a key means

**Status:** proposal, for discussion. Nothing here is built.

`:relations` (#56) gives an entry key a verb. A key no source gives a verb is
*untyped*, and what it names is a dependency: adopting meta-source costs
nothing, and nothing goes missing. #58 let a relation be a dependency too
(`{:verb v :depends true}`). Two limits remain:

1. A verb applies to a key everywhere in the project. Atlas scopes its
   type-refs to an entity type (`:type-ref/source`).
2. Atlas draws no edge from a key nothing declares. meta-source draws a
   dependency. So reach in meta-source still follows keys that atlas's own
   blast radius doesn't.

The first sketch for both was **entity-scoped**: a pack says "for my entities,
an untyped key is a relation" (`:pack/untyped :relation`), and relations can
be scoped to entity kinds. This document measures what that would do, and
proposes something else.

## What the untyped edges are

Untyped dependency edges leaving atlas entities (meta-source 9a18a87, atlas
27a48a7; #58 and atlas#78 add verbs but leave these keys untyped), counted by
key:

| project | edges | keys | the keys |
|---|---|---|---|
| atlas (its own sources) | 27 | 6 | `:enforced-by` 10, `:ux/errors` 7, `:trigger` 3, `:ux/flow` 3, `:ux.flow/entry` 3, `:ux.flow/steps` 1 |
| a private app | 191 | 10 | `:tilakone/to` 67, `:tilakone/machines` 48, `:behavior-tree/children` 24, a project-namespaced id key 21, `:llm-prompt/verifies` 12, `:tilakone/on` 6, `:workflow-producer/output` 5, `:integrant/refs` 5, `:node/mitigates` 2, `:integrant/init-writes` 1 |

In the private app (an integrant system of about 2,600 entities), 23 entities are atlas's and another pack's at once (an atlas
entry describing an integrant component, a tilakone state).

The keys fall into three kinds, and none of them is "atlas's entities":

- **Another pack's keys**: `:tilakone/*` (121), `:integrant/*` (6). They are
  on atlas entities only because atlas describes what those libraries
  declare. `:integrant/refs` *are* dependencies. An entity-scoped "untyped
  is a relation" from atlas would turn them into relations and break reach
  through integrant. **The entity-scoped design is wrong for exactly the
  entities it was meant for.**
- **Keys in an ontology's namespace that no ontology lists**:
  `:llm-prompt/verifies` (12), `:workflow-producer/output` (5). Atlas draws no
  edge from them: they are what the second limit is about. Likewise
  `:behavior-tree/children` (24) and `:node/mitigates` (2), whose ontology
  (`atlas.ontology.behavior-tree`, which the app requires) is in no
  commit of atlas yet.
- **The project's own keys**: an id key in the app's own namespace (21), and in atlas's
  examples `:enforced-by`, `:ux/*`, `:trigger` (27). No library knows what
  they mean. Under meta-source's rule they *should* stay dependencies until
  the project says otherwise, in `meta-source.edn` or in a type-ref.

## Proposal: by key ownership

A key belongs to whoever owns its namespace. The owner says what it means.

**A. Packs declare their own keys** (meta-source-packs; no interface change).
Each pack in meta-source-packs gives `:pack/relations` for the keys it writes
into entries. For tilakone and integrant, checked against the private app
(see *Step A, simulated* below):

| pack | key | relation | why |
|---|---|---|---|
| tilakone | `:tilakone/states` | `{:verb :tilakone/states :depends true}` | a machine runs its states |
| tilakone | `:tilakone/machines` | `{:verb :tilakone/state-of}` | a state's pointer back to its machine; as a dependency every state reaches every other |
| tilakone | `:tilakone/to`, `:tilakone/on` | `{:verb :tilakone/to}`, `{:verb :tilakone/on}` | a transition isn't a dependency |
| tilakone | `:tilakone/transitions`, `:tilakone/initial` | relations by their own name | same |
| integrant | `:integrant/refs`, `:integrant/composite-refs` | `{:verb :depends}` | what they already are, said |
| integrant | `:integrant/derives` | `{:verb :integrant/derives :depends true}` | a derived key with no `init-key` of its own runs its parent's |
| integrant | `:integrant/init-writes` | `{:verb :integrant/init-writes}` | the keys an init writes |

Two of these need #58's `:depends true`: a key can be a relation *and* a
dependency. Declaring every tilakone key a plain relation cut machines off
from their states; declaring `derives` a plain relation left a component
whose 18 children derive it with no dependents at all.

re-frame, reitit, Pathom, aero, Datascript, Overarch and grain get the same
review. It makes rules like `{:related {:tilakone/to …}}` possible, and it's
the right place: the pack that writes a key is the one that knows it.

**B. A pack's untyped default, for its vocabulary only** (small interface
addition). `:pack/untyped :relation` applies to undeclared keys whose
namespace is in the pack's vocabulary, never to other packs' keys or the
project's. Atlas's pack:

- sets `:pack/untyped :relation`;
- derives its vocabulary from the ontologies it reads (the namespaces of
  their `:ontology/for` and `:ontology/keys`), its own and the project's.
  Today it reads atlas's own from a fixed list of files
  (`builtin-ontology-files`); an ontology atlas adds, or one a library ships,
  is missed until it's listed. Reading every `atlas/ontology/*` resource on
  the classpath would close that.

That covers `:llm-prompt/verifies` and `:workflow-producer/output` (17
edges), and `:behavior-tree/children` and `:node/mitigates` (26 more) once
their ontology is one the pack reads. It matches atlas's datalog: no
declaration, no edge. It also makes atlas's current listing of
every ontology key as a relation unnecessary.

`:pack/vocabulary` exists already, as a set of namespaces that describe a
pack's entries. A pack whose vocabulary is computed needs `:pack/vocabulary-of
(fn [entries])`, as `:pack/relations-of` is to `:pack/relations`.

**C. The project's own keys stay the project's.** Nothing changes:
the app's own id key is a dependency until its `meta-source.edn` (or an
ontology of its own) says it's a relation. What helps is seeing them:
`meta-source check` could report, as info, the untyped keys that name
entities from entities a pack describes. It would be a list to declare, not
an error.

**D. Type-scoped relations: deferred.** No conflict has shown up in atlas
(426 entities) or the private app (2,625): no key means two things for two entity
types. Atlas's `:type-ref/source` is there, but no property appears in two of
atlas's 47 type-refs for different types (8 name a property outside their
type's namespace, `:grain/*` on execution functions and `:shapeup/*` on scopes
and bets, and each still means one thing). Scoping would add a lookup per edge, a
precedence rule (scoped beats global) and a second conflict case, for no
measured gain. Revisit when a real conflict appears. If it does, scope with
the entry conditions rules already use (`{:when {:= {:atlas/type …}}}`),
restricted to entry-only clauses, so relations don't depend on relations.

## What changes, in numbers

The untyped dependency edges leaving atlas entities would become:

| project | now | after A | after A + B | left for the project (C) |
|---|---|---|---|---|
| the private app | 191 | 64 | 47 (21 with the behavior-tree ontology read) | its own id key 21 |
| atlas | 27 | 27 | 27 | the examples' keys 27 |

These are estimates from the table above. `:integrant/refs` stays a
dependency, now declared. Each step would be measured with the before/after
of #56 (findings unchanged, reach compared entity by entity).

### Step A, simulated

On the private app, with meta-source 8032469 and atlas 61d01cd, the
tilakone and integrant declarations of step A given as the project's
`:relations` (the same declarations a pack would give):

| | before #56 | main | step A |
|---|---|---|---|
| findings | 218 | 218 | 218, identical |
| reach pairs (entity → entity it reaches) | 31,305 | 27,847 | 5,783 |
| untyped edges, all entities | 1,981 | 1,323 | 101 |

No entity reaches anything it didn't on main. The most depended-on component
keeps 363 dependents (412 on main); an entry point goes from 225 reached
entities to 113. What's left untyped: `:behavior-tree/children` 24,
`:spec/refs` 24, `:spec/req` 12, `:spec/req-un` 1 (the spec pack's own keys,
for A too), the app's own id key 21, and `:llm-prompt/verifies` 12,
`:workflow-producer/output` 5, `:node/mitigates` 2 (B).

## Open questions

1. **`:pack/untyped` only, or `:dependency`/`:relation`?** Only `:relation`
   seems useful; `:dependency` is the default already.
2. **Should C be a finding?** An info per key, not per edge, keeps it short.
   It could also be a `summary` line instead, to keep `check` quiet.
3. **Atlas's computed vocabulary vs `:pack/vocabulary` uses today.**
   Vocabulary already decides what describe shows as a pack's own props.
   Widening it to every ontology namespace changes that display too; check
   it on the private app before merging B.
4. **Order:** A first (packs repo, no core change, biggest gain), then B
   (core + atlas), with C optional. D only on evidence.
