"""Records the README's demos: a scripted session in a tmux pane, written as
an asciicast from tmux's screen captures (colours and cursor kept), for agg
to render as a GIF. See .claude/skills/meta-source-demo-gif/SKILL.md.

    python3 dev/demo/record.py overview|adoption WORK-DIR OUT.cast

WORK-DIR is created and filled: bin/ (where bbin installs meta-source, from
GitHub), el/ (meta-source.el on its own, no checkout), tools/emacs, and the
demo app. Nothing is installed on your PATH. Environment:

    EMACS               Emacs 30 or later (end-of-line Flymake messages);
                        default: the first of emacs, /snap/bin/emacs that is
    MS_DEMO_CLOJURE_MODE  a directory holding clojure-mode.el (required)
"""
import json, os, re, shutil, subprocess, sys, time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEMO = os.path.join(REPO, "dev", "demo")


# =============================================================================
# The recording: frames are tmux captures, kept when the screen changed
# =============================================================================

class Session:
    def __init__(self, name, width, height, cwd, command):
        self.name, self.width, self.height = name, width, height
        self.events, self.last, self.start = [], None, None
        subprocess.run(["tmux", "kill-session", "-t", name], capture_output=True)
        subprocess.run(["tmux", "new-session", "-d", "-s", name, "-x", str(width), "-y", str(height),
                        "-c", cwd, command], check=True)
        subprocess.run(["tmux", "set", "-t", name, "status", "off"], check=True)
        self.start = time.time()

    def tmux(self, *args):
        return subprocess.run(["tmux", *args], capture_output=True, text=True).stdout

    def screen(self):
        lines = self.tmux("capture-pane", "-p", "-e", "-t", self.name).split("\n")[:self.height]
        x, y, shown = self.tmux("display", "-p", "-t", self.name, "#{cursor_x} #{cursor_y} #{cursor_flag}").split()
        body = "\r\n".join("\x1b[0m" + l + "\x1b[0m\x1b[K" for l in lines)
        return ("\x1b[H\x1b[2J" + body + "\x1b[%d;%dH" % (int(y) + 1, int(x) + 1)
                + ("\x1b[?25h" if shown == "1" else "\x1b[?25l"))

    def plain(self):
        # Emacs continues a long line with a backslash at the edge: join them
        return self.tmux("capture-pane", "-p", "-t", self.name).replace("\\\n", "")

    def snap(self):
        s = self.screen()
        if s != self.last:
            self.events.append([round(time.time() - self.start, 3), "o", s])
            self.last = s

    def pause(self, sec):
        end = time.time() + sec
        while time.time() < end:
            self.snap(); time.sleep(0.1)

    def wait_for(self, pattern, timeout=60):
        end = time.time() + timeout
        while time.time() < end:
            self.snap()
            if re.search(pattern, self.plain()):
                return
            time.sleep(0.1)
        raise SystemExit("timed out waiting for %r\n%s" % (pattern, self.plain()))

    def wait_prompt(self, prompt, timeout=60):
        end = time.time() + timeout
        time.sleep(0.2)
        while time.time() < end:
            self.snap()
            lines = [l.rstrip() for l in self.plain().split("\n") if l.strip()]
            if lines and lines[-1] == prompt:
                return
            time.sleep(0.1)
        raise SystemExit("timed out waiting for the prompt\n" + self.plain())

    def type(self, text, delay=0.06):
        """Types text; a newline is RET (C-j would indent in Emacs)."""
        for i, line in enumerate(text.split("\n")):
            if i:
                self.key("Enter")
            for ch in line:
                self.tmux("send-keys", "-t", self.name, "-l", ch)
                time.sleep(delay); self.snap()

    def key(self, *keys):
        self.tmux("send-keys", "-t", self.name, *keys); time.sleep(0.25); self.snap()

    def write(self, out):
        self.tmux("kill-session", "-t", self.name)
        with open(out, "w") as f:
            f.write(json.dumps({"version": 2, "width": self.width, "height": self.height}) + "\n")
            for e in self.events:
                f.write(json.dumps(e) + "\n")
        print(len(self.events), "frames,", round(self.events[-1][0], 1), "s ->", out)


# =============================================================================
# The work directory
# =============================================================================

