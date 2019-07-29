(ns crux.jdbc-test
  (:require [clojure.test :as t]
            [crux.db :as db]
            [crux.jdbc :as j]
            [taoensso.nippy :as nippy]
            [next.jdbc :as jdbc]
            [crux.fixtures.api :refer [*api*]]
            [crux.fixtures.jdbc :as fj]
            [crux.fixtures.postgres :as fp]
            [crux.codec :as c]
            [crux.kafka :as k]
            [next.jdbc.result-set :as jdbcr])
  (:import crux.api.ICruxAPI))

(defn- with-each-jdbc-node [f]
  (t/testing "H2 Database"
    (fj/with-jdbc-node "h2" f))
  (t/testing "SQLite Database"
    (fj/with-jdbc-node "sqlite" f))
  (t/testing "Postgresql Database"
    (fp/with-embedded-postgres f))

  ;; Optional:
  (when (.exists (clojure.java.io/file ".testing-mysql.edn"))
    (t/testing "MYSQL Database"
      (fj/with-jdbc-node "mysql" f
        (read-string (slurp ".testing-mysql.edn")))))
  (when (.exists (clojure.java.io/file ".testing-oracle.edn"))
    (t/testing "Oracle Database"
      (fj/with-jdbc-node "oracle" f
        (read-string (slurp ".testing-oracle.edn"))))))

(t/use-fixtures :each with-each-jdbc-node)

(t/deftest test-happy-path-jdbc-event-log
  (let [doc {:crux.db/id :origin-man :name "Adam"}
        submitted-tx (.submitTx *api* [[:crux.tx/put doc]])]
    (.sync *api* (:crux.tx/tx-time submitted-tx) nil)
    (t/is (.entity (.db *api*) :origin-man))
    (t/testing "Tx log"
      (with-open [tx-log-context (.newTxLogContext *api*)]
        (t/is (= [{:crux.tx/tx-id 2,
                   :crux.tx/tx-time (:crux.tx/tx-time submitted-tx)
                   :crux.api/tx-ops
                   [[:crux.tx/put
                     (str (c/new-id (:crux.db/id doc)))
                     (str (c/new-id doc))]]}]
                 (.txLog *api* tx-log-context 0 false)))))))

(defn- docs [dbtype ds id]
  (jdbc/with-transaction [t ds]
    (doall (map (comp (partial j/->v dbtype) :v)
                (jdbc/execute! t ["SELECT V FROM tx_events WHERE TOPIC = 'docs' AND EVENT_KEY = ?" id]
                               {:builder-fn jdbcr/as-unqualified-lower-maps})))))

(t/deftest test-docs-retention
  (let [tx-log (:tx-log *api*)

        doc {:crux.db/id (c/new-id :some-id) :a :b}
        doc-hash (str (c/new-id doc))

        tx-1 (db/submit-tx tx-log [[:crux.tx/put doc]])]

    (t/is (= 1 (count (docs fj/*dbtype* (:ds (:tx-log *api*)) doc-hash))))
    (t/is (= [doc] (docs fj/*dbtype* (:ds (:tx-log *api*)) doc-hash)))

    (t/testing "Compaction"
      (db/submit-doc tx-log doc-hash {:crux.db/id (c/new-id :some-id) :a :evicted})
      (t/is (= [{:crux.db/id (c/new-id :some-id) :a :evicted}] (docs fj/*dbtype* (:ds (:tx-log *api*)) doc-hash))))))

;; TODO:
;; Microbench:
;;   Throughput (write 1m messages)
;;   Read it back out.
;;   Test deletion
;; Txes
;;   What should be done as a transaction?
;; Performance
;;   Add indices
