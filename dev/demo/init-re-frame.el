;;; init-re-frame.el --- the re-frame demo's Emacs -*- lexical-binding: t -*-
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
(require 'meta-source)
(meta-source-setup)

;; ---- captions: F5 shows the next one in the strip at the top
(defvar demo-captions
  '("A re-frame app: events, subscriptions, views, a spec. It compiles and clj-kondo is happy. No meta-source in it yet."
    "Step 1 · a meta-source.edn at the project root: read the re-frame registrations (:http-xhrio comes from a library)"
    "→ a callback to an event nothing registers, and a subscription with a typo. re-frame would only log them, when they run."
    "Step 2 · C-c . on the event the button dispatches"
    "→ where it is registered and dispatched from, what it dispatches, the effects it returns, the keys it writes"
    "Step 3 · add the specs, and one fact no library has a place for: :user/email is PII"
    "→ C-c . on :user/email: its spec, that it is PII, the event that writes it and the subscription that reads it"
    "Step 4 · fix the typo"
    "→ both findings go: the subscription exists, and :line-count is no longer unused"
    "What it cost: a meta-source.edn and one fact in EDN. Nothing loaded, nothing run: the forms were read as data."))

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
    ;; full width, stacked: what is being edited, the views, the events
    (let* ((views (split-window main 8 'below))
           (events (split-window views 18 'below)))
      (set-window-buffer main (find-file-noselect "src/shop/subs.cljs"))
      (set-window-buffer views (find-file-noselect "src/shop/views.cljs"))
      (set-window-buffer events (find-file-noselect "src/shop/events.cljs"))
      (select-window main))))
(add-hook 'emacs-startup-hook #'demo-layout)
