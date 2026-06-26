; SPDX-License-Identifier: EPL-2.0
(ns protomatter.arbiters
  (:require [protomatter.protocols :as p]
            [protomatter.port :as port]))

;; (receive port receiver)
;; Trivial single-port arbiter. Fires receiver immediately on any value.
;; This is the degenerate case of the arbiter model — a cable's step-function
;; receiver is bound via (receive ...), not wired directly to the port.

(deftype ReceiveArbiter [receiver]
  p/IArbiter
  (notify! [_ _port value]
    (p/activate! receiver value)))

(defn receive
  "Bind receiver to port via a trivial arbiter. Returns the arbiter."
  [port receiver]
  (let [arb (ReceiveArbiter. receiver)]
    (dosync (p/attach! port arb))
    arb))

;; (any ports receiver)
;; Fires receiver with the value from whichever port fires first.
;; Attaches to all listed ports; the first notify! wins each time.
;; The receiver gets a single value — the value that arrived.
;;
;; Note: if multiple ports fire "simultaneously" (within the same tick),
;; activation order is the order of post! calls. No special de-dup.
;; This matches the CCR Choice / CML choose semantics for musical use.

(deftype AnyArbiter [receiver]
  p/IArbiter
  (notify! [_ _port value]
    (p/activate! receiver value)))

(defn any
  "Bind receiver to fire on the first value from any of ports. Returns the arbiter."
  [ports receiver]
  (let [arb (AnyArbiter. receiver)]
    (dosync (run! #(p/attach! % arb) ports))
    arb))

;; (all gate-port value-ports receiver)
;; Sample-on-fire semantics: when gate-port fires, sample current-value
;; from each value-port and activate receiver with [gate-val v1 v2 ...].
;; Does not fire if any value-port has never received (unset sentinel check).
;;
;; The all arbiter attaches only to gate-port. Value ports are sampled
;; directly via current-value (hold-last) at gate fire time — they do not
;; need to notify the arbiter. This is deliberate: value-port updates
;; collapse naturally between gate firings; only the gate triggers delivery.
;;
;; Strict rendezvous (fire only when all ports have posted since last
;; activation) is a different semantic and a separate arbiter type — TODO.
;;
;; (all beat-port value-ports receiver) is the beat-synchronous join:
;; beat-port is the conductor beat port; value ports carry parameter
;; and modulation state. The receiver gets a consistent snapshot of all
;; inputs at the beat boundary.

(deftype AllArbiter [gate-port value-ports receiver]
  p/IArbiter
  (notify! [_ firing-port gate-val]
    (when (identical? firing-port gate-port)
      (let [vals (mapv p/current-value value-ports)]
        (when (every? port/set?' vals)
          (p/activate! receiver (into [gate-val] vals)))))))

(defn all
  "Sample-on-fire join. Fires receiver with [gate-val v1 v2 ...] when
  gate-port fires and all value-ports have at least one posted value.
  Returns the arbiter."
  [gate-port value-ports receiver]
  (let [arb (AllArbiter. gate-port value-ports receiver)]
    (dosync (p/attach! gate-port arb))
    arb))

;; (interleave-arb concurrent-pairs exclusive-pairs teardown-pair)
;;
;; Coordinates three branches of work for a single patch configuration:
;;
;;   concurrent  — activations that may run in parallel with each other
;;   exclusive   — activations that must serialise with each other
;;   teardown    — fires once; tears down the ENTIRE interleave atomically;
;;                 the teardown receiver may, as its last act, construct a
;;                 new interleave arbiter for the incoming patch
;;
;; The interleave arbiter is per-patch, not long-lived infrastructure.
;; When teardown fires:
;;   1. state transitions :active → :done in dosync
;;   2. this arbiter is detached from ALL ports (concurrent + exclusive +
;;      teardown) in the same dosync — the patch is atomically gone
;;   3. teardown receiver activates post-dosync with the teardown port value
;;   4. teardown receiver constructs new interleave for the new patch
;;
;; This gives "strongly after" sequencing: the new patch cannot begin
;; computing until the old interleave has been completely torn down.
;; The teardown port is posted to deliberately (e.g. by a conductor arc
;; on the beat), giving beat-aligned, properly sequenced patch transitions.
;;
;; concurrent vs exclusive — the ordering distinction between these two
;; branches with respect to each other becomes meaningful when cable
;; computation is parallelised across threads. At single-threaded nous-rate
;; both branches behave identically; the structural distinction is recorded
;; now so that the parallel dispatch path can honour it later.

(defn- find-receiver [pairs port]
  (some (fn [[p r]] (when (identical? p port) r)) pairs))

(deftype InterleaveArbiter
  [concurrent-pairs   ;; [[port receiver] ...]
   exclusive-pairs    ;; [[port receiver] ...]
   teardown-pair      ;; [port receiver]
   all-ports          ;; set of all ports — for atomic detach-all
   state]             ;; ref: :active | :done

  p/IArbiter
  (notify! [this firing-port value]
    ;; Determine what to do inside dosync, then act outside it.
    ;; Three outcomes:
    ;;   [:teardown receiver] — transition to :done, detach all, run receiver
    ;;   [:branch   receiver] — normal activation, run receiver
    ;;   nil                  — state is :done, ignore
    (let [outcome
          (dosync
            (when (= :active @state)
              (let [[td-port td-rcvr] teardown-pair]
                (if (identical? firing-port td-port)
                  (do
                    (ref-set state :done)
                    (run! #(p/detach! % this) all-ports)
                    [:teardown td-rcvr])
                  (when-let [r (or (find-receiver concurrent-pairs firing-port)
                                   (find-receiver exclusive-pairs firing-port))]
                    [:branch r])))))]
      (when-let [[_ r] outcome]
        (p/activate! r value)))))

(defn interleave-arb
  "Coordinate three branches of work for one patch configuration.
  concurrent-pairs: [[port receiver] ...]
  exclusive-pairs:  [[port receiver] ...]
  teardown-pair:    [port receiver]  — fires once; tears down entire interleave;
                                       receiver may construct new interleave as last act.
  Returns the arbiter."
  [concurrent-pairs exclusive-pairs teardown-pair]
  (let [all-ports (into #{} (map first)
                        (concat concurrent-pairs exclusive-pairs [teardown-pair]))
        arb       (InterleaveArbiter. concurrent-pairs exclusive-pairs teardown-pair
                                      all-ports (ref :active))]
    (dosync (run! #(p/attach! % arb) all-ports))
    arb))
