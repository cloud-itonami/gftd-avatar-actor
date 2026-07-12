(ns avatar.loop-test
  "Full tick! cycle, fully offline: avatar.murakumo/avatar.judge/datalad shell
  calls are all faked via with-redefs, and lease/state/ledger paths are
  redirected into a temp dir so the test never touches this repo's own
  .avatar/ledger/assets state. avatar.generate/parts-cids is also faked so
  the round actually has candidates to submit (see avatar.generate — with no
  AVATAR_PARTS_CIDS configured, round-candidates would return [] and there
  would be nothing here to settle)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.shell :as shell]
            [avatar.loop :as loop]
            [avatar.generate :as generate]
            [avatar.murakumo :as murakumo]
            [avatar.judge :as judge]
            [avatar.ledger :as ledger]
            [avatar.datalad :as datalad]
            [avatar.publisher :as publisher]))

(defn- tmp-dir []
  (let [f (java.io.File/createTempFile "avatar-loop-test" "")]
    (.delete f) (.mkdirs f) f))

(defn- with-tmp-paths [tmp f]
  (binding []
    (with-redefs [loop/lease-path (str tmp "/lease")
                  loop/state-path (str tmp "/state.edn")
                  ledger/default-path (str tmp "/ledger.edn")
                  datalad/*assets-dir* (str tmp "/assets")
                  generate/parts-cids (fn [] ["cid-base" "cid-costume"])]
      (f))))

(def fake-jobs (atom {}))

(defn- fake-submit! [candidate]
  (let [id (str "job-" (:candidate/id candidate))]
    (swap! fake-jobs assoc id {:gen.job/id id :gen.job/status :done
                               :gen.job/artifacts [(str id ".vrm")]})
    {:gen.job/id id}))

(defn- fake-poll [job-id] (get @fake-jobs job-id))

(deftest submit-then-settle-accepts-a-clean-winner
  (let [tmp (tmp-dir)
        pub (publisher/mock-publisher)]
    (with-tmp-paths tmp
      (fn []
        (with-redefs [murakumo/submit! fake-submit!
                      murakumo/poll fake-poll
                      murakumo/fetch-artifact! (fn [_job] (byte-array [1 2 3]))
                      judge/score! (fn [_c _p] {:score 77 :critique "fine"})
                      shell/sh (fn [& _args] {:exit 0 :out "" :err ""})]
          (reset! fake-jobs {})
          (testing "first tick submits a round"
            (is (= :submitted (loop/tick!))))
          (testing "second tick (jobs already :done in the fake) settles it"
            (is (= :settled (loop/tick! pub))))
          (testing "ledger recorded an accepted asset"
            (let [facts (ledger/read-all (str tmp "/ledger.edn"))]
              (is (some #(= :accepted (:t %)) facts))))
          (testing "publisher received the record"
            (is (= 1 (count @(:a pub))))))))))

(deftest no-parts-configured-submits-nothing-not-a-broken-job
  (let [tmp (tmp-dir)]
    (with-tmp-paths tmp
      (fn []
        (with-redefs [generate/parts-cids (fn [] nil)
                      murakumo/submit! (fn [_c] (throw (ex-info "should never be called" {})))]
          (testing "AVATAR_PARTS_CIDS unset -> round-candidates is [] -> tick still reports :submitted but nothing was actually sent"
            (is (= :submitted (loop/tick!)))
            (is (= [] (:pending (loop/load-state))))))))))

(deftest lease-prevents-concurrent-ticks
  (let [tmp (tmp-dir)]
    (with-tmp-paths tmp
      (fn []
        (spit (str tmp "/lease") (str (System/currentTimeMillis)))
        (is (= :lease-held (loop/tick!)))))))

(deftest budget-caps-daily-submissions
  (let [tmp (tmp-dir)]
    (with-tmp-paths tmp
      (fn []
        (with-redefs [murakumo/submit! fake-submit!]
          (spit (str tmp "/state.edn")
                (pr-str {:round 0 :day (str (java.time.LocalDate/now))
                         :submitted-today 999 :pending [] :elite-gene nil
                         :round-started-at nil}))
          (is (= :idle (loop/tick!))))))))
