;;; init-adoption.el --- the adoption demo's Emacs -*- lexical-binding: t -*-
;; Loaded by dev/demo/record.py: meta-source.el from $MS_DEMO_EL (a copy, no
;; checkout), clojure-mode from $MS_DEMO_CLOJURE_MODE.
(setq inhibit-startup-screen t make-backup-files nil auto-save-default nil create-lockfiles nil
      confirm-nonexistent-file-or-buffer nil)
(menu-bar-mode -1)
(add-to-list 'load-path (getenv "MS_DEMO_CLOJURE_MODE"))
(add-to-list 'load-path (getenv "MS_DEMO_EL"))   ; meta-source.el on its own, no checkout
(require 'clojure-mode)
(add-to-list 'auto-mode-alist '("\\.edn\\'" . clojure-mode))
(load-theme 'modus-vivendi t)
(setq flymake-show-diagnostics-at-end-of-line 'short
      eldoc-idle-delay 0.2
      flymake-no-changes-timeout nil)
(electric-indent-mode -1)
(delete-selection-mode 1)
;; the describe buffer (C-c .) opens in the window asked from
(add-to-list 'display-buffer-alist '("\\*meta-source\\*" display-buffer-same-window))
;; and git status (M-&) too
(add-to-list 'display-buffer-alist '("\\*Async Shell Command\\*" display-buffer-same-window))
(require 'meta-source)
(meta-source-setup)

;; ---- captions: F5 shows the next one in the strip at the top
(defvar demo-captions
  '("An existing Clojure app: an integrant system, clojure.spec, Pathom resolvers. No meta-source in it yet."
    "Step 1 · tell meta-source where the integrant config is: a meta-source.edn at the project root"
    "→ a ref to a component no config defines, underlined where it is: found without starting the system"
    "Step 2 · add the Pathom resolvers to what meta-source reads"
    "→ resolver inputs that nothing produces"
    "Step 3 · add the specs, and one fact no library has a place for: :user/email is PII"
    "→ C-c . on :user/email: its spec, that it is PII, who produces and consumes it, from three sources at once"
    "Step 4 · one plain function opts in: a meta key on the defn, and its contract in two lines of EDN"
    "→ code reading a key its function's contract doesn't declare"
    "Step 5 · what did it cost? git status"
    "What it cost: one line of code changed, a meta-source.edn, a registry/ directory. No dependency added, no code loaded, nothing migrated."))

(defvar demo-caption (get-buffer-create "*caption*"))
(with-current-buffer demo-caption (setq mode-line-format nil cursor-type nil))

(defun demo-next ()
  (interactive)
  (with-current-buffer demo-caption
    (erase-buffer)
    (insert "\n  " (propertize (or (pop demo-captions) "") 'face '(:weight bold :foreground "#ffd75f")))))
(global-set-key (kbd "<f5>") #'demo-next)

(defun demo-layout ()
  (delete-other-windows)
  (let* ((top (selected-window))
         (main (split-window top 3 'below)))
    (set-window-buffer top demo-caption)
    (set-window-dedicated-p top t)
    (set-window-parameter top 'no-other-window t)
    ;; full width, stacked: what is being edited, the system config, the code
    (let* ((config (split-window main 8 'below))
           (code (split-window config 12 'below)))
      (set-window-buffer main (find-file-noselect "resources/system.test.edn"))
      (set-window-buffer config (find-file-noselect "resources/system.edn"))
      (set-window-buffer code (find-file-noselect "src/shop/resolvers.clj"))
      (select-window main))))
(add-hook 'emacs-startup-hook #'demo-layout)