def emacs_program():
    for e in [os.environ.get("EMACS"), "emacs", "/snap/bin/emacs"]:
        if not e:
            continue
        try:
            v = subprocess.run([e, "--version"], capture_output=True, text=True).stdout
        except OSError:
            continue
        m = re.search(r"GNU Emacs (\d+)", v)
        if m and int(m.group(1)) >= 30:
            return e
    raise SystemExit("needs Emacs 30 or later (set EMACS)")


def prepare(work, scenario, install):
    clojure_mode = os.environ.get("MS_DEMO_CLOJURE_MODE")
    if not (clojure_mode and os.path.exists(os.path.join(clojure_mode, "clojure-mode.el"))):
        raise SystemExit("set MS_DEMO_CLOJURE_MODE to a directory holding clojure-mode.el")
    shutil.rmtree(work, ignore_errors=True)
    for d in ["bin", "el", "tools"]:
        os.makedirs(os.path.join(work, d))
    shutil.copy(os.path.join(REPO, "emacs", "meta-source.el"), os.path.join(work, "el"))
    emacs = os.path.join(work, "tools", "emacs")
    with open(emacs, "w") as f:
        f.write("#!/bin/sh\nexec %s -nw -Q -l %s \"$@\"\n"
                % (emacs_program(), os.path.join(DEMO, "init-%s.el" % scenario)))
    os.chmod(emacs, 0o755)
    env = dict(os.environ, BABASHKA_BBIN_BIN_DIR=os.path.join(work, "bin"))
    if install:
        subprocess.run(["bbin", "install", "io.github.semantic-namespace/meta-source"], env=env, check=True,
                       capture_output=True)
    app = os.path.join(work, "shop")
    shutil.copytree(os.path.join(DEMO, "%s-app" % scenario), app)
    return app, clojure_mode


def shell_env(work, clojure_mode, extra=""):
    return ("env -i HOME=%s TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8 LC_ALL=C.UTF-8 "
            "PATH=%s/bin:%s/tools:/usr/local/bin:/usr/bin:/bin:%s/.local/bin "
            "BABASHKA_BBIN_BIN_DIR=%s/bin MS_DEMO_EL=%s/el MS_DEMO_CLOJURE_MODE=%s %s"
            % (os.environ["HOME"], work, work, os.environ["HOME"], work, work, clojure_mode, extra))


# =============================================================================
# Scenarios
# =============================================================================

def overview(work, out):
    """Install from GitHub, the CLI, then Flymake and hover in Emacs."""
    app, clojure_mode = prepare(work, "overview", install=False)
    prompt = "shop-demo $"
    s = Session("ms-overview", 164, 30, work,
                shell_env(work, clojure_mode, "PS1='\\[\\e[1;36m\\]shop-demo\\[\\e[0m\\] \\[\\e[1;32m\\]$\\[\\e[0m\\] ' "
                          "bash --norc --noprofile"))
    def run(cmd, after=1.6, timeout=60):
        s.type(cmd, 0.045); s.pause(0.4); s.key("Enter"); s.wait_prompt(prompt, timeout); s.pause(after)
    time.sleep(0.8); s.key("C-l"); s.pause(0.8)
    run("# meta-source: what a Clojure app declares, read where it is declared", 0.8)
    run("bbin install io.github.semantic-namespace/meta-source | grep complete", 1.4, 240)
    run("cd shop && cat meta-source.edn", 2.8)
    run("meta-source summary | grep -A3 :packs", 2.8)
    run("meta-source uses :shop/db", 3.0)
    s.key("C-l"); s.pause(0.5)
    s.type("emacs resources/system.edn", 0.045); s.pause(0.4); s.key("Enter")
    s.wait_for(r"no config defines"); s.pause(3.0)
    s.key("C-s"); s.type(":shop/db", 0.06); s.key("Enter"); s.key("Left"); s.key("Left")
    s.wait_for(r"integrant", 20); s.pause(3.2)
    s.key("C-x"); s.key("C-f"); s.pause(0.5); s.type("../src/shop/handlers.clj", 0.06); s.pause(0.4); s.key("Enter")
    s.wait_for(r"not in :fn/place-order", 30); s.pause(1.0)
    s.key("C-s"); s.type(":order/discount", 0.06); s.key("Enter"); s.key("Left")
    s.wait_for(r"data key", 20); s.pause(4.0)
    s.key("C-x"); s.key("C-c"); s.wait_prompt(prompt, 10); s.pause(1.5)
    s.write(out)


