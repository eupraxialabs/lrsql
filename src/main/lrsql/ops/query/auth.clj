(ns lrsql.ops.query.auth
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.lrs.auth :as lrs-auth]
            [lrsql.functions :as f]
            [lrsql.spec.common :refer [transaction?]]
            [lrsql.spec.auth :as as]
            [lrsql.util.auth :as ua]))

(s/fdef query-credential-scopes*
  :args (s/cat :tx transaction? :input as/cred-scopes-query-spec)
  :ret (s/nilable (s/coll-of ::as/scope
                             :min-count 1
                             :gen-max 5)))

(defn query-credential-scopes*
  "Return a vec of scopes associated with an API key and secret if it
   exists in the credential table; return nil if not."
  [tx input]
  (when (f/query-credential-exists tx input)
    (some->> (f/query-credential-scopes tx input)
             (map :scope)
             (filter some?)
             vec)))

(s/fdef query-credential-scopes
  :args (s/cat :tx transaction? :input as/cred-scopes-query-spec)
  :ret as/cred-scopes-query-ret-spec)

(defn query-credential-scopes
  "Like `query-credential-scopes*` except that its return value conforms
   to what is expected by the lrs library. In particular, it returns a result
   map containins the scope and auth key map on success. If the credentials
   are not found, return a sentinel to indicate that the webserver will
   return 401 Forbidden."
  [tx input]
  (if-some [scopes (query-credential-scopes* tx input)]
    ;; Credentials found - return result map
    (let [{:keys [api-key secret-key]}
          input
          scope-set
          (if (empty? scopes)
            ;; Credentials not associated with any scope.
            ;; The LRS MUST assume a requested scope of
            ;; "statements/write" and "statements/read/mine"
            ;; if no scope is specified.
            #{:scopes/statements.write
              :scopes/statements.read.mine}
            ;; Return scope set
            (->> scopes
                 (map ua/scope-str->kw)
                 (into #{})))]
      {:result {:scopes scope-set
                :prefix ""
                :auth   {:basic {:username api-key
                                 :password secret-key}}}})
    ;; Credentials not found - uh oh!
    {:result :com.yetanalytics.lrs.auth/forbidden}))

(s/fdef query-credentials
  :args (s/cat :tx transaction? :input as/creds-query-spec)
  :ret (s/coll-of as/scoped-key-pair-spec :gen-max 5))

(defn query-credentials
  "Given an input containing an account ID, return all creds (and their
   associated scopes) that are associated with that account."
  [tx input]
  (let [creds  (->> input
                    (f/query-credentials tx)
                    (map (fn [{ak :api_key sk :secret_key}]
                           {:api-key ak :secret-key sk})))
        scopes (doall (map (fn [cred]
                             (->> cred
                                  (f/query-credential-scopes tx)
                                  (map :scope)))
                           creds))]
    (mapv (fn [cred cred-scopes]
            (assoc cred :scopes (set cred-scopes)))
          creds
          scopes)))
