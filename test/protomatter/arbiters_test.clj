; SPDX-License-Identifier: EPL-2.0
(ns protomatter.arbiters-test
  (:require [clojure.test :refer [deftest is testing]]
            [protomatter.protocols :as p]
            [protomatter.port :as port]
            [protomatter.arbiters :as arb]))

(defn- capturing-receiver []
  (let [received (atom [])]
    {:received received
     :receiver (reify p/IReceiver
                 (activate! [_ v] (swap! received conj v)))}))

;; --- receive ---

(deftest receive-fires-on-post
  (let [{:keys [received receiver]} (capturing-receiver)
        pt (port/make-port [:test :a])]
    (arb/receive pt receiver)
    (p/post! pt :hello)
    (is (= [:hello] @received))))

(deftest receive-fires-on-every-post
  (let [{:keys [received receiver]} (capturing-receiver)
        pt (port/make-port [:test :a])]
    (arb/receive pt receiver)
    (p/post! pt :x)
    (p/post! pt :y)
    (p/post! pt :z)
    (is (= [:x :y :z] @received))))

;; --- any ---

(deftest any-fires-on-first-port
  (let [{:keys [received receiver]} (capturing-receiver)
        p1 (port/make-port [:test :p1])
        p2 (port/make-port [:test :p2])]
    (arb/any [p1 p2] receiver)
    (p/post! p1 :from-p1)
    (is (= [:from-p1] @received))))

(deftest any-fires-on-each-port-independently
  (let [{:keys [received receiver]} (capturing-receiver)
        p1 (port/make-port [:test :p1])
        p2 (port/make-port [:test :p2])]
    (arb/any [p1 p2] receiver)
    (p/post! p1 :a)
    (p/post! p2 :b)
    (p/post! p1 :c)
    (is (= [:a :b :c] @received))))

;; --- all ---

(deftest all-does-not-fire-before-value-ports-set
  (let [{:keys [received receiver]} (capturing-receiver)
        gate (port/make-port [:test :gate])
        vp   (port/make-port [:test :v])]
    (arb/all gate [vp] receiver)
    (p/post! gate :beat)
    (is (= [] @received) "gate fired before value port — should not activate")))

(deftest all-fires-when-gate-fires-and-values-present
  (let [{:keys [received receiver]} (capturing-receiver)
        gate (port/make-port [:test :gate])
        vp1  (port/make-port [:test :v1])
        vp2  (port/make-port [:test :v2])]
    (arb/all gate [vp1 vp2] receiver)
    (p/post! vp1 :a)
    (p/post! vp2 :b)
    (p/post! gate :beat)
    (is (= [[:beat :a :b]] @received))))

(deftest all-samples-most-recent-value
  (testing "intermediate updates collapse; only the most recent is delivered"
    (let [{:keys [received receiver]} (capturing-receiver)
          gate (port/make-port [:test :gate])
          vp   (port/make-port [:test :v])]
      (arb/all gate [vp] receiver)
      (p/post! vp :first)
      (p/post! vp :second)
      (p/post! vp :third)
      (p/post! gate :beat)
      (is (= [[:beat :third]] @received)))))

(deftest all-fires-on-each-gate-post
  (let [{:keys [received receiver]} (capturing-receiver)
        gate (port/make-port [:test :gate])
        vp   (port/make-port [:test :v])]
    (arb/all gate [vp] receiver)
    (p/post! vp :val)
    (p/post! gate :beat-1)
    (p/post! gate :beat-2)
    (is (= [[:beat-1 :val] [:beat-2 :val]] @received)
        "each gate firing samples current value")))

(deftest all-partial-value-ports-do-not-fire
  (testing "gate fires but only some value ports have values"
    (let [{:keys [received receiver]} (capturing-receiver)
          gate (port/make-port [:test :gate])
          vp1  (port/make-port [:test :v1])
          vp2  (port/make-port [:test :v2])]
      (arb/all gate [vp1 vp2] receiver)
      (p/post! vp1 :only-one)
      (p/post! gate :beat)
      (is (= [] @received) "vp2 unset — should not fire"))))
