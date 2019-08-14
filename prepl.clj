(ns miracle.arcadia.prepl
  (:require [arcadia.core :refer :all]
            [clojure.main :as m]
            [clojure.core.server :as server])
  (:import System.Collections.Queue))

(def tracked-binding-symbols
  `(*ns* *warn-on-reflection* *math-context* *print-meta* *print-length* *print-level* *data-readers* *default-data-reader-fn* *compile-path* *command-line-args* *unchecked-math* *assert* *1 *2 *3 
         *e))

(defmacro set-tracked-bindings [map-expr]
  (let [m-sym (gensym "m_")
        settings (for [sym tracked-binding-symbols]
                   `(set! ~sym (get ~m-sym (var ~sym))))]
    `(let [~m-sym ~map-expr]
       ~@settings
       nil)))

(defn- ex->data
  [ex phase]
  (assoc (Throwable->map ex) :phase phase))

;; Since we need to `eval` on the main thread if we want to run
;; Unity functions, we store all forms that the prepl get
;; then `eval`uate them when `update-pego` is called,
;; which is either run from `PreplShimmy.cs` or
;; from the gameobject `PREPL Evaluator` that gets
;; created in play mode
(defonce ^{:doc "Atom storing all forms waiting to be `eval`uated."}
  eval-forms
  (atom []))

(defn check-current-eval
  [& _]
  (when-let [[forms _] (and (seq @eval-forms)
                            (reset-vals! eval-forms []))]
    (doseq [[p form] forms]
      (let [ret (try (pr-str (eval form))
                     (catch Exception e
                       (log "Exception was thrown in prepl" e)
                       {::error e}))]
        (deliver p {::ret ret
                    ::bindings (get-thread-bindings)})))))

(defonce eval-queue (Queue/Synchronized (Queue.)))

(defn safe-dequeue-all [^Queue queue]
  (locking queue
    (when (> (.Count queue) 0)
      (let [objs (.ToArray queue)]
        (.Clear queue)
        objs))))

(defn run-callbacks
  [work-queue]
  (let [ar (safe-dequeue-all work-queue)]
    (loop [i (int 0)]
      (when (< i (count ar))
        (do ((aget ar i))
            (recur (inc i)))))))

(defn run-callbacks-hook
  [& _]
  (run-callbacks eval-queue))

(defn init-pego!
  "Sets up a gameobject that will evaluate queued forms when we're in play mode.
  This is mainly useful in a built binary, since when in the editor the `PreplShimmy` will call evaluate the forms."
  [& _]
  (some-> (object-named "PREPL Evaluator") (retire))
  (let [go (GameObject. "PREPL Evaluator")]
    (hook+ go :update :update-pego #'run-callbacks-hook)))

(defn eval-promise
  "Given a form, this function returns a promise which will
  contain a map with `::bindings` and the `::ret`urn value
  of the form when `eval`uated by `run-callbacks`."
  [form]
  (.Enqueue eval-queue (bound-fn [& _] (check-current-eval)))
  (let [p (promise)]
    (swap! eval-forms conj [p form])
    p))

(defn reset-eval-forms!
  []
  (reset! eval-forms []))

(defn arcadia-prepl-inner-fn
  [in-reader EOF out-fn]
  (when (try
          (let [[form s] (read+string in-reader false EOF)]
            (try
              (when-not (identical? form EOF)
                (let [start (clojure.lang.RT/StartStopwatch)                                       ;;; (System/nanoTime)
                      {:keys [::ret ::bindings]} @(eval-promise form)
                      ms  (clojure.lang.RT/StopStopwatch)]                                         ;;; (quot (- (System/nanoTime) start) 1000000)
                  (log "a" ret "b" bindings)
                  (set-tracked-bindings bindings)
                  (when-let [e (get ret ::error)]
                    (throw e))
                  (when-not (= :repl/quit ret)
                    (set! *3 *2)
                    (set! *2 *1)
                    (set! *1 ret)
                    (out-fn {:tag :ret
                             :val (if (instance? Exception ret)                                    ;;; Throwable
                                    (Throwable->map ret)
                                    ret)
                             :ns (str (.Name *ns*))                                                ;;; .name
                             :ms ms
                             :form s})
                    true)))
              (catch Exception ex                                                                  ;;; Throwable
                (set! *e ex)
                (out-fn {:tag :ret :val (ex->data ex (or (-> ex ex-data :clojure.error/phase) :execution))
                         :ns (str (.Name *ns*)) :form s                                            ;;; .name
                         :exception true})
                true)))
          (catch Exception ex                                                                      ;;; Throwable
            (set! *e ex)
            (out-fn {:tag :ret :val (ex->data ex :read-source)
                     :ns (str (.Name *ns*))                                                        ;;; .name
                     :exception true})
            true))))

(defn arcadia-prepl
  "a REPL with structured output (for programs)
  This is heavily based on the official prepl, but with some workarounds
  in order to `eval`uate forms on the main thread of Unity.

  reads forms to eval from in-reader (a LineNumberingPushbackReader)
  Closing the input or passing the form :repl/quit will cause it to return
  
   Calls out-fn with data, one of:
  {:tag :ret
   :val val ;;eval result
   :ns ns-name-string
   :ms long ;;eval time in milliseconds
   :form string ;;iff successfully read
   :clojure.error/phase (:execution et al per clojure.main/ex-triage) ;;iff error occurred
  }
  {:tag :out
   :val string} ;chars from during-eval *out*
  {:tag :err
   :val string} ;chars from during-eval *err*
  {:tag :tap
   :val val} ;values from tap>
  
   You might get more than one :out or :err per eval, but exactly one :ret
  tap output can happen at any time (i.e. between evals)
  If during eval an attempt is made to read *in* it will read from in-reader unless :stdin is supplied
  
  Alpha, subject to change."
  {:added "1.10"}
  [in-reader out-fn & {:keys [stdin]}]
  (let [EOF (Object.)
        tapfn #(out-fn {:tag :tap :val %1})]
    (m/with-bindings
      (in-ns 'user)
      (binding [*in* (or stdin in-reader)
                *out* (PrintWriter-on #(out-fn {:tag :out :val %1}) nil)
                *err* (PrintWriter-on #(out-fn {:tag :err :val %1}) nil)]
        (try
          (add-tap tapfn)
          (loop []
            (arcadia-prepl-inner-fn in-reader EOF out-fn)
            (recur))
          (finally
            (remove-tap tapfn)))))))

(defn io-arcadia-prepl
  "prepl bound to *in* and *out*, suitable for use with e.g. server/repl (socket-repl).
  :ret and :tap vals will be processed by valf, a fn of one argument
  or a symbol naming same (default pr-str)
  
  Alpha, subject to change."
  {:added "1.10"}
  [& _ #_ {:keys [valf] :or {valf pr-str}}] ;; The keys aren't used, since even `pr-str` needs to be run on the main thread
  (let [;;valf (resolve-fn valf)
        out *out*
        lock (Object.)]
    (arcadia-prepl *in*
                   (fn [m]
                     (binding [*out* out, *flush-on-newline* true, *print-readably* true]
                       (locking lock
                         (prn (if (#{:ret :tap} (:tag m))
                                (try
                                  (assoc m :val (:val m))
                                  (catch Exception ex                                        ;;; Throwable
                                    (assoc m :val (ex->data ex :print-eval-result)
                                           :exception true)))
                                m))))))))

(defonce current-server (atom nil))

(defn start-server []
  (when @current-server (try (server/stop-server "Arcadia prepl")
                             (catch Exception e
                               (log "Found dangling server reference. No worries though." e))))
  (let [settings {:accept 'miracle.arcadia.prepl/io-arcadia-prepl
                  :address "127.0.0.1"
                  :port 7653
                  :name "Arcadia prepl"}]
    (log "Starting prepl-server with settings:" settings)
    (reset! current-server (server/start-server settings))))

#_(start-server)
#_(server/stop-server "Arcadia prepl")

