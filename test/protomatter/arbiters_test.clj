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

;; --- interleave-arb ---

(deftest interleave-concurrent-branch-fires
  (let [{:keys [received receiver]} (capturing-receiver)
        cp  (port/make-port [:test :concurrent])
        ep  (port/make-port [:test :exclusive])
        tdp (port/make-port [:test :teardown])
        {td-rcvr :receiver} (capturing-receiver)]
    (arb/interleave-arb [[cp receiver]] [[ep receiver]] [tdp td-rcvr])
    (p/post! cp :from-concurrent)
    (is (= [:from-concurrent] @received))))

(deftest interleave-exclusive-branch-fires
  (let [{:keys [received receiver]} (capturing-receiver)
        cp  (port/make-port [:test :concurrent])
        ep  (port/make-port [:test :exclusive])
        tdp (port/make-port [:test :teardown])
        {td-rcvr :receiver} (capturing-receiver)]
    (arb/interleave-arb [[cp receiver]] [[ep receiver]] [tdp td-rcvr])
    (p/post! ep :from-exclusive)
    (is (= [:from-exclusive] @received))))

(deftest teardown-fires-teardown-receiver
  (let [{:keys [received receiver]} (capturing-receiver)
        cp  (port/make-port [:test :concurrent])
        ep  (port/make-port [:test :exclusive])
        tdp (port/make-port [:test :teardown])
        {td-received :received td-rcvr :receiver} (capturing-receiver)]
    (arb/interleave-arb [[cp receiver]] [[ep receiver]] [tdp td-rcvr])
    (p/post! tdp :beat)
    (is (= [:beat] @td-received) "teardown receiver fires with teardown port value")))

(deftest teardown-is-total-concurrent-stops
  (testing "after teardown, posts to concurrent port are ignored"
    (let [{:keys [received receiver]} (capturing-receiver)
          cp  (port/make-port [:test :concurrent])
          ep  (port/make-port [:test :exclusive])
          tdp (port/make-port [:test :teardown])
          {td-rcvr :receiver} (capturing-receiver)]
      (arb/interleave-arb [[cp receiver]] [[ep receiver]] [tdp td-rcvr])
      (p/post! cp :before)
      (p/post! tdp :tear-it-down)
      (p/post! cp :after)
      (is (= [:before] @received)
          "concurrent receiver stops after teardown"))))

(deftest teardown-is-total-exclusive-stops
  (testing "after teardown, posts to exclusive port are ignored"
    (let [{:keys [received receiver]} (capturing-receiver)
          cp  (port/make-port [:test :concurrent])
          ep  (port/make-port [:test :exclusive])
          tdp (port/make-port [:test :teardown])
          {td-rcvr :receiver} (capturing-receiver)]
      (arb/interleave-arb [[cp receiver]] [[ep receiver]] [tdp td-rcvr])
      (p/post! ep :before)
      (p/post! tdp :tear-it-down)
      (p/post! ep :after)
      (is (= [:before] @received)
          "exclusive receiver stops after teardown"))))

(deftest teardown-is-one-shot
  (testing "posting to teardown port twice fires teardown receiver exactly once"
    (let [cp  (port/make-port [:test :concurrent])
          ep  (port/make-port [:test :exclusive])
          tdp (port/make-port [:test :teardown])
          {td-received :received td-rcvr :receiver} (capturing-receiver)]
      (arb/interleave-arb [[cp (reify p/IReceiver (activate! [_ _]))]]
                           [[ep (reify p/IReceiver (activate! [_ _]))]]
                           [tdp td-rcvr])
      (p/post! tdp :first-tear)
      (p/post! tdp :second-tear)
      (is (= [:first-tear] @td-received)
          "teardown receiver fires exactly once"))))

(deftest teardown-receiver-can-establish-new-interleave
  (testing "teardown handler creates new interleave on same port — patch transition"
    (let [cp       (port/make-port [:test :concurrent])
          tdp      (port/make-port [:test :teardown])
          log      (atom [])
          new-rcvr (reify p/IReceiver
                     (activate! [_ v] (swap! log conj [:new v])))
          td-rcvr  (reify p/IReceiver
                     (activate! [_ _]
                       ;; Last act: establish new interleave on the same ports
                       (arb/interleave-arb [[cp new-rcvr]] [] [tdp (reify p/IReceiver (activate! [_ _]))])))]
      (arb/interleave-arb [[cp (reify p/IReceiver (activate! [_ v] (swap! log conj [:old v])))]]
                           []
                           [tdp td-rcvr])
      (p/post! cp :old-patch-work)
      (p/post! tdp :transition)
      (p/post! cp :new-patch-work)
      (is (= [[:old :old-patch-work] [:new :new-patch-work]] @log)
          "old interleave fires before teardown, new interleave fires after"))))