def adoption(work, out):
    """An existing app adopts meta-source in Emacs, one pack at a time."""
    app, clojure_mode = prepare(work, "adoption", install=True)
    git = ["git", "-c", "user.name=shop", "-c", "user.email=shop@example.com"]
    subprocess.run(["git", "init", "-q"], cwd=app, check=True)
    subprocess.run(["git", "add", "-A"], cwd=app, check=True)
    subprocess.run(git + ["commit", "-q", "-m", "the shop"], cwd=app, check=True)
    s = Session("ms-adoption", 160, 46, app, shell_env(work, clojure_mode, "%s/tools/emacs" % work))

    def find_file(path):
        s.key("C-x"); s.key("C-f"); s.pause(0.6); s.type(path, 0.05); s.pause(0.5); s.key("Enter"); s.pause(1.0)
    def save():
        s.pause(0.8); s.key("C-x"); s.key("C-s")
    def caption(after):
        s.key("F5"); s.pause(after)
    def replace_buffer(text):
        s.key("C-x"); s.key("h"); s.pause(0.6); s.type(text)

    integrant = '{:packs {:integrant {:configs ["resources"]}'
    s.wait_for(r"resolvers\.clj", 30); s.pause(1.5)
    caption(6)                                        # the app as it is

    caption(2.5)                                      # step 1
    find_file("../meta-source.edn")
    s.type(integrant + "}}")
    save()
    s.wait_for(r"no config defines :shop/metrics"); s.pause(2.5)
    caption(7)

    caption(2.5)                                      # step 2
    replace_buffer(integrant + "\n         :pathom {}}}")
    save()
    s.wait_for(r"nothing produces :user/loyalty-tier"); s.pause(2.5)
    caption(7)

    caption(2.5)                                      # step 3
    replace_buffer(integrant + "\n         :pathom {}\n         :spec {}}}")
    save(); s.pause(2.0)
    s.key("C-x"); s.key("o")                          # system.edn, below
    find_file("../registry/privacy.edn")
    s.type("{:user/email {:data/privacy :pii}}")
    save()
    s.wait_for(r"create\?", 10); s.pause(0.8); s.key("y"); s.pause(2.0)
    s.key("C-x"); s.key("o")                          # resolvers.clj, below
    s.key("C-s"); s.type(":user/email", 0.08); s.key("Enter"); s.key("Left"); s.key("Left"); s.pause(1.5)
    s.key("C-c"); s.key(".")
    s.wait_for(r":data/privacy\s+:pii"); s.pause(3)
    caption(9)

    caption(2.5)                                      # step 4
    find_file("src/shop/handlers.clj")                # from the describe buffer, at the root
    s.key("C-s"); s.type("(defn place-order", 0.08); s.key("Enter"); s.key("C-a")
    for _ in range(len("(defn ")):
        s.key("Right")
    s.pause(1.0)
    s.type("^{:meta-source/id :fn/place-order} ")
    save(); s.pause(2.0)
    s.key("C-x"); s.key("o")                          # meta-source.edn, at the top
    find_file("registry/functions.edn")
    s.type("{:fn/place-order {:fn/reads [:user/id :order/lines]\n                  :fn/returns [:order/id :order/total]}}")
    save(); s.pause(2.0)
    s.key("C-x"); s.key("b"); s.pause(0.5); s.type("meta-source.edn", 0.05); s.key("Enter"); s.pause(1.0)
    replace_buffer(integrant + "\n         :pathom {}\n         :spec {}}\n :dataflow {:consumes [:fn/reads] :produces [:fn/returns]}}")
    save()
    s.wait_for(r"not in :fn/place-order's contract"); s.pause(2.5)
    caption(8)

    caption(2.0)                                      # the cost
    s.key("M-&"); s.pause(0.6); s.type("git status --short", 0.07); s.pause(0.5); s.key("Enter")
    s.wait_for(r"\?\? registry/"); s.pause(4)
    caption(10)
    s.write(out)


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[1] not in ("overview", "adoption"):
        raise SystemExit(__doc__)
    {"overview": overview, "adoption": adoption}[sys.argv[1]](os.path.abspath(sys.argv[2]), os.path.abspath(sys.argv[3]))
