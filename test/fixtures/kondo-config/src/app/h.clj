(ns app.h (:require [app.m :refer [defhandler]]))
(defhandler ^{:meta-source/id :fn/show} show [user] (:user/email user))
