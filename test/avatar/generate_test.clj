(ns avatar.generate-test
  "avatar.generate is a plain .clj (not .cljc, unlike illust.generate) because
  it needs System/getenv up front — AVATAR_PARTS_CIDS gates whether there is
  anything to compose at all (see avatar.generate's docstring). Every test
  that exercises the 'parts are configured' path with-redefs
  avatar.generate/parts-cids instead of touching real process env."
  (:require [clojure.test :refer [deftest testing is]]
            [avatar.generate :as generate]))

(def persona {:tags ["network-isekai"]})

(defn- with-parts [refs f]
  (with-redefs [generate/parts-cids (fn [] refs)]
    (f)))

(deftest round-candidates-is-empty-when-no-parts-configured
  (testing "AVATAR_PARTS_CIDS unset/blank -> 0 candidates, never a broken submit"
    (with-parts nil
      (fn [] (is (= [] (generate/round-candidates persona 0 3)))))))

(deftest round-candidates-is-pure-and-reproducible
  (with-parts ["cid-base" "cid-costume"]
    (fn []
      (is (= (generate/round-candidates persona 3 3) (generate/round-candidates persona 3 3))))))

(deftest round-candidates-count-matches-k
  (with-parts ["cid-base" "cid-costume"]
    (fn [] (is (= 5 (count (generate/round-candidates persona 0 5)))))))

(deftest round-candidates-ids-are-unique-within-a-round
  (with-parts ["cid-base" "cid-costume"]
    (fn []
      (let [cs (generate/round-candidates persona 2 4)]
        (is (= 4 (count (distinct (map :candidate/id cs)))))))))

(deftest round-candidates-prompt-includes-persona-tags
  (with-parts ["cid-base" "cid-costume"]
    (fn []
      (doseq [c (generate/round-candidates persona 0 3)]
        (is (re-find #"network-isekai" (:prompt c)))))))

(deftest round-candidates-params-carry-the-configured-refs
  (with-parts ["cid-base" "cid-costume"]
    (fn []
      (doseq [c (generate/round-candidates persona 0 3)]
        (is (= ["cid-base" "cid-costume"] (get-in c [:params :refs])))))))

(deftest different-rounds-vary-the-gene-selection
  (with-parts ["cid-base" "cid-costume"]
    (fn []
      (let [r0 (map :gene (generate/round-candidates persona 0 3))
            r1 (map :gene (generate/round-candidates persona 1 3))]
        (is (not= r0 r1))))))
