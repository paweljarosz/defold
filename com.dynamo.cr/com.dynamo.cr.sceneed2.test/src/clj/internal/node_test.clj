(ns internal.node-test
  (:require [clojure.test :refer :all]
            [schema.core :as s]
            [plumbing.core :refer [defnk fnk]]
            [dynamo.types :as t :refer [as-schema]]
            [dynamo.node :as n :refer [defnode]]
            [dynamo.property :as dp]
            [dynamo.project :as p]
            [dynamo.system :as ds]
            [dynamo.system.test-support :refer [with-clean-world tx-nodes]]
            [internal.node :as in :refer [deep-merge]]
            [internal.system :as is]
            [internal.query :as iq]
            [internal.transaction :as it]))

(def ^:dynamic *calls*)

(defn tally [node fn-symbol]
  (swap! *calls* update-in [(:_id node) fn-symbol] (fnil inc 0)))

(defn get-tally [node fn-symbol]
  (get-in @*calls* [(:_id node) fn-symbol] 0))

(def a-schema (as-schema {:names [java.lang.String]}))

(def m1 {:cached #{:derived}   :inputs {:simple-number 18 :another [:a] :nested ['v1] :setvalued #{:x}}})
(def m2 {:cached #{:expensive} :inputs {:simple-number 99 :another [:a] :nested ['v3] :setvalued #{:z}}})
(def expected-merge {:cached #{:expensive :derived},
                     :inputs {:simple-number 99
                              :another [:a :a],
                              :nested ['v1 'v3]
                              :setvalued #{:x :z}}})

(def s1           (assoc m1 :declared-type a-schema))
(def s2           (assoc m2 :declared-type a-schema))
(def schema-merge (assoc expected-merge :declared-type a-schema))

(deftest merging-nested-maps
  (is (= expected-merge (deep-merge m1 m2))))

(deftest merging-maps-with-schemas
  (is (= schema-merge (deep-merge s1 s2)))
  (is (:schema (meta (get (deep-merge s1 s2) :declared-type)))))

(defnode SimpleTestNode
  (property foo {:schema dp/Str :default "FOO!"}))

(definterface MyInterface$InnerInterface
  (^int bar []))

(defnode AncestorInterfaceImplementer
  MyInterface$InnerInterface
  (bar [this] 800))

(defnode NodeWithProtocols
  (inherits AncestorInterfaceImplementer)

  (property foo {:schema dp/Str :default "the user"})

  clojure.lang.IDeref
  (deref [this] (:foo this))

  t/N2Extent
  (width [this] 800)
  (height [this] 600))

(defnode NodeWithEvents
  (on :mousedown
    (let [nn (ds/add (make-node-with-protocols :_id -1))]
      (ds/set-property {:_id -1} :foo "newly created")
      (ds/set-property self :message-processed true))))

(deftest node-definition
  (testing "properties"
    (is (= [:foo] (-> (make-simple-test-node) :descriptor :properties keys)))
    (is (contains? (make-simple-test-node) :foo)))
  (testing "property defaults"
    (is (= "FOO!" (-> (make-simple-test-node) :foo))))
  (testing "extending nodes with protocols"
    (is (instance? clojure.lang.IDeref (make-node-with-protocols)))
    (is (= "the user" @(make-node-with-protocols)))
    (is (satisfies? t/N2Extent (make-node-with-protocols)))
    (is (= 800 (t/width (make-node-with-protocols))))))

(deftest event-delivery
  (with-clean-world
    (let [evented (ds/transactional (ds/add (make-node-with-events :_id -1)))]
      (is (= :ok (t/process-one-event evented {:type :mousedown})))
      (is (:message-processed (iq/node-by-id world-ref (:_id evented)))))))

(deftest nodes-share-descriptors
  (is (identical? (-> (make-simple-test-node) :descriptor) (-> (make-simple-test-node) :descriptor))))

(defprotocol AProtocol
  (complainer [this]))

(definterface IInterface
  (allGood []))

(defnode MyNode
  AProtocol
  (complainer [this] :owie)
  IInterface
  (allGood [this] :ok))

(deftest node-respects-namespaces
  (testing "node can implement protocols not known/visible to internal.node"
    (is (= :owie (complainer (make-my-node)))))
  (testing "node can implement interface not known/visible to internal.node"
    (is (= :ok (.allGood (make-my-node))))))

(defnk depends-on-self [this] this)
(defnk depends-on-input [an-input] an-input)
(defnk depends-on-property [a-property] a-property)
(defnk depends-on-several [this project g an-input a-property] [this project g an-input a-property])
(defn  depends-on-default-params [this g] [this g])

(defnode DependencyTestNode
  (input an-input String)
  (input unused-input String)

  (property a-property {:schema dp/Str})

  (output depends-on-self s/Any depends-on-self)
  (output depends-on-input s/Any depends-on-input)
  (output depends-on-property s/Any depends-on-property)
  (output depends-on-several s/Any depends-on-several)
  (output depends-on-default-params s/Any depends-on-default-params))

(deftest dependency-mapping
  (testing "node reports its own dependencies"
    (let [test-node (make-dependency-test-node)
          deps      (t/output-dependencies test-node)]
      (are [input affected-outputs] (and (contains? deps input) (= affected-outputs (get deps input)))
           :an-input           #{:depends-on-input :depends-on-several}
           :a-property         #{:depends-on-property :depends-on-several :a-property}
           :project            #{:depends-on-several})
      (is (not (contains? deps :this)))
      (is (not (contains? deps :g)))
      (is (not (contains? deps :unused-input))))))

(defnode EmptyNode)

(deftest node-intrinsics
  (with-clean-world
    (let [[node] (tx-nodes (make-empty-node))]
      (is (identical? node (in/get-node-value node :self))))))

(defn ^:dynamic production-fn [this g] :defn)
(def ^:dynamic production-val :def)

(defnode ProductionFunctionTypesNode
  (output inline-fn      s/Keyword [this g] :fn)
  (output defn-as-symbol s/Keyword production-fn)
  (output def-as-symbol  s/Keyword production-val))

(deftest production-function-types
  (with-clean-world
    (let [[node] (tx-nodes (make-production-function-types-node))]
      (is (= :fn   (in/get-node-value node :inline-fn)))
      (is (= :defn (in/get-node-value node :defn-as-symbol)))
      (is (= :def  (in/get-node-value node :def-as-symbol)))
      (binding [production-fn :dynamic-binding-val]
        (is (= :dynamic-binding-val (in/get-node-value node :defn-as-symbol))))
      (binding [production-val (constantly :dynamic-binding-fn)]
        (is (= :dynamic-binding-fn (in/get-node-value node :def-as-symbol)))))))

(defn production-fn-this [this g] this)
(defn production-fn-g [this g] g)
(defnk production-fnk-this [this] this)
(defnk production-fnk-g [g] g)
(defnk production-fnk-world [world] world)
(defnk production-fnk-project [project] project)
(defnk production-fnk-prop [prop] prop)
(defnk production-fnk-in [in] in)
(defnk production-fnk-in-multi [in-multi] in-multi)

(defnode ProductionFunctionInputsNode
  (input in       s/Keyword)
  (input in-multi [s/Keyword])
  (property prop {:schema dp/Keyword})
  (output out s/Keyword [this g] :out-val)
  (output inline-fn-this   s/Any [this g] this)
  (output inline-fn-g      s/Any [this g] g)
  (output defn-this        s/Any production-fn-this)
  (output defn-g           s/Any production-fn-g)
  (output defnk-this       s/Any production-fnk-this)
  (output defnk-g          s/Any production-fnk-g)
  (output defnk-world      s/Any production-fnk-world)
  (output defnk-project    s/Any production-fnk-project)
  (output defnk-prop       s/Any production-fnk-prop)
  (output defnk-in         s/Any production-fnk-in)
  (output defnk-in-multi   s/Any production-fnk-in-multi))

(deftest production-function-inputs
  (with-clean-world
    (let [project (ds/transactional (ds/add (p/make-project)))
          [node0 node1 node2] (ds/in project
                                (tx-nodes
                                  (make-production-function-inputs-node :prop :node0)
                                  (make-production-function-inputs-node :prop :node1)
                                  (make-production-function-inputs-node :prop :node2)))
          _ (ds/transactional
              (ds/connect node0 :defnk-prop node1 :in)
              (ds/connect node0 :defnk-prop node2 :in)
              (ds/connect node1 :defnk-prop node2 :in)
              (ds/connect node0 :defnk-prop node1 :in-multi)
              (ds/connect node0 :defnk-prop node2 :in-multi)
              (ds/connect node1 :defnk-prop node2 :in-multi))
          graph (is/graph world-ref)]
      (testing "inline fn parameters"
        (is (identical? node0 (in/get-node-value node0 :inline-fn-this)))
        (is (identical? graph (in/get-node-value node0 :inline-fn-g))))
      (testing "standard defn parameters"
        (is (identical? node0 (in/get-node-value node0 :defn-this)))
        (is (identical? graph (in/get-node-value node0 :defn-g))))
      (testing "'special' defnk inputs"
        (is (identical? node0     (in/get-node-value node0 :defnk-this)))
        (is (identical? graph     (in/get-node-value node0 :defnk-g)))
        (is (identical? world-ref (in/get-node-value node0 :defnk-world)))
        (is (= project (in/get-node-value node0 :defnk-project))))
      (testing "defnk inputs from node properties"
        (is (= :node0 (in/get-node-value node0 :defnk-prop))))
      (testing "defnk inputs from node inputs"
        (is (nil?              (in/get-node-value node0 :defnk-in)))
        (is (= :node0          (in/get-node-value node1 :defnk-in)))
        (is (#{:node0 :node1}  (in/get-node-value node2 :defnk-in)))
        (is (= []              (in/get-node-value node0 :defnk-in-multi)))
        (is (= [:node0]        (in/get-node-value node1 :defnk-in-multi)))
        (is (= [:node0 :node1] (in/get-node-value node2 :defnk-in-multi)))))))

(deftest node-properties-as-node-outputs
  (with-clean-world
    (let [[node0 node1] (tx-nodes
                          (make-production-function-inputs-node :prop :node0)
                          (make-production-function-inputs-node :prop :node1))
          _ (ds/transactional (ds/connect node0 :prop node1 :in))]
      (is (= :node0 (in/get-node-value node1 :defnk-in))))))

(defnk out-from-self [out-from-self] out-from-self)
(defnk out-from-in [in] in)
(defnk out-from-in-multi [in-multi] in-multi)

(defnode DependencyNode
  (input in s/Any)
  (input in-multi [s/Any])
  (output out-from-self     s/Any out-from-self)
  (output out-from-in       s/Any out-from-in)
  (output out-const         s/Any [this g] :const-val))

(deftest value-computation-dependency-loops
  (testing "output dependent on itself"
    (with-clean-world
      (let [[node] (tx-nodes (make-dependency-node))]
        (is (thrown? java.lang.AssertionError (in/get-node-value node :out-from-self))))))
  (testing "output dependent on itself connected to downstream input"
    (with-clean-world
       (let [[node0 node1] (tx-nodes (make-dependency-node) (make-dependency-node))
             _ (ds/transactional (ds/connect node0 :out-from-self node1 :in))]
         (is (thrown? java.lang.AssertionError (in/get-node-value node1 :out-from-in))))))
  (testing "cycle of period 1"
    (with-clean-world
      (let [[node] (tx-nodes (make-dependency-node))]
        (is (thrown? java.lang.AssertionError (ds/transactional
                                                (ds/connect node :out-from-in node :in)))))))
  (testing "cycle of period 2 (single transaction)"
    (with-clean-world
      (let [[node0 node1] (tx-nodes (make-dependency-node) (make-dependency-node))]
        (is (thrown? java.lang.AssertionError (ds/transactional
                                                (ds/connect node0 :out-from-in node1 :in)
                                                (ds/connect node1 :out-from-in node0 :in)))))))
  (testing "cycle of period 2 (multiple transactions)"
    (with-clean-world
      (let [[node0 node1] (tx-nodes (make-dependency-node) (make-dependency-node))
            _ (ds/transactional (ds/connect node0 :out-from-in node1 :in))]
        (is (thrown? java.lang.AssertionError (ds/transactional
                                                (ds/connect node1 :out-from-in node0 :in))))))))

(deftest production-function-dependency-limit
  (with-clean-world
    (let [nodes (apply tx-nodes (repeatedly 201 make-dependency-node))
          _ (ds/transactional
              (ds/connect (first nodes) :out-const (second nodes) :in)
              (doall (for [[x y] (partition 2 1 (rest nodes))]
                       (ds/connect x :out-from-in y :in))))]
      (is (= :const-val (in/get-node-value (nth nodes 0) :out-const)))
      (is (= :const-val (in/get-node-value (nth nodes 1) :out-from-in)))
      (is (= :const-val (in/get-node-value (nth nodes 199) :out-from-in)))
      (is (thrown? java.lang.AssertionError (in/get-node-value (nth nodes 200) :out-from-in))))))

(defn throw-exception-defn [this g]
  (throw (ex-info "Exception from production function" {})))

(defnk throw-exception-defnk []
  (throw (ex-info "Exception from production function" {})))

(defn production-fn-with-abort [this g]
  (n/abort "Aborting..." {:some-key :some-value})
  :unreachable-code)

(defnk throw-exception-defnk-with-invocation-count [this]
  (tally this :invocation-count)
  (throw (ex-info "Exception from production function" {})))

(defnode SubstituteValueNode
  (output out-defn                     s/Any throw-exception-defn)
  (output out-defnk                    s/Any throw-exception-defnk)
  (output out-defn-with-substitute-fn  s/Any :substitute-value (constantly :substitute) throw-exception-defn)
  (output out-defnk-with-substitute-fn s/Any :substitute-value (constantly :substitute) throw-exception-defnk)
  (output out-defn-with-substitute     s/Any :substitute-value :substitute              throw-exception-defn)
  (output out-defnk-with-substitute    s/Any :substitute-value :substitute              throw-exception-defnk)
  (output substitute-value-passthrough s/Any :substitute-value identity                 throw-exception-defn)
  (output out-abort                    s/Any production-fn-with-abort)
  (output out-abort-with-substitute    s/Any :substitute-value (constantly :substitute) production-fn-with-abort)
  (output out-abort-with-substitute-f  s/Any :substitute-value (fn [_] (throw (ex-info "bailed" {}))) production-fn-with-abort)
  (output out-defnk-with-invocation-count s/Any :cached throw-exception-defnk-with-invocation-count))

(deftest node-output-substitute-value
  (with-clean-world
    (let [n (ds/transactional (ds/add (make-substitute-value-node)))]
      (testing "exceptions from get-node-value when no substitute value fn"
        (is (thrown? Throwable (in/get-node-value n :out-defn)))
        (is (thrown? Throwable (in/get-node-value n :out-defnk))))
      (testing "substitute value replacement"
        (is (= :substitute (in/get-node-value n :out-defn-with-substitute-fn)))
        (is (= :substitute (in/get-node-value n :out-defnk-with-substitute-fn)))
        (is (= :substitute (in/get-node-value n :out-defn-with-substitute)))
        (is (= :substitute (in/get-node-value n :out-defnk-with-substitute))))
      (testing "parameters to substitute value fn"
        (is (= n (:node (in/get-node-value n :substitute-value-passthrough)))))
      (testing "exception from substitute value fn"
        (is (thrown-with-msg? Throwable #"bailed" (in/get-node-value n :out-abort-with-substitute-f))))
      (testing "abort"
        (is (thrown-with-msg? Throwable #"Aborting\.\.\." (in/get-node-value n :out-abort)))
        (try
          (in/get-node-value n :out-abort)
          (is false "Expected get-node-value to throw an exception")
          (catch Throwable e
            (is (= {:some-key :some-value} (ex-data e)))))
        (is (= :substitute (in/get-node-value n :out-abort-with-substitute))))
      (testing "interaction with cache"
        (binding [*calls* (atom {})]
          (is (= 0 (get-tally n :invocation-count)))
          (is (thrown? Throwable (in/get-node-value n :out-defnk-with-invocation-count)))
          (is (= 1 (get-tally n :invocation-count)))
          (is (thrown? Throwable (in/get-node-value n :out-defnk-with-invocation-count)))
          (is (= 1 (get-tally n :invocation-count))))))))

(defnode ValueHolderNode
  (property value {:schema dp/Long}))

(def ^:dynamic *answer-call-count* (atom 0))

(defnk the-answer [in]
  (swap! *answer-call-count* inc)
  (assert (= 42 in))
  in)

(defnode AnswerNode
  (input in s/Int)
  (output out                        s/Int                                      the-answer)
  (output out-cached                 s/Int :cached                              the-answer)
  (output out-with-substitute        s/Int         :substitute-value :forty-two the-answer)
  (output out-cached-with-substitute s/Int :cached :substitute-value :forty-two the-answer))

(deftest substitute-values-and-cache-invalidation
  (with-clean-world
    (let [[holder-node answer-node] (tx-nodes (make-value-holder-node :value 23) (make-answer-node))]

      (ds/transactional (ds/connect holder-node :value answer-node :in))

      (binding [*answer-call-count* (atom 0)]
        (is (thrown? Throwable (in/get-node-value answer-node :out)))
        (is (thrown? Throwable (in/get-node-value answer-node :out)))
        (is (= 2 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (thrown? Throwable (in/get-node-value answer-node :out-cached)))
        (is (thrown? Throwable (in/get-node-value answer-node :out-cached)))
        (is (= 1 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= :forty-two (in/get-node-value answer-node :out-with-substitute)))
        (is (= :forty-two (in/get-node-value answer-node :out-with-substitute)))
        (is (= 2 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= :forty-two (in/get-node-value answer-node :out-cached-with-substitute)))
        (is (= :forty-two (in/get-node-value answer-node :out-cached-with-substitute)))
        (is (= 1 @*answer-call-count*)))

      (ds/transactional (ds/set-property holder-node :value 42))

      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out)))
        (is (= 1 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out-cached)))
        (is (= 1 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out-with-substitute)))
        (is (= 1 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out-cached-with-substitute)))
        (is (= 1 @*answer-call-count*))))))

(defnk always-fail []
  (assert false "I always fail :-("))

(defnode FailureNode
  (output out s/Any always-fail))

(deftest production-fn-input-failure
  (with-clean-world
    (let [[failure-node answer-node] (tx-nodes (make-failure-node) (make-answer-node))]

      (ds/transactional (ds/connect failure-node :out answer-node :in))

      (binding [*answer-call-count* (atom 0)]
        (is (thrown? Throwable (in/get-node-value answer-node :out)))
        (is (= 0 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (thrown? Throwable (in/get-node-value answer-node :out-cached)))
        (is (= 0 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= :forty-two (in/get-node-value answer-node :out-with-substitute)))
        (is (= 0 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= :forty-two (in/get-node-value answer-node :out-cached-with-substitute)))
        (is (= 0 @*answer-call-count*)))

      (ds/transactional
        (ds/disconnect failure-node :out answer-node :in)
        (ds/connect (ds/add (make-value-holder-node :value 42)) :value answer-node :in))

      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out)))
        (is (= 1 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out-cached)))
        (is (= 42 (in/get-node-value answer-node :out-cached)))
        (is (= 1 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out-with-substitute)))
        (is (= 1 @*answer-call-count*)))
      (binding [*answer-call-count* (atom 0)]
        (is (= 42 (in/get-node-value answer-node :out-cached-with-substitute)))
        (is (= 42 (in/get-node-value answer-node :out-cached-with-substitute)))
        (is (= 1 @*answer-call-count*))))))

(deftest test-parse-output-flags
  (testing "degenerate cases"
    (is (= {:properties #{} :options {} :remainder nil} (in/parse-output-flags nil)))
    (is (= {:properties #{} :options {} :remainder []}  (in/parse-output-flags []))))
  (testing "preserves production function tail"
    (is (= '[production-fn] (:remainder (in/parse-output-flags '[production-fn]))))
    (is (= '[[a b] c (d e)] (:remainder (in/parse-output-flags '[[a b] c (d e)])))))
  (testing "boolean flags"
    (is (= #{:cached}            (:properties (in/parse-output-flags '[:cached production-fn]))))
    (is (= #{:cached :on-update} (:properties (in/parse-output-flags '[:cached :on-update :cached production-fn]))))
    (is (= #{}                   (:properties (in/parse-output-flags '[:unrecognized :cached production-fn])))))
  (testing "options with parameters"
    (is (= {:substitute-value 'subst-fn} (:options (in/parse-output-flags '[:substitute-value subst-fn production-fn]))))
    (is (thrown? java.lang.AssertionError (in/parse-output-flags '[:substitute-value])))
    (is (thrown? java.lang.AssertionError (in/parse-output-flags '[:cached :substitute-value]))))
  (testing "interactions among flags, options, and tail"
    (is (= {:properties #{:cached} :options {:substitute-value 'subst-fn} :remainder '[production-fn]}
          (in/parse-output-flags '[:cached :substitute-value subst-fn production-fn])))
    (is (= {:properties #{:cached} :options {:substitute-value 'subst-fn} :remainder '[production-fn]}
          (in/parse-output-flags '[:substitute-value subst-fn :cached production-fn])))
    (is (= {:properties #{} :options {:substitute-value :cached} :remainder '[production-fn]}
          (in/parse-output-flags '[:substitute-value :cached production-fn])))))
