;; Copyright 2020-2022 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns internal.cache
  (:require [clojure.core.cache :as cc]
            [internal.graph.types :as gt]))

(set! *warn-on-reflection* true)

(def ^:dynamic *cache-debug* nil)

;; ----------------------------------------
;; Null cache for testing / uncached queries
;; ----------------------------------------
(cc/defcache NullCache [cache]
  cc/CacheProtocol
  (lookup [_ item])
  (lookup [_ item not-found] not-found)
  (has? [_ item] false)
  (hit [this item] this)
  (miss [this item result] this)
  (evict [this key] this)
  (seed [this base] base))

(def null-cache (NullCache. {}))

;; ----------------------------------------
;; Mutators
;; ----------------------------------------
(defn- encache
  [cache kvs]
  (if-let [kv (first kvs)]
    (do (assert (gt/endpoint? (first kv)))
        (recur (cc/miss cache (first kv) (second kv)) (next kvs)))
    cache))

(defn- hits
  [cache ks]
  (if-let [k (first ks)]
    (do (assert (gt/endpoint? k))
        (recur (cc/hit cache k) (next ks)))
    cache))

(defn- evict
  [cache ks]
  (if-let [k (first ks)]
    (do (assert (gt/endpoint? k))
        (recur (cc/evict cache k) (next ks)))
    cache))

;; ----------------------------------------
;; Interface
;; ----------------------------------------
(def default-cache-limit 1000)

(defn cache? [value]
  (satisfies? cc/CacheProtocol value))

(defn make-cache
  ([]
   (make-cache default-cache-limit))
  ([limit]
   (if (zero? limit)
     null-cache
     (cc/lru-cache-factory {} :threshold limit))))

(defn cache-hit
  [cache ks]
  (hits cache ks))

(defn cache-encache
  [cache kvs]
  (encache cache kvs))

(defn cache-invalidate
  [cache ks]
  (evict cache ks))
