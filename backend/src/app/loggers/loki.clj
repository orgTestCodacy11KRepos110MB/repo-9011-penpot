;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.loki
  "A Loki integration."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.http.client :as http]
   [app.util.json :as json]
   [app.loggers.database :as ldb]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(defonce enabled (atom true))

(declare ^:private handle-record)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req [::http/client]))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (when-let [uri (cf/get :loggers-loki-uri)]
    (px/thread
      {:name "penpot/loki-reporter"
       :virtual true}
      (l/info :hint "initializing loki reporter" :uri uri)
      (let [input (sp/chan (sp/dropping-buffer 2048)
                           (comp
                            (filter ldb/error-record?)
                            (remove (fn [record] (= (::l/logger record) "app.loggers.loki")))))
            cfg   (assoc cfg ::uri uri)]
        (add-watch l/log-record ::reporter #(sp/put! input %4))
        (try
          (loop []
            (when-let [record (sp/take! input)]
              (handle-record cfg record)
              (recur)))

          (catch InterruptedException _
            (l/debug :hint "reporter interrupted"))
          (catch Throwable cause
            (l/error :hint "unexpected exception"
                     :cause cause))
          (finally
            (sp/close! input)
            (remove-watch l/log-record ::reporter)
            (l/info :hint "reporter terminated")))))))

(defmethod ig/halt-key! ::reporter
  [_ thread]
  (some-> thread px/interrupt!))

(defn- prepare-payload
  [{:keys [::l/logger ::l/level ::l/message ::l/trace ::l/timestamp]}]
  (let [labels {:host    (cf/get :host)
                :tenant  (cf/get :tenant)
                :version (:full cf/version)
                :logger  logger
                :level   (name level)}]
    {:streams
     [{:stream labels
       :values [[(str (* timestamp 1000000))
                 (cond-> @message
                   (some? trace)
                   (str "\n" @trace))]]}]}))

(defn- make-request
  [{:keys [::uri] :as cfg} payload]
  (http/req! cfg
             {:uri uri
              :timeout 3000
              :method :post
              :headers {"content-type" "application/json"}
              :body (json/encode payload)}
             {:sync? true}))

(defn- handle-record
  [cfg record]
  (us/assert! ::l/record record)
  (try
    (let [payload  (prepare-payload record)
          response (make-request cfg payload)]
      (prn "RECORD" response)

      (when-not (= 204 (:status response))
        (l/error :hint "error on sending log to loki (unexpected response)"
                 :response (pr-str response))))

    (catch java.net.ConnectException cause
      (l/error :hint "unable to connect to loki server"
               :uri (::uri cfg)
               :cause cause))

    (catch Throwable cause
      (l/error :hint "error on sending log to loki (unexpected exception)"
               :cause cause))))
