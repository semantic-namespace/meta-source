# Questions and answer key (saas-boilerplate at 6ee43e2)

Verified by hand against the code, and cross-checked with meta-source
(which corrected one item: the set-active-page dispatches are inside a #_ form).

Q1. Which integrant components does `:server/jetty` depend on, directly and transitively (#ig/ref only)?
  :webserver/port, :saas/handler; through :saas/handler: :auth/cognito, :saas/db, :saas/migrator, :saas/openai, :saas/telegram; through :saas/telegram: :telegram/instance, :telegram/channel. (9)

Q2. Which configured integrant keys have no init-key method anywhere in the code?
  :saas/telegram, :saas/telegram-webhook. (code has :telegram/webhook instead; the app uses integrant 0.8, no default init-key, so ig/init fails)

Q3. Which init-key methods exist for keys that no config file defines?
  :telegram/webhook, :db/migrations.

Q4. Which components depend, directly or transitively, on `:saas/db`?
  :saas/migrator, :saas/handler, :telegram/threads, :server/jetty, :saas/telegram-webhook. (5)

Q5. Which components have a halt-key! method?
  :server/jetty, :saas/db, :telegram/channel, :telegram/threads (and :telegram/webhook, not configured).

Q6. List the HTTP routes (method + full path) and the function that builds each handler.
  GET /swagger.json (reitit swagger handler); POST /v1/telegram-webhook (saas.telegram.handlers/telegram-webhook-handler);
  POST /v1/account/sign-up (saas.account.handlers/sign-up!); POST /v1/account/confirm (confirm-account);
  POST /v1/account/log-in (log-in); POST /v1/account/refresh (refresh-token).

Q7. Which routes have the snake->kebab middleware (mw/wrap-snake->kebab->snake)?
  The four /v1/account/* routes.

Q8. Which route handlers are handed the whole system map, and which only the auth component?
  Whole system: telegram-webhook, sign-up. Only auth: confirm, log-in, refresh.

Q9. Which re-frame events are dispatched but never registered?
  :app/show-mobile-sidebar, :app/toggle-mobile-sidebar, :saas.events/show-mobile-sidebar. (set-active-page is in #_ code: not counted)

Q10. Which subscriptions are subscribed to but never registered?
  :saas.subs/show-mobile-sidebar?, :nav/active-page, :nav/page-title.

Q11. Which registered events or subscriptions are never dispatched or subscribed?
  event :saas.events/toggle-mobile-sidebar; subs :saas.subs/name, :saas.subs/dark-mode?.

Q12. Which secrets (paths under :saas/secrets) feed which components?
  :cognito → :auth/cognito; :openai → :saas/openai; :telegram → :telegram/instance, and :telegram-config of :telegram/channel and :saas/telegram-webhook; :db → :saas/db.

Q13. Is the Telegram webhook handler wired to put messages on the :telegram/channel?
  No: its put is `(go (>! ))`, no channel and no value; the handler only destructures :db (unused). Messages never reach the channel.
