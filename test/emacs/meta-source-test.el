;;; meta-source-test.el --- meta-source.el against a real `meta-source serve' -*- lexical-binding: t -*-

;; Run from the meta-source checkout:
;;   emacs --batch -Q -l test/emacs/meta-source-test.el -f ert-run-tests-batch-and-exit
;; or `bb emacs-smoke', which puts clojure-mode on the load path when it
;; finds it (the hover tests need it, for keyword syntax; they are skipped
;; without it).  Needs Emacs 27+ and bb.  The fixture is test/fixtures/shop.

(require 'ert)
(require 'cl-lib)
(defconst meta-source-test--checkout
  (expand-file-name "../.." (file-name-directory (or load-file-name buffer-file-name))))
(require 'clojure-mode nil t)
(when (featurep 'clojure-mode)
  (add-to-list 'auto-mode-alist '("\\.edn\\'" . clojure-mode)))
(load (expand-file-name "emacs/meta-source.el" meta-source-test--checkout) nil t)
(setq meta-source-bb-config (expand-file-name "bb.edn" meta-source-test--checkout))
;; this checkout's code, even when a meta-source command is installed
(setq meta-source-command (list "bb" "--config" meta-source-bb-config "meta-source"))

(defun meta-source-test--fixture (file)
  (expand-file-name (concat "test/fixtures/shop/" file) meta-source-test--checkout))

(defun meta-source-test--report (file &optional edit)
  "What the backend reports for FILE: (:called DIAGNOSTICS) or :not-called.
With EDIT, the buffer has an unsaved change first."
  (with-current-buffer (find-file-noselect (meta-source-test--fixture file))
    (when edit (goto-char (point-max)) (insert " "))
    (let ((result :not-called)
          (deadline (+ (float-time) 120)))
      (meta-source-flymake (lambda (diags &rest _) (setq result (list :called diags))))
      (unless edit
        (while (and (eq result :not-called) (< (float-time) deadline))
          (accept-process-output nil 0.1)))
      (when edit (set-buffer-modified-p nil) (revert-buffer t t))
      result)))

(defun meta-source-test--marked (diag)
  "The text DIAG covers, its type and its message."
  (with-current-buffer (flymake-diagnostic-buffer diag)
    (list (buffer-substring-no-properties (flymake-diagnostic-beg diag) (flymake-diagnostic-end diag))
          (flymake-diagnostic-type diag)
          (flymake-diagnostic-text diag))))

;; ---- Flymake

(ert-deftest meta-source-flymake-code ()
  "A key code uses outside its function's contract, underlined in the code;
a var whose meta key nothing else describes, at the key."
  (let ((r (meta-source-test--report "src/shop/handlers.clj")))
    (should (eq (car r) :called))
    (should (equal (sort (mapcar #'meta-source-test--marked (cadr r)) (lambda (a b) (string< (car a) (car b))))
                   '((":fn/cancel-order" :note
                      "meta-source: :fn/cancel-order: the meta key names it, no source says anything about it yet")
                     (":order/discount" :note
                      "meta-source: :order/discount is used here but is not in :fn/place-order's contract"))))))

(ert-deftest meta-source-flymake-config ()
  "In an integrant config: the ref no config defines, and the id two packs declare."
  (let ((marked (mapcar #'meta-source-test--marked (cadr (meta-source-test--report "resources/system.edn")))))
    (should (member '(":shop/metrics" :warning "meta-source: no config defines :shop/metrics") marked))
    (should (cl-some (lambda (m) (and (equal (car m) ":shop.resolvers/seed") (eq (cadr m) :warning))) marked))))

(ert-deftest meta-source-flymake-destructured ()
  "A key only destructured is underlined at its name in the form."
  (let ((marked (mapcar #'meta-source-test--marked (cadr (meta-source-test--report "src/shop/resolvers.clj")))))
    (should (member '("lines" :note "meta-source: nothing produces :order/lines") marked))
    (should (member '("loyalty-tier" :note "meta-source: no schema shapes :user/loyalty-tier") marked))))

(ert-deftest meta-source-flymake-unsaved ()
  "With unsaved changes the backend doesn't answer: the server reads the disk."
  (should (eq (meta-source-test--report "src/shop/handlers.clj" t) :not-called)))

(ert-deftest meta-source-flymake-mode ()
  "meta-source-mode turns Flymake on with the backend, and Flymake shows its markers."
  (with-current-buffer (find-file-noselect (meta-source-test--fixture "src/shop/spec.clj"))
    (meta-source-mode 1)
    (should flymake-mode)
    (should (memq #'meta-source-flymake flymake-diagnostic-functions))
    (flymake-start)
    (let ((deadline (+ (float-time) 120)))
      (while (and (null (flymake-diagnostics)) (< (float-time) deadline))
        (accept-process-output nil 0.1)))
    (should (equal (mapcar #'meta-source-test--marked (flymake-diagnostics))
                   '((":user/emial" :note "meta-source: :user/emial has no spec of its own"))))))

;; ---- hover, M-., M-? (need clojure-mode, for keyword syntax)

(defun meta-source-test--at (file needle)
  "FILE's buffer, with point inside the first NEEDLE."
  (let ((b (find-file-noselect (meta-source-test--fixture file))))
    (with-current-buffer b
      (goto-char (point-min)) (search-forward needle) (backward-char 2))
    b))

(defun meta-source-test--eldoc ()
  (let ((got 'none) (deadline (+ (float-time) 120)))
    (meta-source-eldoc (lambda (s &rest _) (setq got s)))
    (while (and (eq got 'none) (< (float-time) deadline)) (accept-process-output nil 0.05))
    got))

(defun meta-source-test--files (xrefs)
  (sort (delete-dups (mapcar (lambda (x) (file-name-nondirectory (xref-file-location-file (xref-item-location x)))) xrefs))
        #'string<))

(ert-deftest meta-source-hover ()
  "Eldoc on a component describes it, and on a binding-only var; a symbol
that isn't a qualified keyword is left to other backends."
  (skip-unless (featurep 'clojure-mode))
  (with-current-buffer (meta-source-test--at "resources/system.edn" ":shop/db ")
    (should (string-prefix-p ":shop/db  integrant\ncomponent  refs 0" (meta-source-test--eldoc))))
  (with-current-buffer (meta-source-test--at "src/shop/handlers.clj" ":fn/cancel-order")
    (should (string-prefix-p ":fn/cancel-order  binding" (meta-source-test--eldoc))))
  (with-current-buffer (meta-source-test--at "src/shop/spec.clj" "string?")
    (should-not (meta-source--keyword-at-point))))

(ert-deftest meta-source-xref ()
  "M-. offers the config that declares it and the defmethods that implement
it (variant configs are listed in the entry, :integrant/config-in); M-? what
uses it."
  (skip-unless (featurep 'clojure-mode))
  (with-current-buffer (meta-source-test--at "resources/system.edn" ":shop/db ")
    (should (eq 'meta-source (meta-source--xref-backend)))
    (should (equal (meta-source-test--files (xref-backend-definitions 'meta-source ":shop/db"))
                   '("system.clj" "system.edn")))
    (should (equal (meta-source-test--files (xref-backend-references 'meta-source ":shop/db"))
                   '("system.edn")))))

;;; meta-source-test.el ends here
