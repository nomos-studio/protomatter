; SPDX-License-Identifier: EPL-2.0
(defproject nomos-studio/protomatter "0.1.0-SNAPSHOT"
  :description "protomatter — port/arbiter/receiver protocol substrate for nomos-studio"
  :url "https://github.com/nomos-studio/protomatter"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]]
  :source-paths ["src"]
  :test-paths   ["test"]
  :target-path  "target/%s"
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "1.5.0"]]}})
