# The precision corpus

How often is a check right? This directory measures it. meta-source runs its
checks over real apps pinned at fixed commits. A person judges each finding.
The report gives, per check, how many of its findings were real.

```sh
bb corpus fetch        # clone the apps at their commits, under .corpus/apps (gitignored)
bb corpus run          # check them all, into .corpus/findings.edn (or: bb corpus run athens tubo)
bb corpus report       # per check: findings, labels, precision; per app: findings per 1k lines
bb corpus unlabeled 20 # findings no label judges yet, with their code and an entry to fill in
bb corpus plant        # recall: plant known defects, see whether their check finds them
bb corpus ci           # run + report, exit 1 while a finding has no label
```

## Files

- **`corpus.edn`**: the apps.
  - `:git` + `:sha` (never a branch), or `:path` inside this repository.
  - `:root` inside the checkout, and the `:config` meta-source reads it with.
  - `:not-judged` lists problems that say what wasn't read rather than what's
    wrong (`:pack-form-not-read`). They are counted but never in precision.
- **`labels.edn`**: one entry per judged finding.
- **`plants.edn`**: known defects for recall.

## A finding's key

`[app problem dev-id file what-else-it-names]`, with every line and column
left out. A label survives edits that move code. It stops matching when the
finding changes: another key, another target. `report` then counts it as
stale.

## Labels

```clojure
{:finding [:athens :event-not-registered :alert/unset "src/cljs/athens/views.cljs" {}]
 :label :real          ; :real | :false | :useless | :unsure
 :confirmed false      ; true once someone other than the checks' author agrees
 :by "claude, first pass"
 :why "dispatched in views.cljs, registered nowhere in the repository"
 :evidence "https://..."} ; optional: an issue, a fix, a repro
```

- **`:real`**: a defect, or drift, the app's maintainer would want to know about.
- **`:false`**: meta-source is wrong, whether it misread the code or a heuristic
  doesn't hold.
- **`:useless`**: true, but nobody would act on it.
- **`:unsure`**: can't tell without the maintainers.

Precision is real / (real + false), with its 95% Wilson interval. Useless
and unsure findings are counted, but left out of it. The report gives it
twice: over all labels, and over confirmed ones only.

Ground truth, strongest first:
1. **The maintainers.** An issue or PR upstream; a merged fix is the best
   label.
2. **The app's history.** A later commit fixed it, and the finding disappears
   when the corpus pins that commit.
3. **A repro.** For checks that predict a runtime failure, three lines that
   fail.
4. **Reading the code.**

Decide what counts as real for a check before reading its findings, or the
labels drift toward whatever the tool says. The checks' author labels with
`:confirmed false`: someone else confirms.

## Recall

A check that never fires has no false findings. `plants.edn` makes one edit
in a copy of an app, a mistake a developer could make, and expects a problem
with given keys to appear. `bb corpus plant` gives, per check, how many it
found. The plants so far are typos and removals: easy cases. They are a floor
for recall, not a measure of it.

## First results (2026-10-22, unconfirmed)

The first pass covers 17 apps and 270 findings. 50 of them are not judged
(what wasn't read), and 174 are labeled. The labels are by the checks'
author, so none is confirmed yet.

| check | severity | judged | precision | what the false ones have in common |
|---|---|---|---|---|
| `event-not-registered` | warning | 9 | 8/9 (56–98%) | a library's event (re-pressed): `:provided` would say so |
| `event-never-dispatched` | info | 88 | 13/88 (9–24%) | dispatched from an fx map, through a wrapper fn, as a local binding, or as data in an action map |
| `sub-never-subscribed` | info | 33 | 4/33 (5–27%) | subscribed as data in an action map (`:available [::subs/x]`) |
| `route-not-defined` | info | 26 | 0/26 (0–13%) | routes a fn builds, which the pack says it didn't read |
| `input-without-producer` | info | 8 | 0/8 (0–32%) | produced by a resolver whose output is computed |
| `keys-outside-contract` | info | 3 | 0/3 | schema names read as keys |
| `ref-to-undefined` | warning | 1 | 0/1 | keys a duct module provides |

The real findings include:
- five events repath-studio's `user.cljs` dispatches under names that don't
  exist;
- three in athens: `:dispatch` wrapped as an event inside `:dispatch-n`,
  `:alert/unset`, and `:fail-resolve-forward-transact`.

Not yet judged: `data-key-without-schema` (42) and a few single findings.

Planted defects: 8 of 8 found. Each was a typo or a removal, with one check
per defect.

## Second run: checks quiet where the pack didn't read (2026-10-22)

These changes follow from the first results:
- **`route-not-defined`:** a name a form gives literally (`:name :about-page`
  in an `:expand` fn) counts as defined, even when the pack can't read the
  tree around it.
- **`input-without-producer`:** not reported while some output couldn't be read.
- **The never-used checks:** opt-in (`:report-unused`), and events aren't
  reported while some handler couldn't be read. The corpus turns them on:
  it measures every check, not only the default ones.
- **Library ids:** re-pressed's events, `:http-xhrio` and the keys duct's
  logging and sql modules add need no `:provided`.

| check | first run | second run |
|---|---|---|
| `event-not-registered` | 8/9 | 8/8 |
| `event-never-dispatched` | 13/88 | no findings: athens and repath-studio both have handlers the pack can't read |
| `route-not-defined` | 0/26 | no findings: tubo's names are read from its `:expand` fn |
| `input-without-producer` | 0/8 | 3 findings, 2 useless, 1 unjudged |
| `ref-to-undefined` | 0/1 | no findings |
| `sub-never-subscribed` (opt-in) | 4/33 | 4/33: subscriptions held as data still aren't seen |

What it cost: the 13 real never-dispatched events found in the first run
are no longer reported. athens and repath-studio both have handlers the pack
can't read. Planted defects: 8 of 8 found. The route typo planted in tubo is
reported as info, since part of tubo's tree is still built in code.

That is the trade made: the checks are right when they speak, and say less.

125 labels judge findings this run no longer produces. They are kept: they
are the record of what the first run reported, and they apply again if a
check comes back.
