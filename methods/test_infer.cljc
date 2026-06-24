(ns ibuki.methods.test-infer
  "ibuki 息吹 — Murakumo-only narration. ADR-2606101200 + ADR-2605215000.
  Port of methods/test_infer.py (every Python assertion, 1:1) + the injectable
  HTTP-fn contract (the stubbed live path, no gateway required)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.infer :as infer]))

(defmacro murakumo-thrown?
  "Asserts a MurakumoOnlyViolation whose message contains \"Murakumo\"
  (the expect_raises(…, contains=\"Murakumo\") parity)."
  [& body]
  `(let [e# (try ~@body nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e# e#))]
     (is (some? e#) "expected MurakumoOnlyViolation, none raised")
     (when e#
       (is (infer/murakumo-only-violation? e#))
       (is (str/includes? (ex-message e#) "Murakumo")
           (str "expected 'Murakumo' in '" (ex-message e#) "'")))))

(deftest allowlist-is-murakumo-fleet-only
  (doseq [host infer/murakumo-allowed-hosts]
    (is (contains? #{"127.0.0.1" "localhost" "192.168.1.70"} ;; loopback + EVO-X2 LAN
                   (first (str/split host #":")))
        (str host " is not loopback/EVO-X2"))))

(deftest commercial-endpoints-unrepresentable
  (doseq [bad ["https://api.openai.com/v1/chat/completions"
               "https://api.anthropic.com/v1/messages"
               "https://bedrock-runtime.us-east-1.amazonaws.com/model/x/invoke"
               "https://api.runpod.ai/v2/x/run"
               "http://203.0.113.7:4000/v1/chat/completions"]]
    (murakumo-thrown? (infer/assert-murakumo bad))))

(deftest https-to-allowed-host-still-refused
  ;; only the documented http fleet endpoints — a lookalike scheme is not the fleet
  (murakumo-thrown? (infer/assert-murakumo "https://127.0.0.1:4000/v1")))

(deftest default-endpoint-is-litellm-loopback
  (is (str/starts-with? infer/default-endpoint "http://127.0.0.1:4000"))
  (is (nil? (infer/assert-murakumo infer/default-endpoint))))

(deftest template-deterministic
  (let [a (infer/template-narrate "Live cattle stewardship" "10101500" "calm" "recordAnalysis")
        b (infer/template-narrate "Live cattle stewardship" "10101500" "calm" "recordAnalysis")]
    (is (= a b))
    (is (str/includes? a "mirror, not advice"))))

(deftest narrate-offline-uses-template
  ;; the env-pop parity: :live false ≡ IBUKI_MURAKUMO_LIVE unset
  (let [out (infer/narrate "Cereal grains provisioning" "50221000" "neutral" "recordAnalysis"
                           {:live false})]
    (is (= "template" (:via out)))
    (is (str/includes? (:text out) "50221000"))))

(deftest narrate-refuses-bad-endpoint-even-when-live
  (murakumo-thrown?
   (infer/narrate "t" "c" "calm" "recordAnalysis"
                  {:live true
                   :endpoint "https://api.openai.com/v1/chat/completions"})))

(deftest narrate-live-fails-open-to-template
  ;; Live gate set but (normally) no Murakumo gateway listening: the organism
  ;; must keep living offline (fail-open), not crash.
  (let [out (infer/narrate "t" "c" "calm" "recordAnalysis"
                           {:live true
                            :endpoint "http://127.0.0.1:4000/v1/chat/completions"
                            :timeout-s 0.2})]
    (is (contains? #{"template" "murakumo"} (:via out))) ;; murakumo only if a real fleet is up
    (is (seq (:text out)))))

;; ── the injectable HTTP-fn contract (Clojure-specific, no live gateway) ───

(deftest narrate-live-via-stubbed-http
  (let [seen (atom nil)
        stub (fn [endpoint body timeout-s]
               (reset! seen {:endpoint endpoint :body body :timeout-s timeout-s})
               {:choices [{:message {:content "  live words  "}}]})
        out (infer/narrate "t" "c" "calm" "recordAnalysis"
                           {:live true :http-post stub})]
    (is (= {:text "live words" :via "murakumo"} out))
    (is (= infer/default-endpoint (:endpoint @seen)))
    (is (= infer/default-model (get-in @seen [:body :model])))
    (is (= 120 (get-in @seen [:body :max_tokens])))))

(deftest narrate-blank-completion-falls-open
  (let [stub (fn [_ _ _] {:choices [{:message {:content "   "}}]})
        out (infer/narrate "t" "c" "calm" "recordAnalysis"
                           {:live true :http-post stub})]
    (is (= "template" (:via out)))))

(deftest infer-text-same-discipline
  ;; offline → the deterministic fallback
  (is (= {:text "fb" :via "template"} (infer/infer-text "p" "fb" {:live false})))
  ;; allowlist enforced BEFORE the env gate
  (murakumo-thrown?
   (infer/infer-text "p" "fb" {:live true
                               :endpoint "https://api.openai.com/v1/chat/completions"}))
  ;; live + no gateway → fail-open
  (let [out (infer/infer-text "p" "fb" {:live true :timeout-s 0.2})]
    (is (contains? #{"template" "murakumo"} (:via out)))
    (is (seq (:text out))))
  ;; stubbed live path → murakumo
  (let [stub (fn [_ body _]
               (is (= 200 (:max_tokens body)))
               {:choices [{:message {:content "reasoned"}}]})]
    (is (= {:text "reasoned" :via "murakumo"}
           (infer/infer-text "p" "fb" {:live true :http-post stub}))))
  ;; a throwing gateway → fail-open to the fallback
  (let [stub (fn [_ _ _] (throw (ex-info "gateway down" {})))]
    (is (= {:text "fb" :via "template"}
           (infer/infer-text "p" "fb" {:live true :http-post stub})))))
