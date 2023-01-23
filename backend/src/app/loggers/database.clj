;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.database
  "A specific logger impl that persists errors on the database."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec.csp :as sp]
   [promesa.exec :as px]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error Listener
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handle-event)

(defonce enabled (atom true))

(defn- persist-on-database!
  [{:keys [::db/pool] :as cfg} {:keys [id] :as event}]
  (when-not (db/read-only? pool)
    (db/insert! pool :server-error-report {:id id :content (db/tjson event)})))

(defn parse-event
  [{:keys [::l/props ::l/context event] :as record}]
  (-> {}
      (assoc :hint (or (:hint event) (:message event)))
      (assoc :tenant (cf/get :tenant))
      (assoc :host (cf/get :host))
      (assoc :public-uri (cf/get :public-uri))
      (assoc :version (:full cf/version))
      (update :id #(or % (uuid/next)))))

(defn- handle-event
  [cfg record]
  (try
    (app.common.pprint/pprint record)
    #_(let [event (prepare-event record)
            uri   (cf/get :public-uri)]


        (l/debug :hint "registering error on database" :id (:id event)
                 :uri (str uri "/dbg/error/" (:id event)))

        (persist-on-database! cfg event))
    (catch Throwable cause
      (l/warn :hint "unexpected exception on database error logger" :cause cause))))

(defn- error-event?
  [{:keys [::l/level]}]
  (= :error level))

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (let [input (sp/chan (sp/sliding-buffer 32) (filter error-event?))]
    (add-watch l/log-record ::reporter #(sp/put! input %4))
    (px/thread
      {:name "penpot/database-reporter" :virtual true}
      (l/info :hint "initializing database error persistence")
      (try
        (loop []
          (when-let [record (sp/take! input)]
            (handle-event cfg record)
            (recur)))
        (catch InterruptedException _
          (l/debug :hint "reporter interrupted"))
        (catch Throwable cause
          (l/error :hint "unexpected error" :cause cause))
        (finally
          (sp/close! input)
          (remove-watch l/log-record ::reporter)
          (l/info :hint "reporter terminated"))))))

(defmethod ig/halt-key! ::reporter
  [_ thread]
  (some-> thread px/interrupt!))
