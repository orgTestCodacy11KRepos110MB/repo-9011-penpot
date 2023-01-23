;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.exceptions
  "A helpers for work with exceptions."
  #?(:cljs (:require-macros [app.common.exceptions]))
  (:require
   #?(:clj [clojure.stacktrace :as strace])
   [app.common.pprint :as pp]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [expound.alpha :as expound])
  #?(:clj
     (:import
      app.common.exceptions.ExceptionData
      clojure.lang.IPersistentMap)))

#?(:clj (set! *warn-on-reflection* true))

(defmacro error
  [& {:keys [type hint] :as params}]
  (if (:ns &env)
    `(ex-info ~(or hint (name type))
              (merge
               ~(dissoc params :cause ::data)
               ~(::data params))
              ~(:cause params))
    `(let [params#  (merge ~(dissoc params :cause ::data ::meta) ~(::data params))
           message# ~(or hint (name type))]
       (ExceptionData/create
        ^String message#
        ^IPersistentMap params#
        ^IPersistentMap ~(::meta params)
        ^Throwable ~(:cause params)))))

(defmacro raise
  [& params]
  `(throw (error ~@params)))

(defn try*
  [f on-error]
  (try (f) (catch #?(:clj Throwable :cljs :default) e (on-error e))))

;; http://clj-me.cgrand.net/2013/09/11/macros-closures-and-unexpected-object-retention/
;; Explains the use of ^:once metadata

(defmacro ignoring
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) (constantly nil)))

(defmacro try!
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) identity))

(defn ex-info?
  {:deprecated true}
  [v]
  (instance? #?(:clj clojure.lang.IExceptionInfo :cljs cljs.core.ExceptionInfo) v))

(defn error?
  [v]
  (instance? #?(:clj clojure.lang.IExceptionInfo :cljs cljs.core.ExceptionInfo) v))

(defn exception?
  [v]
  (instance? #?(:clj java.lang.Throwable :cljs js/Error) v))

#?(:clj
   (defn runtime-exception?
     [v]
     (instance? RuntimeException v)))

(defn explain
  ([data] (explain data nil))
  ([data {:keys [max-problems] :or {max-problems 10} :as opts}]
   (cond
     ;; ;; NOTE: a special case for spec validation errors on integrant
     (and (= (:reason data) :integrant.core/build-failed-spec)
          (contains? data :explain))
     (explain (:explain data) opts)

     (and (contains? data ::s/problems)
          (contains? data ::s/value)
          (contains? data ::s/spec))
     (binding [s/*explain-out* expound/printer]
       (with-out-str
         (s/explain-out (update data ::s/problems #(take max-problems %))))))))

#?(:clj
(defn format-throwable
  [^Throwable cause & {:keys [trace? data? chain? data-level data-length trace-length]
                       :or {trace? true
                            data? true
                            chain? true
                            data-length 10
                            data-level 3}}]
  (letfn [(print-trace-element [^StackTraceElement e]
            (let [class (.getClassName e)
                  method (.getMethodName e)]
              (let [match (re-matches #"^([A-Za-z0-9_.-]+)\$(\w+)__\d+$" (str class))]
                (if (and match (= "invoke" method))
                  (apply printf "%s/%s" (rest match))
                  (printf "%s.%s" class method))))
            (printf "(%s:%d)" (or (.getFileName e) "") (.getLineNumber e)))

          (print-explain [explain]
            (print "    xp: ")
            (let [[line & lines] (str/lines explain)]
              (print line)
              (newline)
              (doseq [line lines]
                (println "       " line))))

          (print-data [data]
            (when (seq data)
              (print "    dt: ")
              (let [[line & lines] (str/lines (pp/pprint-str data :level data-level :length data-length ))]
                (print line)
                (newline)
                (doseq [line lines]
                  (println "       " line)))))

          (print-trace-title [cause]
            (print   " →  ")
            (printf "%s: %s" (.getName (class cause)) (first (str/lines (ex-message cause))))

            (when-let [e (first (.getStackTrace ^Throwable cause))]
              (printf " (%s:%d)" (or (.getFileName e) "") (.getLineNumber e)))

            (newline))

          (print-summary [cause]
            (let [causes (loop [cause (ex-cause cause)
                                result []]
                           (if cause
                             (recur (ex-cause cause)
                                    (conj result cause))
                             result))]
              (println "TRACE:")
              (print-trace-title cause)
              (doseq [cause causes]
                (print-trace-title cause))))

          (print-trace [cause]
            (print-trace-title cause)
            (let [st (.getStackTrace cause)]
              (print "    at: ")
              (if-let [e (first st)]
                (print-trace-element e)
                (print "[empty stack trace]"))
              (newline)

              (doseq [e (if (nil? trace-length) (rest st) (take (dec trace-length) (rest st)))]
                (print "        ")
                (print-trace-element e)
                (newline))))

          (print-all [cause]
            (print-summary cause)
            (println "DETAIL:")
            (when trace?
              (print-trace cause))

            (when data?
              (when-let [data (ex-data cause)]
                (if-let [explain (explain data)]
                  (print-explain explain)
                  (print-data data))))

            (when chain?
              (loop [cause cause]
                (when-let [cause (ex-cause cause)]
                  (newline)
                  (print-trace cause)

                  (when data?
                    (when-let [data (ex-data cause)]
                      (if-let [explain (explain data)]
                        (print-explain explain)
                        (print-data data))))

                  (recur cause)))))
          ]
    (with-out-str
      (print-all cause)))))

#?(:clj
(defn print-throwable
  [cause & {:as opts}]
  (println (format-throwable cause opts))))
