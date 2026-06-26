; SPDX-License-Identifier: EPL-2.0
(ns protomatter.protocols)

;; Three layers: Port → Arbiter → Receiver.
;; Ports never know about receivers directly; arbiters always mediate.
;; (receive port fn) is itself an arbiter — the trivial single-port case
;; is uniform with (any ...) and (all ...).

(defprotocol IPort
  "A named value holder that notifies attached arbiters on post!.
  Holds the most recently posted value (hold-last semantics).
  attach! and detach! must be called within dosync."
  (post!         [port value]   "Post a value; update hold-last; notify all attached arbiters.")
  (attach!       [port arbiter] "Attach an arbiter. Caller must be within dosync.")
  (detach!       [port arbiter] "Detach an arbiter. Caller must be within dosync.")
  (current-value [port]         "Return the most recently posted value, or unset if never posted."))

(defprotocol IArbiter
  "Mediates between one or more ports and a receiver.
  The three arbiter implementations are receive (trivial), any, and all.
  notify! is called by a port when a value is posted to it."
  (notify! [arbiter port value]))

(defprotocol IReceiver
  "Handles a single activation. Always receives one value.
  For (all ...), that value is a tuple assembled by the arbiter."
  (activate! [receiver value]))

(defprotocol INode
  "A named entity in the ctrl-tree: a path, a ports map, and a state atom.
  The state atom is addressable as a ctrl-tree path so that:
  - checkpoints can restore cable state by path
  - conductor arcs can reset state (e.g. phasor sync) via ctrl-write!
  - the REPL can inspect live cable state without instrumentation"
  (node-ports [node] "Return {:in port :out port ...}.")
  (node-state [node] "Return the state atom. nil for stateless nodes.")
  (node-path  [node] "Return the ctrl-tree path vector."))

(defprotocol IMount
  "Projects ctrl-tree path writes to an external protocol.
  Implementations: LocalStmMount, OscMount, KairosIpcMount, DeviceMapMount.
  mount-write! is called after dosync commits (side effect, never inside transaction).
  mount-recable! is called after routing ref changes commit.
  A NullMount captures calls without dispatching — used when a mount subtree
  is present in the table but its target is not yet connected or available."
  (mount-write!   [mount path value]  "Dispatch a value write to this mount's protocol.")
  (mount-recable! [mount changes]     "Dispatch routing changes to this mount's protocol."))
