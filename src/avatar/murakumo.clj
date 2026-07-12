(ns avatar.murakumo
  "JVM I/O edge: submit/poll generation jobs against the REAL murakumo
  distributed generation backend (ADR-2607122200 §3), reusing
  `cloud-murakumo.gen`/`cloud-murakumo.queue-kotoba` verbatim (sibling
  gftdcojp repo, `:local/root` dep — see deps.edn) rather than re-deriving
  the job-normalization / kotoba-queue wire protocol here.

  HONEST LIMIT: this actor only SUBMITS jobs onto the `gftd-murakumo` kotoba
  graph and POLLS for completion — it does not itself run GPU inference. A
  murakumo fleet worker (`clojure -M:worker --kotoba-url https://kotobase.net
  --kotoba-graph gftd-murakumo`, running on a Mac-mini / `gad` node) has to be
  up and consuming that queue for jobs to ever leave :queued. Same
  operational dependency ai-gftd-apex already has on cloud-murakumo.

  SECOND HONEST LIMIT (avatar-specific, see avatar.generate): unlike the
  :image actor, `:vrm-compose`/`:kisekae` is not text-to-X — it composites a
  set of INPUT reference CIDs (base body + costume/material parts) into an
  exported VRM. `submit!` passes those refs straight through to
  `cloud-murakumo.gen/job`'s `:refs` request key (already supported there,
  unmodified) — it does not resolve or validate them itself."
  (:require [cloud-murakumo.spec :as spec]
            [cloud-murakumo.gen :as gen]
            [cloud-murakumo.queue-kotoba :as qk])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def modality :vrm-compose)
(def actor-id "gftd-avatar-actor")

(defn function
  "This actor's murakumo.edn `:apps :generation` function entry (SSoT stays
  in cloud-murakumo, we just look it up — never hardcode the model list)."
  []
  (or (gen/fn-for-modality (spec/functions (spec/load-spec)) modality)
      (throw (ex-info "no :vrm-compose generation function in murakumo.edn" {:modality modality}))))

(defn- kotoba-url [] (or (System/getenv "MURAKUMO_KOTOBA_URL") "https://kotobase.net"))
(defn- kotoba-graph [] (or (System/getenv "MURAKUMO_KOTOBA_GRAPH") "gftd-murakumo"))

(defn conn
  "queue-kotoba connection map. Auth: MURAKUMO_KOTOBA_TOKEN (bearer, the
  simplest operational path) preferred; falls back to a CACAO seed
  (MURAKUMO_KOTOBA_SEED) if the operator has wired the `kotoba` CLI's
  did-derive/cacao-sign path instead (cloud_murakumo.queue-kotoba/fresh-cacao)."
  []
  (qk/conn (kotoba-url) (kotoba-graph)
           (cond-> {}
             (System/getenv "MURAKUMO_KOTOBA_TOKEN")
             (assoc :token (System/getenv "MURAKUMO_KOTOBA_TOKEN"))
             (System/getenv "MURAKUMO_KOTOBA_SEED")
             (assoc :seed (System/getenv "MURAKUMO_KOTOBA_SEED")))))

(defn submit!
  "candidate ({:prompt :params}, `:params` carries :refs — see
  avatar.generate) -> the enqueued :gen.job map (has :gen.job/id). :refs is
  passed through to cloud-murakumo.gen/job's request map unmodified.
  http-fn injected (default real HTTP) so callers can fake this offline in
  tests."
  ([candidate] (submit! (function) (conn) candidate {}))
  ([f c {:keys [prompt params]} {:keys [http-fn]}]
   (let [job (assoc (gen/job f {:prompt prompt :refs (:refs params) :params params} actor-id)
                    :gen.job/id (str (random-uuid)))]
     (qk/enqueue! (cond-> {} http-fn (assoc :http-fn http-fn)) c job {}))))

(defn poll
  "job-id -> the current job map (nil if not found yet — a submit! + poll in
  the same tick can race the kotoba write, callers must tolerate nil)."
  ([job-id] (poll (conn) job-id {}))
  ([c job-id {:keys [http-fn]}]
   (qk/job-by-id (cond-> {} http-fn (assoc :http-fn http-fn)) c job-id)))

(defn done? [job] (= :done (:gen.job/status job)))
(defn failed? [job] (= :failed (:gen.job/status job)))

;; ── artifact fetch ───────────────────────────────────────────────────────

(defn jvm-http-get
  "Plain GET, returns {:status :body-bytes}. Used for both http(s) artifact
  URLs and the kotoba CID gateway fallback below."
  [url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 120))
                (.GET)
                .build)
        resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp) :body-bytes (.body resp)}))

(defn artifact-url
  "One `:gen.job/artifacts` entry -> a fetchable URL. If it's already an
  http(s) URL, use it as-is. Otherwise treat it as a kotoba CID and resolve
  it against a gateway — HONEST LIMIT: this CID->URL convention
  (KOTOBASE_ARTIFACT_BASE_URL, default `<kotoba-url>/ipfs/`) is a best-effort
  guess, not a confirmed contract with murakumo ops; correct it via the env
  var if wrong, no code change needed."
  [artifact]
  (if (re-find #"^https?://" artifact)
    artifact
    (str (or (System/getenv "KOTOBASE_ARTIFACT_BASE_URL") (str (kotoba-url) "/ipfs/"))
         artifact)))

(defn fetch-artifact!
  "First artifact of a :done job -> raw bytes, or nil if none / fetch failed.
  get-fn injected for offline tests."
  ([job] (fetch-artifact! job jvm-http-get))
  ([job get-fn]
   (when-let [a (first (:gen.job/artifacts job))]
     (try
       (let [{:keys [status body-bytes]} (get-fn (artifact-url a))]
         (when (= 200 status) body-bytes))
       (catch Exception _ nil)))))
