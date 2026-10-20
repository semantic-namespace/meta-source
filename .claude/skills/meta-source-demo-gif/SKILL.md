---
name: meta-source-demo-gif
description: Record, check and publish the README's GIF demos of meta-source (the CLI, and Emacs with Flymake and hover). Use when a demo GIF must be made or remade, a new scenario recorded, or a feature shown in the README changed.
---

# meta-source demo GIFs

The README shows two recordings, made with `dev/demo/record.py`:

| GIF | Scenario | Shows |
|---|---|---|
| `docs/images/meta-source-demo.gif` | `overview` | bbin install from GitHub, the CLI, then Flymake and hover in Emacs |
| `docs/images/meta-source-adoption.gif` | `adoption` | an existing app adopting meta-source in Emacs, one pack at a time, every edit typed live |

A recording is a scripted session in a tmux pane. Each screen change is a
`tmux capture-pane -e` frame (colours and cursor kept), written as an
asciicast, then rendered by [agg](https://github.com/asciinema/agg). No
screen recorder or X server is needed.

## Rules

- **Show only what is real.** The demo installs meta-source from GitHub, so
  push the features it shows first. Never fake or edit output. If a step is
  prepared off screen, the README caption says so.
- **Show findings where they land**, in the file in Emacs, rather than as
  CLI output to read. A shell-only version was hard to follow.
- **Take the time it needs.** Pause 5–9 s on each outcome. Length is fine:
  the adoption GIF is about 2.5 minutes at real speed.
- **Captions name the step, then the outcome** (F5 advances them in the
  adoption scenario). They must match what the screen shows. A caption that
  promises something not on screen is a bug.
- **Nothing lands on the user's PATH.** `record.py` installs into
  `WORK/bin` with `BABASHKA_BBIN_BIN_DIR`.

## Needs

- tmux, bb, bbin, git, Python 3 with Pillow (for `cuts.py`).
- Emacs 30 or later (end-of-line Flymake messages), as `$EMACS` or
  `/snap/bin/emacs`. The `/usr/bin/emacs` on some machines is too old.
- clojure-mode: `MS_DEMO_CLOJURE_MODE=<dir with clojure-mode.el>`.
- agg: use the **musl** build
  (`agg-x86_64-unknown-linux-musl` from the asciinema/agg releases). The gnu
  build needs a newer glibc than Ubuntu 20.04 has. Keep it in a scratch
  directory.
- A monospace font agg finds: DejaVu Sans Mono.

## Flow

1. **Storyboard.** Each step gets one action and one visible outcome. Run
   the steps by hand first and check every outcome is on screen.
2. **Push** what the demo shows, so `bbin install` gets it.
3. **Record**, into a scratch work directory:
   ```sh
   MS_DEMO_CLOJURE_MODE=~/.emacs.d/straight/build/clojure-mode \
     python3 dev/demo/record.py adoption /tmp/x/work /tmp/x/adoption.cast
   ```
   A step that times out prints the screen. Read it before changing anything.
4. **Check** each outcome as a still:
   ```sh
   python3 dev/demo/cuts.py /tmp/x/adoption.cast /tmp/x/agg /tmp/x/cuts \
     "found without starting the system" "nothing migrated"
   ```
   Look at every PNG: wrapping, stray text, captions, mode lines.
5. **Render**:
   ```sh
   agg --font-family "DejaVu Sans Mono" --font-size 14 --theme monokai \
       --idle-time-limit 3 --last-frame-duration 8 --fps-cap 15 \
       /tmp/x/adoption.cast docs/images/meta-source-adoption.gif
   ```
   The overview used `--idle-time-limit 2.5 --last-frame-duration 4`.
6. **Publish.** Commit the GIF with the README text that describes it, push,
   and check the image URL serves (`curl -sI .../raw/main/docs/images/...`).

## What went wrong before (and the fix)

| Symptom | Cause | Fix |
|---|---|---|
| agg: `GLIBC_2.32 not found` | gnu build | the musl build |
| GIF frames look faint, old frames bleed through | old Pillow composites agg's partial frames when seeking | judge a recording with `cuts.py`, never by seeking the GIF |
| `·` shows as `?` | `env -i` drops the locale | `LANG=C.UTF-8 LC_ALL=C.UTF-8` (record.py sets it) |
| a `*Warnings*` window pushes everything down | Emacs 30+ wants a lexical-binding cookie | the cookie on line 1 of every init |
| the wait for a message never matches | Emacs wrapped the line: `text\` then the rest | widen the frame (156–164 columns); `plain()` joins edge backslashes |
| messages wrap in side-by-side windows | the window edge, not the screen edge | stack windows full width |
| a typed line comes out indented | `C-j` indents in clojure-mode | newlines as `RET` (`type()` does this) |
| an edit lands mid-word (`place-^{...} order`) | `M-b` stops at `-` | move explicitly: `C-a`, then `Right` n times |
| `git status` output vanishes | `M-!` shows short output in the echo area | `M-&`, shown in the window it's run from |
| findings never appear after editing `meta-source.edn` | (fixed) the server read its config once; buffers opened before the config had no mode | keep the demo on a meta-source with those fixes |
| a buffer shows `Flymake:Wait` | its check is queued, or Flymake defers hidden buffers | fine if it clears; otherwise read the server's stderr |
| f-string `SyntaxError` on EDN | braces in f-strings | build EDN strings by concatenation |
| `$` in heredocs eats backslashes | unquoted heredoc | `<<'EOF'`, and pass paths as arguments |

## Adding a scenario

Add a function to `record.py`, an app under `dev/demo/<scenario>-app/`, and,
for Emacs, a `dev/demo/init-<scenario>.el`. Reuse `Session`: `type`, `key`,
`wait_for` (screen text), `wait_prompt` (shell), `pause`. Wait for what the
user would see, never for a fixed time.
