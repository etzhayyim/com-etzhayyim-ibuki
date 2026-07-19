(ns ibuki.methods.host-capabilities
  "Babashka host providers for Ibuki's explicit outward capabilities."
  (:require [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]))

(defn pds-transport [url body headers]
  (let [opts {:headers (merge {"Content-Type" "application/json"} headers)
              :timeout 20000}
        response (if (nil? body)
                   (http/get (str url) opts)
                   (http/post (str url) (assoc opts :body (json/generate-string body))))]
    (json/parse-string (:body response))))

(defn perception-fetch [url]
  (let [response (http/get (str url) {:headers {"Accept" "application/json"}
                                      :timeout 5000})]
    (json/parse-string (:body response))))

(defn murakumo-http-post [endpoint body-map timeout-s]
  (let [response (http/post (str endpoint)
                            {:headers {"Content-Type" "application/json"}
                             :body (json/generate-string body-map)
                             :timeout (long (* 1000 (double timeout-s)))})]
    (json/parse-string (:body response) true)))

(defn kotoba-http-post [url body-map headers timeout-s]
  (let [response (http/post (str url)
                            {:headers headers
                             :body (json/generate-string body-map)
                             :timeout (long (* 1000 (double timeout-s)))
                             :throw false})
        status (:status response)]
    (if (<= 200 status 299)
      (json/parse-string (:body response) true)
      (let [body (str (:body response))]
        (throw (ex-info (str "kotoba transact HTTP " status ": "
                             (subs body 0 (min 200 (count body))))
                        {:ibuki/kotoba-transact-http-error true :status status}))))))

(defn gh-state [pr]
  (let [child (process/process ["gh" "pr" "view" (str pr) "--json" "state"]
                               {:out :string :err :string})
        result (deref child 30000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info (str "gh pr view " pr " timed out after 30s") {:pr pr})))
    (when-not (zero? (:exit result))
      (throw (ex-info (str "gh pr view " pr " failed: " (:err result))
                      {:pr pr :exit (:exit result)})))
    (get (json/parse-string (:out result)) "state")))
