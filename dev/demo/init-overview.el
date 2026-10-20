;;; init-overview.el --- the overview demo's Emacs -*- lexical-binding: t -*-
;; Loaded by dev/demo/record.py: meta-source.el from $MS_DEMO_EL (a copy, no
;; checkout), clojure-mode from $MS_DEMO_CLOJURE_MODE.
(setq inhibit-startup-screen t make-backup-files nil auto-save-default nil create-lockfiles nil)
(menu-bar-mode -1)
(add-to-list 'load-path (getenv "MS_DEMO_CLOJURE_MODE"))
(add-to-list 'load-path (getenv "MS_DEMO_EL"))   ; meta-source.el on its own, no checkout
(require 'clojure-mode)
(add-to-list 'auto-mode-alist '("\\.edn\\'" . clojure-mode))
(load-theme 'modus-vivendi t)
(setq flymake-show-diagnostics-at-end-of-line 'short
      eldoc-idle-delay 0.2
      flymake-no-changes-timeout nil)
(require 'meta-source)
(meta-source-setup)
