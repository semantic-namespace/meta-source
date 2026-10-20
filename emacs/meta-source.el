;;; meta-source.el --- What a Clojure project's keywords are, from its files -*- lexical-binding: t -*-

;; Author: @tangrammer
;; URL: https://github.com/semantic-namespace/meta-source
;; Package-Requires: ((emacs "27.1"))

;;; Commentary:
;;
;; In a project with a `meta-source.edn' at its root, point on any
;; `:ns/name' keyword, in a .clj file or an .edn file, and:
;;
;;   eldoc     shows what it is: an entity (what declares it: a pack, an
;;             EDN entry, a var's meta key; what it consumes and produces;
;;             who uses it) or a data key (its shape, producers, consumers)
;;   M-.       jumps to where it is declared: each file that says something
;;             about it, and the var that implements it
;;   M-?       lists what uses it
;;   C-c .     opens a describe buffer with clickable file:line links
;;   Flymake   underlines what `meta-source check' finds, where it is: a key
;;             used outside its contract, a ref no config defines, a
;;             resolver input nothing produces, conflicting sources...
;;             (M-x flymake-show-buffer-diagnostics lists them)
;;
;; Entities come from the project's EDN files and from the registries its
;; libraries already declare, read by packs: integrant systems, specs and
;; malli schemas, Pathom resolvers, a project's own.
;;
;; Answers come from `meta-source serve', one long-lived babashka process
;; per project that reads the files and the code (clj-kondo) without loading
;; anything, keeps the index in memory and refreshes it from disk before
;; each answer.  Saved files are seen; unsaved edits are not: while a buffer
;; has unsaved changes, Flymake keeps the markers it has, which move with
;; the text, and asks again when the buffer is saved.
;;
;; For keywords meta-source doesn't know, eldoc and xref fall through to the
;; next backend (clojure-lsp, CIDER).
;;
;; Setup: install the meta-source command
;;   bbin install io.github.semantic-namespace/meta-source
;; put this file anywhere on your load path, and
;;   (require 'meta-source)
;;   (meta-source-setup)   ; enable in Clojure buffers of projects with a meta-source.edn
;; Without the command, it runs `bb meta-source' from the meta-source checkout
;; this file is in (`meta-source-bb-config').

;;; Code:

(require 'cl-lib)
(require 'eldoc)
(require 'flymake)
(require 'xref)
(require 'json)

(defgroup meta-source nil
  "Atlas keywords from the project's source files."
  :group 'tools)

(defcustom meta-source-bb-program (or (executable-find "bb") "/usr/local/bin/bb")
  "The babashka executable."
  :type 'string)

(defcustom meta-source-bb-config
  (expand-file-name "../bb.edn" (file-name-directory (or load-file-name buffer-file-name)))
  "The meta-source checkout's bb.edn, which defines the `meta-source' task: what
`meta-source-command' runs when the meta-source command isn't installed."
  :type 'file)

(defcustom meta-source-command nil
  "The command that answers for a project, before its arguments (\"serve\"
\"--root\" ROOT).  nil: the meta-source command when it is on `exec-path'
\(bbin install io.github.semantic-namespace/meta-source), else `bb meta-source' from
`meta-source-bb-config'."
  :type '(choice (const :tag "meta-source, else bb source from the checkout" nil)
                 (repeat string)))

(defun meta-source--command ()
  (or meta-source-command
      (when-let* ((cmd (executable-find "meta-source"))) (list cmd))
      (list meta-source-bb-program "--config" meta-source-bb-config "meta-source")))

(defcustom meta-source-flymake t
  "When non-nil, `meta-source-mode' turns Flymake on with meta-source's
diagnostics.  The backend is added either way; Flymake shows it when on."
  :type 'boolean)

(defcustom meta-source-timeout 3
  "Seconds to wait for a synchronous answer (M-., M-?, describe)."
  :type 'number)

;; =============================================================================
;; Server: one `bb source serve' per project root
;; =============================================================================

(defvar meta-source--servers (make-hash-table :test 'equal)
  "Project root -> server process.")

(defvar meta-source--callbacks (make-hash-table :test 'eql)
  "Request id -> fn of the reply plist.")

(defvar meta-source--next-id 0)

(defun meta-source--root ()
  "The project root: the closest directory with an meta-source.edn."
  (when-let* ((dir (locate-dominating-file default-directory "meta-source.edn")))
    (expand-file-name dir)))

(defun meta-source--filter (proc output)
  "Dispatch each complete JSON line PROC printed to its request's callback."
  (let ((pending (concat (or (process-get proc 'pending) "") output)))
    (while (string-match "\n" pending)
      (let ((line (substring pending 0 (match-beginning 0))))
        (setq pending (substring pending (match-end 0)))
        (when-let* ((reply (ignore-errors
                             (json-parse-string line :object-type 'plist :array-type 'list
                                                :null-object nil :false-object nil)))
                    (id (plist-get reply :id))
                    (cb (gethash id meta-source--callbacks)))
          (remhash id meta-source--callbacks)
          (funcall cb reply))))
    (process-put proc 'pending pending)))

(defun meta-source--server (root)
  "The live server for ROOT, started if needed."
  (let ((proc (gethash root meta-source--servers)))
    (if (process-live-p proc)
        proc
      (let* ((default-directory root)
             (proc (make-process
                    :name (format "meta-source[%s]" (file-name-nondirectory (directory-file-name root)))
                    :command (append (meta-source--command) (list "serve" "--root" root))
                    :connection-type 'pipe
                    :coding 'utf-8
                    :noquery t
                    :stderr (get-buffer-create " *meta-source stderr*")
                    :filter #'meta-source--filter)))
        (puthash root proc meta-source--servers)
        proc))))

(defun meta-source--request (root op keyword callback &optional extra)
  "Send OP for KEYWORD to ROOT's server; call CALLBACK with the reply plist.
EXTRA is a plist of more request fields, such as (:file FILE)."
  (let ((id (cl-incf meta-source--next-id)))
    (puthash id callback meta-source--callbacks)
    (process-send-string (meta-source--server root)
                         (concat (json-serialize (append (list :id id :op op :keyword (or keyword "")) extra)) "\n"))
    id))

(defun meta-source--request-sync (root op keyword)
  "OP for KEYWORD from ROOT's server, waiting up to `meta-source-timeout'."
  (let* ((done nil) (reply nil)
         (proc (meta-source--server root))
         (deadline (+ (float-time) meta-source-timeout)))
    (meta-source--request root op keyword (lambda (r) (setq reply r done t)))
    (while (and (not done) (< (float-time) deadline) (process-live-p proc))
      (accept-process-output proc 0.05))
    (or reply (user-error "meta-source: no answer from %s (see buffer \" *meta-source stderr*\")" root))))

(defun meta-source-stop ()
  "Stop every meta-source server."
  (interactive)
  (maphash (lambda (_ p) (when (process-live-p p) (delete-process p))) meta-source--servers)
  (clrhash meta-source--servers)
  (clrhash meta-source--callbacks))

;; =============================================================================
;; Keyword at point
;; =============================================================================

(defun meta-source--keyword-at-point ()
  "The qualified keyword at point, as a string like \":fn/get-cart\", or nil."
  (let ((s (thing-at-point 'symbol t)))
    (when (and s (string-match-p "\\`:[^:/[:space:]]+/[^/[:space:]]+\\'" s))
      s)))

(defun meta-source--hover (keyword)
  "The hover result plist for KEYWORD, or nil when meta-source doesn't know it."
  (when-let* ((root (meta-source--root)))
    (let ((result (plist-get (meta-source--request-sync root "hover" keyword) :result)))
      (unless (equal (plist-get result :kind) "none") result))))

;; =============================================================================
;; eldoc
;; =============================================================================

(defun meta-source-eldoc (callback &rest _)
  "Eldoc for the keyword at point when meta-source knows it.
nil (falls through to the next backend) otherwise."
  (let ((kw (meta-source--keyword-at-point))
        (root (meta-source--root)))
    (when (and kw root)
      (meta-source--request
       root "hover" kw
       (lambda (reply)
         (let ((r (plist-get reply :result)))
           (if (and r (not (equal (plist-get r :kind) "none")))
               (funcall callback (plist-get r :eldoc) :thing kw :face 'font-lock-constant-face)
             (funcall callback nil)))))
      t)))

;; =============================================================================
;; xref (M-. and M-?)
;; =============================================================================

(defun meta-source--xref-backend ()
  "The meta-source xref backend, when point is on a keyword it knows."
  (when-let* ((kw (meta-source--keyword-at-point)))
    (when (meta-source--hover kw) 'meta-source)))

(cl-defmethod xref-backend-identifier-at-point ((_ (eql meta-source)))
  (meta-source--keyword-at-point))

(cl-defmethod xref-backend-identifier-completion-table ((_ (eql meta-source)))
  nil)

(defun meta-source--xrefs (keyword key)
  (mapcar (lambda (l)
            (xref-make (plist-get l :summary)
                       (xref-make-file-location (plist-get l :file) (plist-get l :line) 0)))
          (plist-get (meta-source--hover keyword) key)))

(cl-defmethod xref-backend-definitions ((_ (eql meta-source)) identifier)
  (meta-source--xrefs identifier :definitions))

(cl-defmethod xref-backend-references ((_ (eql meta-source)) identifier)
  (meta-source--xrefs identifier :references))

;; =============================================================================
;; Describe buffer
;; =============================================================================

(define-derived-mode meta-source-describe-mode special-mode "Atlas"
  "Describes a keyword. RET or click follows a file:line or keyword.")

(defun meta-source--buttonize (root)
  "Make file:line references and qualified keywords in the buffer clickable."
  (goto-char (point-min))
  (while (re-search-forward "\\([^[:space:]]+\\.\\(?:edn\\|cljc?\\)\\):\\([0-9]+\\)\\(?:-[0-9]+\\)?" nil t)
    (let ((file (expand-file-name (match-string 1) root))
          (line (string-to-number (match-string 2))))
      (make-text-button (match-beginning 0) (match-end 0)
                        'action (lambda (_) (find-file-other-window file) (goto-char (point-min)) (forward-line (1- line)))
                        'help-echo (format "open %s:%d" file line))))
  (goto-char (point-min))
  (while (re-search-forward ":[[:alnum:]._*+!?<>=-]+/[[:alnum:]._*+!?<>=-]+" nil t)
    (unless (get-text-property (match-beginning 0) 'button)
      (let ((kw (match-string 0)))
        (make-text-button (match-beginning 0) (match-end 0)
                          'action (lambda (_) (meta-source-describe kw))
                          'face 'font-lock-constant-face
                          'help-echo (format "describe %s" kw))))))

(defun meta-source-describe (&optional keyword)
  "Describe KEYWORD (default: the keyword at point) in a buffer with links."
  (interactive)
  (let* ((kw (or keyword (meta-source--keyword-at-point) (user-error "No qualified keyword at point")))
         (root (or (meta-source--root) (user-error "No meta-source.edn above %s" default-directory)))
         (r (or (meta-source--hover kw) (user-error "meta-source doesn't know %s" kw))))
    (with-current-buffer (get-buffer-create "*meta-source*")
      (let ((inhibit-read-only t))
        (erase-buffer)
        (meta-source-describe-mode)
        (setq default-directory root)
        (insert (plist-get r :describe) "\n")
        (meta-source--buttonize root)
        (goto-char (point-min))
        (put-text-property (point-min) (line-end-position) 'face 'bold))
      (display-buffer (current-buffer)))))

;; =============================================================================
;; Flymake
;; =============================================================================

(defun meta-source--diagnostic (buffer d)
  "A Flymake diagnostic in BUFFER for D, a plist from the `diagnostics' op:
:line, and :col/:end-col (1-based) when there is a token to underline."
  (with-current-buffer buffer
    (save-excursion
      (save-restriction
        (widen)
        (goto-char (point-min))
        (forward-line (1- (plist-get d :line)))
        (let* ((bol (point))
               (eol (line-end-position))
               (col (plist-get d :col))
               (end-col (plist-get d :end-col))
               (beg (if col (min eol (+ bol (1- col))) bol))
               (end (if end-col (min eol (+ bol (1- end-col))) eol)))
          (flymake-make-diagnostic
           buffer beg (max end (min (1+ beg) (point-max)))
           (pcase (plist-get d :severity)
             ("error" :error)
             ("warning" :warning)
             (_ :note))
           (plist-get d :message)))))))

(defun meta-source-flymake (report-fn &rest _)
  "Flymake backend: what `bb source check' finds in this buffer's file.
The server reads files from disk, so while the buffer has unsaved changes
it doesn't answer: Flymake keeps the markers it has, which move with the
text, and asks again on save."
  (let ((root (meta-source--root))
        (file buffer-file-name)
        (buffer (current-buffer)))
    (cond
     ((not (and root file)) (funcall report-fn nil))
     ((buffer-modified-p) nil)
     (t (meta-source--request
         root "diagnostics" nil
         (lambda (reply)
           (when (buffer-live-p buffer)
             (if-let* ((err (plist-get reply :error)))
                 (funcall report-fn :panic :explanation err)
               (funcall report-fn
                        (mapcar (lambda (d) (meta-source--diagnostic buffer d))
                                (plist-get reply :result))))))
         (list :file (expand-file-name file)))))))

;; =============================================================================
;; Mode
;; =============================================================================

(defvar meta-source-mode-map
  (let ((m (make-sparse-keymap)))
    (define-key m (kbd "C-c .") #'meta-source-describe)
    m))

;;;###autoload
(define-minor-mode meta-source-mode
  "Hover, M-., M-? and Flymake for a project's keywords, from its files."
  :lighter " meta"
  :keymap meta-source-mode-map
  (if meta-source-mode
      (progn
        ;; Ahead of CIDER (`cider-xref-fn-depth' -90) and lsp-mode: the
        ;; meta-source backends answer only for keywords it knows, so the rest
        ;; still reaches them.
        (add-hook 'eldoc-documentation-functions #'meta-source-eldoc -95 t)
        (add-hook 'xref-backend-functions #'meta-source--xref-backend -95 t)
        (add-hook 'flymake-diagnostic-functions #'meta-source-flymake nil t)
        (setq-local eldoc-echo-area-use-multiline-p t)
        (eldoc-mode 1)
        (when meta-source-flymake (flymake-mode 1)))
    (remove-hook 'eldoc-documentation-functions #'meta-source-eldoc t)
    (remove-hook 'xref-backend-functions #'meta-source--xref-backend t)
    (remove-hook 'flymake-diagnostic-functions #'meta-source-flymake t)))

(defun meta-source-maybe-enable ()
  "Enable `meta-source-mode' in a project with a meta-source.edn."
  (when (and buffer-file-name (meta-source--root))
    (meta-source-mode 1)))

(defun meta-source-setup ()
  "Enable `meta-source-mode' in Clojure buffers of meta-source projects.
Now, and in buffers opened later."
  (interactive)
  (dolist (h '(clojure-mode-hook clojurec-mode-hook clojurescript-mode-hook
               clojure-ts-mode-hook clojure-ts-clojurec-mode-hook))
    (add-hook h #'meta-source-maybe-enable))
  (dolist (b (buffer-list))
    (with-current-buffer b
      (when (derived-mode-p 'clojure-mode 'clojure-ts-mode)
        (meta-source-maybe-enable)))))

(provide 'meta-source)
;;; meta-source.el ends here
