;; Copyright 2020 The Defold Foundation
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

(ns editor.animation-set
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.build-target :as bt]
            [editor.defold-project :as project]
            [editor.graph-util :as gu]
            [editor.model-loader :as model-loader]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [util.murmur :as murmur])
  (:import [clojure.lang ExceptionInfo]
           [com.dynamo.rig.proto Rig$AnimationSet Rig$AnimationSetDesc]
           [com.dynamo.bob.pipeline AnimationSetBuilder LoaderException]
           [java.util ArrayList]))

(set! *warn-on-reflection* true)

(def ^:private animation-set-icon "icons/32/Icons_24-AT-Animation.png")

(def supported-skeleton-formats #{"dae"})

(defn- animation-instance-desc-pb-msg [resource]
  {:animation (resource/resource->proj-path resource)})

(g/defnk produce-desc-pb-msg [skeleton animations]
  {:skeleton (resource/resource->proj-path skeleton)
   :animations (mapv animation-instance-desc-pb-msg animations)})

(defn- to-animation-id [^String parent-id ^String id]
  (cond
      (neg? (.indexOf id "/"))
      (str parent-id "/" id)
      :else
      id))

(defn- get-animation-resource [path animation-resources]
  (let [matches (filter (fn [resource] (= path (resource/proj-path resource))) animation-resources)]
    (first matches)))

(g/defnk produce-animation-info [resource bones animation-resources]
  (prn "MAWE animation-set produce-animation-info" resource animation-resources bones)
  (let [desc (AnimationSetBuilder/getAnimationSetDesc (io/input-stream resource))
        animation-paths (AnimationSetBuilder/getAnimationsPaths desc)
        _ (prn "MAWE animation-set produce-animation-info  animation-paths" animation-paths)
        animation-infos (map (fn [path] {:path path :parent-id "" :resource (get-animation-resource path animation-resources)}) animation-paths)]

    (prn "MAWE animation-set produce-animation-info" animation-infos)
    animation-infos))

(defn- is-animation-set? [resource]
  (= (:ext resource) "animationset"))

(defn- resolve-animation-infos [parent-id animation-info animation-resources]
  (let [animation-sets (filter is-animation-set? animation-resources)
        animations-infos (map (fn [resource] (assoc (:animation-info resource) :parent-id parent-id)) animation-sets)
        child-animations-infos (map (fn [resource]
                                      (let [anim-id (resource/base-name resource)
                                            next-parent-id (to-animation-id parent-id anim-id)]
                                        (resolve-animation-infos next-parent-id (:animation-info resource) (:animation-resources resource)))) animation-sets)

        _ (prn "MAWE resolve-animation-infos animation-sets" animation-sets)
        _ (prn "MAWE resolve-animation-infos animation-info" animation-info)
        _ (prn "MAWE resolve-animation-infos animations-infos" animations-infos)
        _ (prn "MAWE resolve-animation-infos child-animations-infos" child-animations-infos)

        ; [[a] [b c] [d]] -> [a b c d]
        flattened (reduce into (concat [animation-info] [animations-infos] child-animations-infos))]
    
    (prn "MAWE resolve-animation-infos" flattened)
    flattened))

; Also used by model.clj
(g/defnk produce-animation-infos [animation-info animation-resources]
  (let [out (resolve-animation-infos "" animation-info animation-resources)]
    out))

(defn- load-and-validate-animation-set [bones animation-infos]
  (prn "MAWE load-and-validate-animation-set" animation-infos bones)
  (let [paths (map (fn [x] (:path x)) animation-infos)
        streams (map (fn [x] (io/input-stream (:resource x))) animation-infos)
        parent-ids (map (fn [x] (:parent-id x)) animation-infos)
        animation-set-builder (Rig$AnimationSet/newBuilder)
        animation-ids (ArrayList.)]
    (AnimationSetBuilder/buildAnimations paths bones streams parent-ids animation-set-builder animation-ids)

    (let [animation-set (protobuf/pb->map (.build animation-set-builder))]
      {:animation-set animation-set
       :animation-ids animation-ids})))

; Also used by model.clj
(g/defnk produce-animation-set-info [_node-id bones resource skeleton-resource animation-infos]
  (try
    (prn "MAWE animation-set produce-animation-set-info" bones)
    (load-and-validate-animation-set bones animation-infos)
    (catch LoaderException e
      (g/->error _node-id :animations :fatal resource
                 (str "Failed to build " (resource/resource->proj-path resource)
                      ": " (.getMessage e))))))

(g/defnk produce-animation-set [animation-set-info]
  (:animation-set animation-set-info))

(g/defnk produce-animation-ids [animation-set-info]
  (:animation-ids animation-set-info))

;; used by the model.clj
(defn hash-animation-set-ids [animation-set]
  (update animation-set :animations (partial mapv #(update % :id murmur/hash64))))

(defn- build-animation-set [resource _dep-resources user-data]
  {:resource resource
   :content (protobuf/map->bytes Rig$AnimationSet (:animation-set user-data))})

(g/defnk produce-animation-set-build-target [_node-id resource bones animation-set]
  (bt/with-content-hash
    {:node-id _node-id
     :resource (workspace/make-build-resource resource)
     :build-fn build-animation-set
     :user-data {:animation-set animation-set}}))

(def ^:private form-sections
  {:navigation false
   :sections
   [{:title "Animation Set"
     :fields [{:path [:skeleton]
               :type :resource
               :filter #{"dae"}
               :label "Skeleton"
               :default nil}
              {:path [:animations]
               :type :list
               :label "Animations"
               :element {:type :resource
                         :filter #{"animationset" "dae"}
                         :default nil}}]}]})

(defn- set-form-op [{:keys [node-id]} [property] value]
  (g/set-property! node-id property value))

(defn- clear-form-op [{:keys [node-id]} [property]]
  (g/clear-property! node-id property))

(g/defnk produce-form-data [_node-id skeleton animations]
  (let [values {[:skeleton] skeleton
                [:animations] animations}]
    (-> form-sections
        (assoc :form-ops {:user-data {:node-id _node-id}
                          :set set-form-op
                          :clear clear-form-op})
        (assoc :values values))))

(g/defnk produce-bones [skeleton-resource]
  (let [bones (model-loader/load-bones skeleton-resource)]
  ;(let [bones (:bones skeleton-resource)]
    (prn "MAWE animation-set produce-bones" skeleton-resource bones)
    bones))

(g/defnode AnimationSetNode
  (inherits resource-node/ResourceNode)

  (input skeleton-resource resource/Resource)
  (input animation-resources resource/Resource :array)
  (input animation-sets g/Any :array)
           
  (input bones g/Any)
  ;(output bones g/Any (gu/passthrough bones))

  (property skeleton resource/Resource
            (value (gu/passthrough skeleton-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :skeleton-resource
                                             :bones :bones])))
            (dynamic error (g/fnk [_node-id skeleton]
                                  (or (validation/prop-error :info _node-id :skeleton validation/prop-nil? skeleton "Skeleton")
                                      (validation/prop-error :fatal _node-id :skeleton validation/prop-resource-not-exists? skeleton "Skeleton"))))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext supported-skeleton-formats})))

  (property animations resource/ResourceVec
            (value (gu/passthrough animation-resources))
            (set (fn [evaluation-context self old-value new-value]
                   (let [project (project/get-project (:basis evaluation-context) self)
                         connections [[:resource :animation-resources]
                                      [:animation-set :animation-sets]]]
                     (concat
                       (for [old-resource old-value]
                         (if old-resource
                           (project/disconnect-resource-node evaluation-context project old-resource self connections)
                           (g/disconnect project :nil-resource self :animation-resources)))
                       (for [new-resource new-value]
                         (if new-resource
                           (:tx-data (project/connect-resource-node evaluation-context project new-resource self connections))
                           (g/connect project :nil-resource self :animation-resources)))))))
            (dynamic visible (g/constantly false)))

  ;(output bones g/Any :cached produce-bones)
  (output animation-info g/Any :cached produce-animation-info)
  (output animation-infos g/Any :cached produce-animation-infos)
  (output form-data g/Any :cached produce-form-data)

  (output desc-pb-msg g/Any :cached produce-desc-pb-msg)
  (output save-value g/Any (gu/passthrough desc-pb-msg))
  (output animation-set-info g/Any :cached produce-animation-set-info)
  (output animation-set g/Any :cached produce-animation-set)
  (output animation-ids g/Any :cached produce-animation-ids)
  (output animation-set-build-target g/Any :cached produce-animation-set-build-target))

(defn- load-animation-set [_project self resource pb]
  (let [proj-path->resource (partial workspace/resolve-resource resource)
        animation-proj-paths (map :animation (:animations pb))
        animation-resources (mapv proj-path->resource animation-proj-paths)]
    (g/set-property self
                    :skeleton (workspace/resolve-resource resource (:skeleton pb))
                    :animations animation-resources)))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
    :ext "animationset"
    :icon animation-set-icon
    :label "Animation Set"
    :load-fn load-animation-set
    :node-type AnimationSetNode
    :ddf-type Rig$AnimationSetDesc
    :view-types [:cljfx-form-view]))
