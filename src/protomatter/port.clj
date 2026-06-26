; SPDX-License-Identifier: EPL-2.0
(ns protomatter.port
  (:require [protomatter.protocols :as p]))

;; Sentinel distinguishing "never posted" from a legitimately posted nil.
;; Used by (all ...) to avoid firing when value ports have not yet received.
(def ^:private unset (Object.))

(defn unset? [v] (identical? v unset))
(defn set?'  [v] (not (unset? v)))

;; Port holds:
;;   current-val — atom, hold-last value (or unset sentinel)
;;   arbiters    — ref, set of attached IArbiter instances
;;
;; arbiters is a ref (not atom) so that attach!/detach! compose with
;; apply-surface-patch! dosync blocks when adding/removing whole cable nodes.
;; post! reads @arbiters outside any transaction — it sees the last committed
;; arbiter set, which is correct: we do not want a recable mid-post.

(deftype Port [path current-val arbiters]
  p/IPort
  (port-path [_] path)
  (post! [this value]
    (reset! current-val value)
    (run! #(p/notify! % this value) @arbiters))
  (attach! [_ arbiter]
    (alter arbiters conj arbiter))
  (detach! [_ arbiter]
    (alter arbiters disj arbiter))
  (current-value [_] @current-val)

  clojure.lang.IDeref
  (deref [this] (p/current-value this)))

(defn make-port
  "Create a port at path. Initial value is the unset sentinel unless supplied."
  ([path]
   (Port. path (atom unset) (ref #{})))
  ([path initial-value]
   (Port. path (atom initial-value) (ref #{}))))

(defn port-set? [port]
  (set?' (p/current-value port)))
