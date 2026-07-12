(ns avatar.generate
  "Pure(ish) candidate builder for one co-scientist round (ADR-2607122200
  §2/§3) — a PLAIN `.clj` (not `.cljc`, unlike illust.generate), because
  unlike `:image`'s pure text-prompt generation, `:vrm-compose`/`:kisekae`
  needs `System/getenv` up front (see below) and there is no JVM-free runtime
  path for that in this repo yet (same judgment illust's own persona.cljc ->
  persona.clj made once a JVM-only call was needed — root CLAUDE.md
  `.cljc`/`.kotoba` runtime-priority note).

  STRUCTURAL DIFFERENCE FROM illust.generate (read this before touching
  anything below): `:image` is text-to-image — a prompt alone is a complete
  generation request. `:vrm-compose` is NOT text-to-X: per cloud-murakumo's
  murakumo.edn (`:apps :generation :vrm-compose`), kisekae is a Mac-mini
  compositor that reconnects skin/mesh, applies material/expression, and
  exports a VRM from a set of EXISTING INPUT reference CIDs (a base body +
  costume/material parts) — it cannot compose an avatar out of nothing.
  So instead of varying subject/style/lighting TEXT, this gene pool varies
  the COMPOSE CONFIG (expression preset / material variant / accessory
  on-or-off), and every candidate's `:params` carries `:refs` — the input
  CIDs to composite — resolved once per call from the `AVATAR_PARTS_CIDS` env
  var (comma-separated CID string). If that env var is unset or blank,
  `round-candidates` returns an EMPTY vector (0 candidates) rather than
  submitting a job with no parts to compose — see the HONEST LIMIT in
  README.md. There is no automated pipeline yet feeding this actor parts
  from other actors (e.g. gftd-sculpt-actor's own accepted output) — that
  hand-off is explicit follow-up, not wired here."
  (:require [clojure.string :as str]))

(def gene-pool
  {:expression ["neutral" "gentle-smile" "focused"]
   :material-variant ["matte-cloth" "weathered" "clean-formal"]
   :accessory ["none" "shoulder-bag" "scarf"]})

(defn- pick [xs seed n] (nth xs (mod (+ seed n) (count xs))))

(defn- gene-for
  "One candidate's compose-config gene map. `bias` (from
  avatar.cosci/evolve-round's elite, or nil on round 0) pins ONE
  randomly-chosen slot to the prior winner's value instead of round-robining
  it — elitism without literal crossover machinery, honest about being a
  small closed pool rather than a genuine genetic search."
  [round i bias]
  (let [raw {:expression        (pick (:expression gene-pool) round i)
             :material-variant  (pick (:material-variant gene-pool) round (+ i 1))
             :accessory         (pick (:accessory gene-pool) round (+ i 2))}]
    (if (and bias (pos? round) (zero? (mod (+ round i) 3)))
      (merge raw (select-keys bias [(nth [:expression :material-variant :accessory] (mod round 3))]))
      raw)))

(defn parts-cids
  "AVATAR_PARTS_CIDS env (comma-separated CID string) -> a vector of CIDs, or
  nil if unset/blank. Deliberately a standalone (non-private) wrapper around
  `System/getenv` — not inlined into round-candidates — so tests can
  `with-redefs` it instead of mutating real process env to exercise the
  'no parts configured -> 0 candidates' path deterministically."
  []
  (some-> (System/getenv "AVATAR_PARTS_CIDS")
          str/trim
          not-empty
          (str/split #",")
          (->> (map str/trim) (remove str/blank?) vec)
          not-empty))

(defn round-candidates
  "persona + round n (0-based) + k candidates + optional elite bias
  -> [{:candidate/id :prompt :gene :params} ...], or [] if AVATAR_PARTS_CIDS
  is unset/blank (see namespace docstring — this actor never submits a
  compose job with no input refs)."
  ([persona n k] (round-candidates persona n k nil))
  ([{:keys [tags]} n k bias]
   (if-let [refs (parts-cids)]
     (vec
      (for [i (range k)]
        (let [{:keys [expression material-variant accessory]} (gene-for n i bias)]
          {:candidate/id (str "r" n "-c" i)
           :prompt (str/join ", " (concat [(str "expression:" expression)
                                            (str "material:" material-variant)
                                            (str "accessory:" accessory)]
                                           tags))
           :gene {:expression expression :material-variant material-variant :accessory accessory}
           :params {:refs refs}})))
     [])))
