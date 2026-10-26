# An LLM keeping a registry while it explores

Can an LLM that explores a project keep meta-source registries as it goes,
and does that help the next session? One run, on one corpus app. A hint,
not a benchmark.

## The run

App: [clojure-saas-boilerplate](https://github.com/ovistoica/clojure-saas-boilerplate)
(integrant, reitit, re-frame; ~5k lines), copied without its git history.

1. **Explore and record.** An agent explored the app at `95d8ded`
   (2023-05-30) to understand its architecture, writing `:note/*` facts into
   `registry/*.edn` and its own rules into `rules/`, with meta-source's
   commands at hand. It touched no code.
2. **Drift.** The code moved to `6ee43e2` (the corpus commit, 15 files
   changed, among them the system config and the routes). `check` was run
   on the old registry, then the agent updated its registry.
3. **Use.** Two fresh agents, neither having seen the exploration, answered
   13 questions on `6ee43e2` ([questions.md](saas-boilerplate/questions.md),
   with the answer key, written and verified by hand before any agent ran):
   **A** with file reads and grep only, **B** with meta-source and the
   registry too.

What the explorer left is in [saas-boilerplate/](saas-boilerplate).

## Results

| | Correct | Tool calls | Tokens | Time |
|---|---|---|---|---|
| A: grep and reads | 13/13 | 9 | 70k | 65 s |
| B: meta-source + registry | 13/13 | 8 | 86k | 56 s |
| Explorer: first pass | | 32 | 105k | 4.8 min |
| Explorer: update after drift | | 46 in all | 138k | 8.3 min in all |

**On an app this size, the registry didn't help answer questions.** Both
were right on everything; B wasn't cheaper. ~5k lines fit in a handful of
greps. Both went past the key in places (A: `saas.templates` is required by
no namespace, so its dispatches are dead too; B: `:telegram/threads` returns
a lazy `for`, so its workers likely never start).

**The explorer's notes were accurate and worth having.** Every claim
sampled held, and they carry what no tool derives: the migrator's
`db-initiated?` bug at `95d8ded` (init script skipped on an empty database,
re-run on every boot after), routes with no authentication anywhere, the
calorie bot reachable only from a `(comment ...)` block, the webhook handler
putting nothing on the channel. Its rules were each tested by negating them.

**Drift: `check` caught one stale note in five.** At `6ee43e2`, against the
old registry:

| What went stale | Flagged? |
|---|---|
| `:saas/telegram` became a map with no init-key (the agent's rule "every config key has an init-key") | yes |
| the `:saas/telegram` note still describes the bot client and "no webhook route" | no |
| `:saas/secrets` still lists `:saas/telegram` as a consumer | no |
| `:saas/db` doesn't list `:telegram/threads` | no |
| `:saas/handler` says telegram is unused by any route | no |
| the new route and components have no note at all | no |

What a rule encodes is re-checked; prose and `:note/depends-on` sets rot
silently, since `:resolves` only checks that the ids exist. Updating, the
agent found every change, but by rereading code: "no rule covered this; I
would have left a stale bug note if I had only relied on check".

## What meta-source got wrong

1. **Notes become graph edges.** Every qualified keyword in an entry is a
   reference, so `:note/used-by #{routes}` on `:saas/db` reads as `:saas/db`
   depending on the routes. `reach :server/jetty` went from the 9 right
   components to 17. B noticed and worked around it; a less careful reader
   would have answered wrong from it. **This is the one to fix first**: an
   LLM's notes must not corrupt the graph the next LLM relies on.
2. **Route paths miss the router's prefix** when routes come from a fn
   composed under it: `/account/log-in` for `/v1/account/log-in`. The
   explorer kept its own `:note/real-path`.
3. **martian interceptors read as routes**: `{:name ::add-headers ...}` in a
   def gives a reitit entity.
4. **`derive`s of exception keywords** are integrant entities (they only
   refer now, but still show).
5. **No command lists entities**; the explorer made a rule that always
   fails to enumerate them. Rule violations come out as warnings among the
   app's own, which hid them at first.
6. **Not read**: aero `#ref` edges (secrets), a route's `:parameters`
   shape, app-db keys.

## What would make it worth it

- **Notes that can't lie about edges**: a way to declare which note keys
  are back-references or plain annotations (as `:pack/not-references` does
  for packs), so `:note/used-by` doesn't invert `reach`.
- **Fingerprints for drift**: record, with each note, a hash of the
  entity's code facts when it was written; when they change, the note is
  "to review". That turns the 1-in-5 into all of them, prose included.
- **Coverage**: an entity no note covers, once a registry exists, as info.
  The new route and components would have shown.
- **A larger app.** The question is whether the registry pays off where
  grep stops being enough: tens of thousands of lines, several libraries'
  registries crossing. Mercurius is the next corpus app to try.
