(ns lrsql.hugsql.command.statement
  (:require
   [com.yetanalytics.lrs.xapi.statements :as ss]
   [com.yetanalytics.lrs.xapi.activities :as as]
   [lrsql.hugsql.functions :as f]
   [lrsql.hugsql.util :as u]
   [lrsql.hugsql.command.util :as cu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Insertions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(defn- insert-statement-input!
  "Insert a new input into the DB. If the input is a Statement, return the
   Statement ID on success, nil for any other kind of input. May void
   previously-stored Statements."
  [tx {:keys [table] :as input}]
  (case table
    :statement
    ;; TODO: Query the statement by ID first; if IDs match, compare the payloads
    ;; to determine if the two statements are the same, in which case throw
    ;; an exception.
    (do (f/insert-statement! tx (update input :payload u/write-json))
        ;; Void statements
        (when (:voiding? input)
          (f/void-statement! tx {:statement-id (:?statement-ref-id input)}))
        ;; Success! (Too bad H2 doesn't have INSERT...RETURNING)
        (u/uuid->str (:statement-id input)))
    :actor
    (do (if (some->> (select-keys input [:actor-ifi])
                     (f/query-actor-exists tx))
          (f/update-actor! tx (update input :payload u/write-json))
          (f/insert-actor! tx (update input :payload u/write-json)))
        nil)
    :activity
    (do (if-some [old-activ (some->> (select-keys input [:activity-iri])
                                     (f/query-activity tx)
                                     :payload
                                     u/parse-json)]
          (let [new-activ (:payload input)
                activity' (as/merge-activity old-activ new-activ)
                input'    (assoc input :payload (u/write-json activity'))]
            (f/update-activity! tx input'))
          (f/insert-activity! tx (update input :payload u/write-json)))
        nil)
    :attachment
    (do (f/insert-attachment! tx input) nil)
    :statement-to-actor
    (do (f/insert-statement-to-actor! tx input) nil)
    :statement-to-activity
    (do (f/insert-statement-to-activity! tx input) nil)
    :statement-to-statement
    (do (let [input' {:statement-id (:descendant-id input)}
              exists (f/query-statement-exists tx input')]
          (when exists (f/insert-statement-to-statement! tx input)))
        nil)
    ;; Else
    (cu/throw-invalid-table-ex "insert-statement!" input)))

#_(defn insert-statements!
  "Insert one or more statements into the DB by inserting a sequence of inputs.
   Return a map between `:statement-ids` and a coll of statement IDs."
  [tx inputs]
  (->> inputs
       (map (partial insert-statement-input! tx))
       doall
       (filter some?)
       (assoc {} :statement-ids)))

(defn- insert-statement-input!
  [tx input]
  (f/insert-statement! tx (update input :payload u/write-json))
        ;; Void statements
  (when (:voiding? input)
    (f/void-statement! tx {:statement-id (:?statement-ref-id input)}))
        ;; Success! (Too bad H2 doesn't have INSERT...RETURNING)
  (u/uuid->str (:statement-id input)))

(defn- insert-actor-input!
  [tx input]
  (do (if (some->> (select-keys input [:actor-ifi])
                   (f/query-actor-exists tx))
        (f/update-actor! tx (update input :payload u/write-json))
        (f/insert-actor! tx (update input :payload u/write-json)))
      nil))

(defn- insert-activity-input!
  [tx input]
  (do (if-some [old-activ (some->> (select-keys input [:activity-iri])
                                   (f/query-activity tx)
                                   :payload
                                   u/parse-json)]
        (let [new-activ (:payload input)
              activity' (as/merge-activity old-activ new-activ)
              input'    (assoc input :payload (u/write-json activity'))]
          (f/update-activity! tx input'))
        (f/insert-activity! tx (update input :payload u/write-json)))
      nil))

(defn- insert-attachment-input!
  [tx input]
  (do (f/insert-attachment! tx input) nil))

(defn- insert-stmt-actor-input!
  [tx input]
  (do (f/insert-statement-to-actor! tx input) nil))

(defn- insert-stmt-activity-input!
  [tx input]
  (do (f/insert-statement-to-activity! tx input) nil))

(defn- insert-stmt-stmt-input!
  [tx input]
  (do (let [input' {:statement-id (:descendant-id input)}
            exists (f/query-statement-exists tx input')]
        (when exists (f/insert-statement-to-statement! tx input)))
      nil))

(defn insert-statement!
  [tx {:keys [statement-input
              actor-inputs
              activity-inputs
              attachment-inputs
              stmt-actor-inputs
              stmt-activity-inputs
              stmt-stmt-inputs]
       :as input-map}]
  (insert-statement-input! tx statement-input)
  (doall (map (partial insert-actor-input! tx) actor-inputs))
  (doall (map (partial insert-activity-input! tx) activity-inputs))
  (doall (map (partial insert-stmt-actor-input! tx) stmt-actor-inputs))
  (doall (map (partial insert-stmt-activity-input! tx) stmt-activity-inputs))
  (doall (map (partial insert-stmt-stmt-input! tx) stmt-stmt-inputs))
  (doall (map (partial insert-attachment-input! tx) attachment-inputs))
  ;; Success! Return the statement ID
  (:statement-id statement-input))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Query
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- format-stmt
  [statement format ltags]
  (case format
    :ids
    (ss/format-statement-ids statement)
    :canonical
    (ss/format-canonical statement ltags)
    :exact
    statement
    ;; else
    (throw (ex-info "Unknown format type"
                    {:kind   ::unknown-format-type
                     :format format}))))

(defn- query-res->statement
  [format ltags query-res]
  (-> query-res
      :payload
      u/parse-json
      (format-stmt format ltags)))

(defn- conform-attachment-res
  [{att-sha      :attachment_sha
    content-type :content_type
    length       :content_length
    contents     :contents}]
  {:sha2        att-sha
   :length      length
   :contentType content-type
   :content     contents})

(defn- query-one-statement
  "Query a single statement from the DB, using the `:statement-id` parameter."
  [tx input ltags]
  (let [{:keys [format attachments?] :or {format :exact}} input
        query-result (f/query-statement tx input)
        statement   (when query-result
                           (query-res->statement format ltags query-result))
        attachments (when (and statement attachments?)
                      (->> {:statement-id (get statement "id")}
                           (f/query-attachments tx)
                           (map conform-attachment-res)))]
    (cond-> {}
      statement   (assoc :statement statement)
      attachments (assoc :attachments attachments))))

(defn- query-many-statements
  "Query potentially multiple statements from the DB."
  [tx input ltags]
  (let [{:keys [format limit attachments?]
         :or   {format :exact}} input
        input'        (if limit (update input :limit inc) input)
        query-results (f/query-statements tx input')
        next-cursor   (if (and limit
                               (= (inc limit) (count query-results)))
                        (-> query-results last :id u/uuid->str)
                        "")
        stmt-results  (map (partial query-res->statement format ltags)
                           (if (not-empty next-cursor)
                             (butlast query-results)
                             query-results))
        att-results   (if attachments?
                        (->> (doall (map (fn [stmt]
                                           (->> (get stmt "id")
                                                (assoc {} :statement-id)
                                                (f/query-attachments tx)))
                                         stmt-results))
                             (apply concat)
                             (map conform-attachment-res))
                        [])]
    {:statement-result {:statements stmt-results
                        :more       next-cursor}
     :attachments      att-results}))

(defn query-statements
  "Query statements from the DB. Return a map containing a singleton
   `:statement` if a statement ID is included in the query, or a
   `:statement-result` object otherwise. The map also contains `:attachments`
   to return any associated attachments. The `ltags` argument controls which
   language tag-value pairs are returned when `:format` is `:canonical`.
   Note that the `:more` property of `:statement-result` returned is a
   statement PK, NOT the full URL."
  [tx input ltags]
  (let [{:keys [statement-id]} input]
    (if statement-id
      (query-one-statement tx input ltags)
      (query-many-statements tx input ltags))))

(defn- query-statement-refs*
  [tx input]
  (when-some [sref-id (:?statement-ref-id input)]
    (let [stmt-id (:statement-id input)]
      ;; Find descendants of the referenced Statement, and make those
      ;; the descendants of the referencing Statement.
      (->> (f/query-statement-descendants tx {:ancestor-id sref-id})
           (map :descendant_id)
           (concat [sref-id])
           (map (fn [descendant-id]
                  {:table         :statement-to-statement
                   :primary-key   (u/generate-squuid)
                   :descendant-id descendant-id ; add one level to hierarchy
                   :ancestor-id   stmt-id}))))))

(defn query-statement-refs
  "Query Statement References from the DB. In addition to the immediate
   references given by `:?statement-ref-id`, it returns ancestral
   references, i.e. not only the Statement referenced by `:?statement-ref-id`,
   but the Statement referenced by _that_, and so on. The return value
   is a lazy seq of maps with `:descendant-id` and `:ancestor-id` properties,
   where `:descendant-id` is the same as in `input`; these maps serve as
   additional inputs for `insert-statements!`."
  [tx inputs]
  (->> inputs
       (mapcat (partial query-statement-refs* tx))
       (filter some?)))

(defn query-descendants
  [tx {{?sref-id :?statement-ref-id} :statement-input}]
  (when ?sref-id
    (->> {:ancestor-id ?sref-id}
         (f/query-statement-descendants tx)
         (map :descendant_id)
         (concat [?sref-id]))))
