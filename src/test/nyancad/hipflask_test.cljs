; SPDX-FileCopyrightText: 2026 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns nyancad.hipflask-test
  "Tests for nyancad.hipflask — the PouchDB reactive-atom layer.

   PouchDB in Node defaults to a leveldb adapter that creates directories
   on disk. We load pouchdb-adapter-memory so each test gets a fresh
   in-memory DB with no filesystem footprint."
  (:require ["pouchdb" :as PouchDB]
            ["pouchdb-adapter-memory" :as MemAdapter]
            [cljs.test :refer [deftest is testing async]]
            [cljs.core.async :refer [go <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [nyancad.hipflask :as hf]
            [nyancad.hipflask.util :as hfu]))

(.plugin PouchDB MemAdapter)

(defn- mem-db
  "Create a fresh in-memory PouchDB. Gensymmed name so each test is isolated."
  []
  (PouchDB. (str "testdb-" (random-uuid)) #js {:adapter "memory"}))

;; ---------------------------------------------------------------------------
;; Low-level PouchDB helpers
;; ---------------------------------------------------------------------------

(deftest put-returns-ok
  (testing "put with a fresh doc resolves with .ok=true"
    (async done
      (go
        (let [db (mem-db)
              res (<p! (hf/put db {:_id "a" :n 1}))]
          (is (.-ok res))
          (is (= "a" (.-id res)))
          (is (string? (.-rev res))))
        (done)))))

;; ---------------------------------------------------------------------------
;; pouch-atom lifecycle
;; ---------------------------------------------------------------------------
;; Contract: a PAtom is a reactive view of a single "group" in a PouchDB.
;; The group prefix is `{group}{sep}` (sep = ":"). On construction, the
;; atom asynchronously fetches all docs with that prefix into its cache.
;; `done?` returns a channel that closes when the latest mutation (or the
;; initial load) has finished — waiting on it is the standard way to
;; sequence with the atom.

(deftest pouch-atom-init-loads-existing-docs
  (testing "pre-existing docs under the group prefix land in the cache after done?"
    (async done
      (go
        (let [db (mem-db)]
          (<p! (hf/put db {:_id "grp:a" :n 1}))
          (<p! (hf/put db {:_id "grp:b" :n 2}))
          (<p! (hf/put db {:_id "other:c" :n 9}))
          (let [pa (hf/pouch-atom db "grp")]
            (<! (hf/done? pa))
            (is (contains? @pa "grp:a"))
            (is (contains? @pa "grp:b"))
            (is (not (contains? @pa "other:c"))
                "docs outside the group prefix must not appear in the cache")
            (is (= 1 (get-in @pa ["grp:a" :n])))))
        (done)))))

(deftest pouch-atom-update-writes-through-to-db
  (testing "swap!-assoc stores the doc; re-fetching from DB sees it with a _rev"
    (async done
      (go
        (let [db (mem-db)
              pa (hf/pouch-atom db "grp")]
          (<! (hf/done? pa))
          (<! (swap! pa assoc "grp:x" {:n 42}))
          (is (= 42 (get-in @pa ["grp:x" :n])))
          (is (string? (get-in @pa ["grp:x" :_rev])))
          (let [^js res (<p! (.get db "grp:x"))]
            (is (= 42 (.-n res)))))
        (done)))))

(deftest pouch-atom-delete-via-dissoc
  (testing "swap!-dissoc removes the key from cache and tombstones the doc in DB.
            (dissoc is the canonical delete path: it produces an updocs map
            without the key, which prepare translates into a _deleted:true
            write. `assoc id nil` does NOT delete — it stores a nil value.)"
    (async done
      (go
        (let [db (mem-db)
              pa (hf/pouch-atom db "grp")]
          (<! (hf/done? pa))
          (<! (swap! pa assoc "grp:x" {:n 1}))
          (is (contains? @pa "grp:x"))
          (<! (swap! pa dissoc "grp:x"))
          (is (not (contains? @pa "grp:x")))
          ;; verify the doc is gone from the DB too (alldocs without
          ;; {keys: [...]} skips tombstones).
          (let [^js res (<p! (hf/alldocs db #js {:include_docs true
                                                 :startkey "grp:"
                                                 :endkey "grp:\ufff0"}))]
            (is (zero? (.-total_rows res))
                "deleted doc should not appear in alldocs result")))
        (done)))))

(deftest pouch-atom-multi-key-swap-via-update-keys
  (testing "passing a set as the swap! key-selector reaches the 'update-keys'
            branch of pouch-swap!'s keyset dispatch — f receives the keys as
            one positional argument (a seq), which is exactly util/update-keys'
            shape. This is the canonical bulk-update idiom."
    (async done
      (go
        (let [db (mem-db)
              pa (hf/pouch-atom db "grp")]
          (<! (hf/done? pa))
          (<! (swap! pa assoc "grp:a" {:n 10}))
          (<! (swap! pa assoc "grp:b" {:n 20}))
          ;; (swap! pa update-keys keyset update :n inc) expands to
          ;; (update-keys docs keys-seq update :n inc).
          (<! (swap! pa hfu/update-keys #{"grp:a" "grp:b"} update :n inc))
          (is (= 11 (get-in @pa ["grp:a" :n])))
          (is (= 21 (get-in @pa ["grp:b" :n]))))
        (done)))))

(deftest pouch-atom-identity-is-stable
  (testing "deref'ing the same PAtom twice without mutation returns identical cache"
    (async done
      (go
        (let [db (mem-db)
              pa (hf/pouch-atom db "grp")]
          (<! (hf/done? pa))
          (is (identical? @pa @pa)))
        (done)))))

(deftest pouch-atom-cache-type-preserved
  (testing "a PAtom constructed with a sorted-map cache maintains that type
            across swaps — important when callers rely on ordered iteration"
    (async done
      (go
        (let [db (mem-db)
              pa (hf/pouch-atom db "grp" (atom (sorted-map)))]
          (<! (hf/done? pa))
          (<! (swap! pa assoc "grp:b" {:n 2}))
          (<! (swap! pa assoc "grp:a" {:n 1}))
          (is (= cljs.core/PersistentTreeMap (type @pa)))
          (is (= ["grp:a" "grp:b"] (vec (keys @pa)))))
        (done)))))

(deftest pouch-atom-watcher-fires-on-mutation
  (testing "add-watch on a PAtom forwards change notifications like a core atom"
    (async done
      (go
        (let [db (mem-db)
              pa (hf/pouch-atom db "grp")
              seen (atom 0)]
          (<! (hf/done? pa))
          (add-watch pa :cnt (fn [_ ref _ _]
                               (is (identical? ref pa))
                               (swap! seen inc)))
          (<! (swap! pa assoc "grp:x" {:n 1}))
          (is (pos? @seen)))
        (done)))))

;; ---------------------------------------------------------------------------
;; get-group
;; ---------------------------------------------------------------------------
;; Contract: range query over a single group. Optional limit caps how many
;; docs come back. Optional target collection determines the returned type.

(deftest get-group-respects-limit
  (testing "limit=2 returns at most 2 docs even if more exist in the group"
    (async done
      (go
        (let [db (mem-db)]
          (doseq [i (range 5)]
            (<p! (hf/put db {:_id (str "grp:doc" i) :n i})))
          (let [out (<! (hf/get-group db "grp" 2))]
            (is (= 2 (count out))))
          (let [all (<! (hf/get-group db "grp"))]
            (is (= 5 (count all)))))
        (done)))))

(deftest get-group-respects-target-type
  (testing "target sorted-map keeps the sorted-map type in the result"
    (async done
      (go
        (let [db (mem-db)]
          (<p! (hf/put db {:_id "grp:a" :n 1}))
          (let [out (<! (hf/get-group db "grp" nil (sorted-map)))]
            (is (= cljs.core/PersistentTreeMap (type out)))))
        (done)))))

;; ---------------------------------------------------------------------------
;; validator
;; ---------------------------------------------------------------------------
;; Contract: PAtom exposes a mutable `validator` field. If set, it is
;; called with the proposed new cache value before each write.

(deftest validator-accepts-conforming-updates
  (testing "a validator that accepts every change lets swap! through"
    (async done
      (go
        (let [db (mem-db)
              pa (hf/pouch-atom db "grp")]
          (<! (hf/done? pa))
          (set! (.-validator ^js pa) (constantly true))
          (<! (swap! pa assoc "grp:x" {:n 7}))
          (is (= 7 (get-in @pa ["grp:x" :n]))))
        (done)))))
