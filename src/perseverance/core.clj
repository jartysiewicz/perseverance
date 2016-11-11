(ns perseverance.core
  "Flexible retry utility for unreliable operations."
  (:import java.io.IOException
           clojure.lang.ExceptionInfo))

(def ^:dynamic *retry-contexts*
  "Dynamic variable that contains a stack of retry contexts. Each context is a
  map that consists of a strategy, a map of error tokens to strategies, and an
  optional selector."
  ())

;; Raise site

(defn- maybe-handle-exception
  "If `og-ex`, the exception caught, is the one that might be retried (based on
  `opts`), and the retry context is set for this exception, handle the exception
  and produce an according delay."
  [og-ex attempt error-token opts]
  (let [wrapped-ex (if-let [ex-wrapper (:ex-wrapper opts)]
                     (ex-wrapper og-ex)
                     (ex-info "Retriable code failed." {:tag (:tag opts)
                                                        :e og-ex
                                                        :token error-token}))
        {strat-map :strategies-map, log-fn :log-fn, :as ctx}
        (some #(if-let [selector (:selector %)]
                 ;; If the retry context has a selector, check
                 ;; if the selector matches the exception.
                 ;; Otherwise, the context always matches.
                 (when (selector wrapped-ex) %)
                 %)
              *retry-contexts*)]
    ;; If no context matched the error, throw it further.
    (when-not ctx
      (throw og-ex))
    (when-not (get @strat-map error-token)
      (swap! strat-map assoc error-token (:strategy ctx)))
    (let [strategy (get @strat-map error-token)
          delay (strategy attempt)]
      (when-not delay ; Strategy said we should stop retrying.
        (throw wrapped-ex))
      (log-fn wrapped-ex delay)
      (Thread/sleep delay)
      error-token)))

(defmacro retriable
  "Wraps `body` in a loop that whenever SocketTimoutException is signaled,
  `:retry-network` restart will be available to call `body` again. When
  signaling a condition, attach `extras` to it."
  [opts & body]
  (let [error-token (gensym "error-token")
        attempt (gensym "attempt")]
    `(let [~error-token (Object.)]
       (loop [~attempt 1]
         (let [result#
               (try (do ~@body)
                    ~@(map (fn [etype]
                             `(catch ~etype ex#
                                (#'maybe-handle-exception
                                 ex# ~attempt ~error-token
                                 ~(select-keys opts [:tag :ex-wrapper]))))
                           (or (:catch opts) [IOException])))]
           (if (identical? result# ~error-token)
             (recur (inc ~attempt))
             result#))))))

;; Handle site

(defn constant-retry-strategy
  "Create a retry strategy that returns same `delay` for each attempt. If
  `max-count` is specified, the strategy returns nil after so many attempts."
  ([delay]
   (constant-retry-strategy delay nil))
  ([delay max-count]
   (fn [attempt]
     (when-not (and max-count (> attempt max-count))
       delay))))

(defn progressive-retry-strategy
  "Create a retry strategy that returns a raising delay. First `stable-length`
  attempts have `initial-delay`, each next attempt the delay is increased by
  `multiplier` times. Delay cannot be bigger than `max-delay`. If `max-count` is
  specified and reached, nil is returned."
  [& {:keys [initial-delay stable-length multiplier max-delay max-count]
      :or {initial-delay 500, stable-length 3, multiplier 2, max-delay 60000}}]
  (fn [attempt]
    (when-not (and max-count (> attempt max-count))
      (if (<= attempt stable-length)
        initial-delay
        (* initial-delay (int (Math/pow multiplier (- attempt stable-length))))))))

(defn- default-log-fn
  "Prints a message to stdout that an error happened and going to be retried."
  [wrapped-ex delay]
  (println (format "%s, retrying in %.1f seconds..."
                   (:e (ex-data wrapped-ex))
                   (/ delay 1000.0))))

(defmacro retry
  [{:keys [strategy selector log-fn]} & body]
  `(binding [*retry-contexts*
             (cons {:strategy ~(or strategy (progressive-retry-strategy))
                    :strategies-map (atom {})
                    :selector ~(if (keyword? selector)
                                 `(fn [ex#] (= (:tag (ex-data ex#)) ~selector))
                                 selector)
                    :log-fn ~(or log-fn `#'default-log-fn)}
                   *retry-contexts*)]
     ~@body))