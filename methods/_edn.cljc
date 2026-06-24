(ns ibuki.methods._edn
  "Minimal EDN reader (subset: [] {} :kw \"str\" num bool nil) — 1:1 Clojure port of
  20-actors/ibuki/methods/_edn.py (itself ported from shionome/ake/noroshi).

  Keeps keywords as \":ns/name\" STRINGS (Python parity) so the seed + the append-only log
  read back as the byte-for-byte shapes `_edn.py` produced. Stdlib only; file I/O only
  behind #?(:clj …). Used by datoms/autorun to read the seed + the log without a dependency,
  mirroring the other actors' parsers for parity.

  Quoted strings: `datoms.py` serializes strings with `json.dumps`, so `_edn.py` reverses
  them with `json.loads` (the exact inverse — \\n / \\t / \\\" / \\\\ escapes). This port
  mirrors that JSON-string decode, falling back (like the Python `except ValueError`) to a
  minimal \\\" / \\\\ unescape for any non-JSON-shaped quoted token in a hand-authored file."
  (:require [clojure.string :as str]))

;; ── tokenizer ─────────────────────────────────────────────────────────────────
;; Python: re.compile(r'[\s,]+|;[^\n]*|(\[|\]|\{|\}|"(?:\\.|[^"\\])*"|[^\s,\[\]{}]+)')
;; Whitespace/commas and comments are separators (group 1 nil → skipped); the captured
;; group is a real token.
(def ^:private token-re
  #"[\s,]+|;[^\n]*|(\[|\]|\{|\}|\"(?:\\.|[^\"\\])*\"|[^\s,\[\]{}]+)")

(defn- tokens
  "Yield only the captured group-1 tokens (Python `_tokens`: `if t is not None: yield t`)."
  [s]
  (->> (re-seq token-re s)
       (keep (fn [m] (when (vector? m) (second m))))))

(defn- json-unescape
  "Reverse a JSON string body's escapes (the `json.loads` path of `_atom`): \\n \\t \\r \\\"
  \\\\ \\/ \\b \\f and \\uXXXX. `body` is the token WITHOUT its surrounding quotes."
  [^String body]
  (let [n (count body)
        sb (StringBuilder.)]
    (loop [i 0]
      (if (>= i n)
        (.toString sb)
        (let [c (.charAt body i)]
          (if (and (= c \\) (< (inc i) n))
            (let [e (.charAt body (inc i))]
              (case e
                \n (do (.append sb \newline) (recur (+ i 2)))
                \t (do (.append sb \tab) (recur (+ i 2)))
                \r (do (.append sb \return) (recur (+ i 2)))
                \b (do (.append sb \backspace) (recur (+ i 2)))
                \f (do (.append sb \formfeed) (recur (+ i 2)))
                \" (do (.append sb \") (recur (+ i 2)))
                \\ (do (.append sb \\) (recur (+ i 2)))
                \/ (do (.append sb \/) (recur (+ i 2)))
                \u (if (<= (+ i 6) n)
                     (let [hex (subs body (+ i 2) (+ i 6))]
                       (.append sb (char #?(:clj (Integer/parseInt hex 16)
                                            :cljs (js/parseInt hex 16))))
                       (recur (+ i 6)))
                     (do (.append sb c) (recur (inc i))))
                (do (.append sb c) (recur (inc i)))))
            (do (.append sb c) (recur (inc i)))))))))

(defn- atom*
  "Parse one atom token (Python `_atom`)."
  [^String t]
  (cond
    (str/starts-with? t "\"")
    ;; json.loads is the exact inverse of datoms.py's json.dumps; fall back to the minimal
    ;; \" / \\ unescape for any non-JSON-shaped quoted token (Python: except ValueError).
    (let [body (subs t 1 (dec (count t)))]
      (try (json-unescape body)
           (catch #?(:clj Exception :cljs js/Error) _
             (-> body
                 (str/replace "\\\"" "\"")
                 (str/replace "\\\\" "\\")))))
    (= t "true")  true
    (= t "false") false
    (= t "nil")   nil
    (str/starts-with? t ":") t
    :else
    (let [as-long (try (Long/parseLong t) (catch #?(:clj Exception :cljs js/Error) _ ::nope))]
      (if (not= as-long ::nope)
        as-long
        (let [as-dbl (try (Double/parseDouble t) (catch #?(:clj Exception :cljs js/Error) _ ::nope))]
          (if (not= as-dbl ::nope) as-dbl t))))))

(def ^:private END ::end)

(defn- parse-one
  "Parse one form from the mutable token cursor (an atom holding a seq). Returns the parsed
  value or END for a closing bracket. Mirrors `_parse(it)`."
  [cur]
  (let [t (first @cur)]
    (swap! cur rest)
    (cond
      (= t "[")
      (loop [out []]
        (let [x (parse-one cur)]
          (if (= x END) out (recur (conj out x)))))
      (= t "{")
      (loop [out {}]
        (let [k (parse-one cur)]
          (if (= k END)
            out
            (let [v (parse-one cur)]
              (recur (assoc out k v))))))
      (or (= t "]") (= t "}")) END
      :else (atom* t))))

(defn parse-edn
  "Parse the first EDN form in `s` → Clojure data (keywords kept as strings)."
  [s]
  (parse-one (atom (tokens s))))

#?(:clj
   (defn load-edn
     "Read + parse an EDN file (file I/O only at this edge). Mirrors `load_edn(path)`."
     [path]
     (parse-edn (slurp (str path)))))
