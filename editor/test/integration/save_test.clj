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

(ns integration.save-test
  (:require [clojure.data :as data]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [dynamo.graph :as g]
            [editor.asset-browser :as asset-browser]
            [editor.defold-project :as project]
            [editor.disk :as disk]
            [editor.fs :as fs]
            [editor.git :as git]
            [editor.git-test :refer [with-git]]
            [editor.progress :as progress]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util]
            [service.log :as log]
            [support.test-support :refer [spit-until-new-mtime touch-until-new-mtime with-clean-system]]
            [util.text-util :as text-util])
  (:import [com.dynamo.gamesys.proto AtlasProto$Atlas]
           [com.dynamo.gameobject.proto GameObject$CollectionDesc GameObject$PrototypeDesc]
           [com.dynamo.gamesys.proto Gui$SceneDesc]
           [com.dynamo.gamesys.proto Label$LabelDesc]
           [com.dynamo.gamesys.proto ModelProto$ModelDesc]
           [com.dynamo.particle.proto Particle$ParticleFX]
           [com.dynamo.render.proto Font$FontDesc]
           [com.dynamo.gamesys.proto Tile$TileGrid Tile$TileSet]
           [java.io StringReader File]
           [org.apache.commons.io FileUtils]
           [org.eclipse.jgit.api Git ResetCommand$ResetType]))

(def ^:private ext->proto {"atlas" AtlasProto$Atlas
                           "font" Font$FontDesc
                           "go" GameObject$PrototypeDesc
                           "collection" GameObject$CollectionDesc
                           "gui" Gui$SceneDesc
                           "label" Label$LabelDesc
                           "model" ModelProto$ModelDesc
                           "particlefx" Particle$ParticleFX
                           "tilegrid" Tile$TileGrid
                           "tilemap" Tile$TileGrid
                           "tileset" Tile$TileSet
                           "tilesource" Tile$TileSet})

(deftest save-all
  ;; These tests bypass the dirty check in order to verify that the information
  ;; saved is equivalent to the data loaded. Failures might signal that we've
  ;; forgotten to read data from the file, or somehow we're not writing all
  ;; properties to disk. If you get failures after modifying a .proto file,
  ;; first make sure your protobuf file extension maps to the corresponding
  ;; class in the ext->proto map above. This ensures added fields will use
  ;; defaults from the .proto file and not be reported as missing data in the
  ;; on-disk reference files.
  (let [queries ["**/env.cubemap"
                 "**/switcher.atlas"
                 "**/atlas_sprite.collection"
                 "**/props.collection"
                 "**/sub_props.collection"
                 "**/sub_sub_props.collection"
                 "**/go_with_scale3.collection"
                 "**/all_embedded.collection"
                 "**/all_referenced.collection"
                 "**/collection_with_scale3.collection"
                 "**/atlas_sprite.go"
                 "**/atlas.sprite"
                 "**/props.go"
                 "game.project"
                 "**/super_scene.gui"
                 "**/scene.gui"
                 "**/simple.gui"
                 "**/gui_resources.gui"
                 "**/gui_resources_missing_files.gui"
                 "**/broken_gui_resources.gui"
                 "**/breaks_gui_resources.gui"
                 "**/uses_gui_resources.gui"
                 "**/uses_gui_resources_missing_files.gui"
                 "**/uses_broken_gui_resources.gui"
                 "**/replaces_gui_resources.gui"
                 "**/replaces_broken_gui_resources.gui"
                 "**/fireworks_big.particlefx"
                 "**/new.collisionobject"
                 "**/three_shapes.collisionobject"
                 "**/tile_map_collision_shape.collisionobject"
                 "**/treasure_chest.model"
                 "**/new.factory"
                 "**/with_prototype.factory"
                 "**/new.sound"
                 "**/tink.sound"
                 "**/new.camera"
                 "**/non_default.camera"
                 "**/new.tilemap"
                 "**/with_cells.tilemap"
                 "**/with_layers.tilemap"
                 "**/test.model"
                 "**/empty_mesh.model"
                 "**/test.label"
                 "**/with_collection.collectionproxy"
                 "**/ice.tilesource"
                 "**/level.tilesource"
                 "**/test.tileset"]]
    (with-clean-system
      (let [workspace (test-util/setup-workspace! world)
            project (test-util/setup-project! workspace)
            save-data (group-by :resource (project/all-save-data project))]
        (doseq [query queries]
          (testing (format "Saving %s" query)
            (let [[resource _] (first (project/find-resources project query))
                  save (first (get save-data resource))
                  file (slurp resource)
                  pb-class (-> resource resource/resource-type :ext ext->proto)]
              (if pb-class
                (let [pb-save (protobuf/read-text pb-class (StringReader. (:content save)))
                      pb-disk (protobuf/read-text pb-class resource)
                      path []
                      [disk save both] (data/diff (get-in pb-disk path) (get-in pb-save path))]
                  (is (nil? disk))
                  (is (nil? save)))
                (is (= file (:content save)))))))))))

(deftest save-all-literal-equality
  (let [paths ["/collection/embedded_instances.collection"
               "/editor1/test.collection"
               "/game_object/embedded_components.go"
               "/editor1/ship_thruster_trail.particlefx"
               "/editor1/camera_fx.gui"
               "/editor1/body_font.font"
               "/editor1/test.gui"
               "/editor1/test.model"
               "/editor1/test.particlefx"]]
    (test-util/with-loaded-project
      (doseq [path paths]
        (testing (format "Saving %s" path)
          (let [node-id (test-util/resource-node project path)
                resource (g/node-value node-id :resource)
                save (g/node-value node-id :save-data)
                file (slurp resource)
                pb-class (-> resource resource/resource-type :ext ext->proto)]
            (is (not (g/error? save)))
            (when (and pb-class (not= file (:content save)))
              (let [pb-save (protobuf/read-text pb-class (StringReader. (:content save)))
                    pb-disk (protobuf/read-text pb-class resource)
                    path []
                    [disk-diff save-diff both] (data/diff (get-in pb-disk path) (get-in pb-save path))]
                (is (nil? disk-diff))
                (is (nil? save-diff))
                (when (and (nil? disk-diff) (nil? save-diff))
                  (let [diff-lines (keep (fn [[f s]] (when (not= f s) [f s])) (map vector (str/split-lines file) (str/split-lines (:content save))))]
                    (doseq [[f s] diff-lines]
                      (prn "f" f)
                      (prn "s" s))))))
            (is (= file (:content save)))))))))

(defn- save-all! [project]
  (let [save-data (project/dirty-save-data project)]
    (project/write-save-data-to-disk! save-data nil)
    (project/invalidate-save-data-source-values! save-data)))

(defn- dirty? [node-id]
  (some-> (g/node-value node-id :save-data)
    :dirty?))

(defn- set-prop-fn [k v]
  (fn [node-id]
    (g/transact
      (g/set-property node-id k v))))

(defn- delete-first-child! [node-id]
  (g/transact (g/delete-node (:node-id (test-util/outline node-id [0])))))

(defn- append-lua-code-line! [node-id]
  (test-util/code-editor-source! node-id (str (test-util/code-editor-source node-id) "\n-- added line")))

(defn- append-shader-code-line! [node-id]
  (test-util/code-editor-source! node-id (str (test-util/code-editor-source node-id) "\n// added line")))

(defn- append-c-code-line! [node-id]
  (test-util/code-editor-source! node-id (str (test-util/code-editor-source node-id) "\n// added line")))

(defn- set-setting!
  [node-id path value]
  (log/without-logging
    (let [form-data (g/node-value node-id :form-data)]
      (let [{:keys [user-data set]} (:form-ops form-data)]
        (set user-data path value)))))

(deftest save-dirty
  (let [black-list #{"/game_object/type_faulty_props.go"
                     "/collection/type_faulty_props.collection"}
        paths [["/sprite/atlas.sprite" (set-prop-fn :default-animation "no-anim")]
               ["/collection/empty_go.collection" delete-first-child!]
               ["/collection/embedded_embedded_sounds.collection" delete-first-child!]
               ["/collection/empty_props_go.collection" delete-first-child!]
               ["/collection/props_embed.collection" delete-first-child!]
               ["/collection/sub_embed.collection" delete-first-child!]
               ["/logic/session/ball.go" delete-first-child!]
               ["/logic/session/block.go" delete-first-child!]
               ["/logic/session/hud.go" delete-first-child!]
               ["/logic/session/pow.go" delete-first-child!]
               ["/logic/session/roof.go" delete-first-child!]
               ["/logic/session/wall.go" delete-first-child!]
               ["/logic/embedded_sprite.go" delete-first-child!]
               ["/logic/main.go" delete-first-child!]
               ["/logic/one_embedded.go" delete-first-child!]
               ["/logic/tilegrid_embedded_collision.go" delete-first-child!]
               ["/logic/two_embedded.go" delete-first-child!]
               ["/collection/all_embedded.collection" delete-first-child!]
               ["/materials/test.material" (set-prop-fn :name "new-name")]
               ["/collection/components/test.label" (set-prop-fn :text "new-text")]
               ["/editor1/test.atlas" (set-prop-fn :margin 500)]
               ["/collection/components/test.collisionobject" (set-prop-fn :mass 2.0)]
               ["/collision_object/embedded_shapes.collisionobject" (set-prop-fn :restitution 0.0)]
               ["/game_object/empty_props.go" delete-first-child!]
               ["/editor1/ice.tilesource" (set-prop-fn :inner-padding 1)]
               ["/tilesource/valid.tilesource" (set-prop-fn :inner-padding 1)]
               ["/graphics/sprites.tileset" (set-prop-fn :inner-padding 1)]
               ["/editor1/test.particlefx" delete-first-child!]
               ["/particlefx/blob.particlefx" delete-first-child!]
               ["/editor1/camera_fx.gui" delete-first-child!]
               ["/editor1/test.gui" delete-first-child!]
               ["/editor1/template_test.gui" delete-first-child!]
               ["/gui/legacy_alpha.gui" delete-first-child!]
               ["/model/empty_mesh.model" (set-prop-fn :name "new-name")]
               ["/script/props.script" append-lua-code-line!]
               ["/logic/default.render_script" append-lua-code-line!]
               ["/logic/main.gui_script" append-lua-code-line!]
               ["/script/test_module.lua" append-lua-code-line!]
               ["/logic/test.vp" append-shader-code-line!]
               ["/logic/test.fp" append-shader-code-line!]
               ["/native_ext/main.cpp" append-c-code-line!]
               ["/game.project" #(set-setting! % ["project" "title"] "new-title")]
               ["/live_update/live_update.settings" #(set-setting! % ["liveupdate" "mode"] "Zip")]]]
    (with-clean-system
      (let [workspace (test-util/setup-scratch-workspace! world)
            project   (test-util/setup-project! workspace)]
        (let [xf (comp (map :resource)
                   (map resource/resource->proj-path)
                   (filter (complement black-list)))
              clean? (fn [] (empty? (into [] xf (g/node-value project :dirty-save-data))))]
          ;; This first check is intended to verify that changes to the file
          ;; formats do not cause undue changes to existing content in game
          ;; projects. For example, adding fields to component protobuf
          ;; definitions may cause the default values to be written to every
          ;; instance of those components embedded in collection or game object
          ;; files, because the embedded components are written as a string
          ;; literal. If you get errors here, you need to ensure the loaded data
          ;; is migrated to the new format by adding a :sanitize-fn when
          ;; registering your resource type (Example: `collision_object.clj`).
          ;; Non-embedded components do not have this issue as long as your
          ;; added protobuf field has a default value. But more drastic file
          ;; format changes have happened in the past, and you can find other
          ;; examples of :sanitize-fn usage in non-component resource types.
          (is (clean?))
          (doseq [[path f] paths]
            (testing (format "Verifying %s" path)
              (let [node-id (test-util/resource-node project path)]
                (is (false? (dirty? node-id))))))
          (doseq [[path f] paths]
            (testing (format "Dirtyfying %s" path)
              (let [node-id (test-util/resource-node project path)]
                (f node-id)
                (is (true? (dirty? node-id))))))
          (is (not (clean?)))
          (save-all! project)
          (is (clean?)))))))

(defn- setup-scratch
  [ws-graph]
  (let [workspace (test-util/setup-scratch-workspace! ws-graph test-util/project-path)
        project (test-util/setup-project! workspace)]
    [workspace project]))

(deftest save-after-delete []
  (with-clean-system
    (let [[workspace project] (setup-scratch world)
          atlas-id (test-util/resource-node project "/switcher/switcher.atlas")]
      (asset-browser/delete [(g/node-value atlas-id :resource)])
      (is (not (g/error? (project/all-save-data project)))))))

(deftest save-after-external-delete []
  (with-clean-system
    (let [[workspace project] (setup-scratch world)
          atlas-id (test-util/resource-node project "/switcher/switcher.atlas")
          path (resource/abs-path (g/node-value atlas-id :resource))]
      (fs/delete-file! (File. path))
      (workspace/resource-sync! workspace)
      (is (not (g/error? (project/all-save-data project)))))))

(deftest save-after-rename []
  (with-clean-system
    (let [[workspace project] (setup-scratch world)
          atlas-id (test-util/resource-node project "/switcher/switcher.atlas")]
      (asset-browser/rename (g/node-value atlas-id :resource) "/switcher/switcher2.atlas")
      (is (not (g/error? (project/all-save-data project)))))))

(defn- resource-line-endings
  [resource]
  (when (and (resource/exists? resource))
    (text-util/scan-line-endings (io/make-reader resource nil))))

(defn- line-endings-by-resource
  [project]
  (into {}
        (keep (fn [{:keys [resource]}]
                (when-let [line-ending-type (resource-line-endings resource)]
                  [resource line-ending-type])))
        (project/all-save-data project)))

(defn- set-autocrlf!
  [^Git git enabled]
  (-> git .getRepository .getConfig (.setString "core" nil "autocrlf" (if enabled "true" "false")))
  nil)

(defn- clean-checkout! [^Git git]
  (doseq [file (-> git .getRepository .getWorkTree .listFiles)]
    (when-not (= ".git" (.getName file))
      (FileUtils/deleteQuietly file)))
  (-> git .reset (.setRef "HEAD") (.setMode ResetCommand$ResetType/HARD) .call)
  nil)

(deftest save-respects-line-endings
  (with-git [project-path (test-util/make-temp-project-copy! test-util/project-path)
             git (git/init project-path)]
    (set-autocrlf! git false)
    (-> git .add (.addFilepattern ".") .call)
    (-> git .commit (.setMessage "init repo") .call)

    (testing "autocrlf false"
      (set-autocrlf! git false)
      (clean-checkout! git)
      (with-clean-system
        (let [workspace (test-util/setup-workspace! world project-path)
              project (test-util/setup-project! workspace)
              line-endings-before (line-endings-by-resource project)
              {:keys [lf crlf] :or {lf 0 crlf 0}} (frequencies (map second line-endings-before))]
          (is (< 100 lf))
          (is (> 100 crlf))
          (project/write-save-data-to-disk! (project/dirty-save-data project) nil)
          (is (= line-endings-before (line-endings-by-resource project))))))

    (testing "autocrlf true"
      (set-autocrlf! git true)
      (clean-checkout! git)
      (with-clean-system
        (let [workspace (test-util/setup-workspace! world project-path)
              project (test-util/setup-project! workspace)
              line-endings-before (line-endings-by-resource project)
              {:keys [lf crlf] :or {lf 0 crlf 0}} (frequencies (map second line-endings-before))]
          (is (> 100 lf))
          (is (< 100 crlf))
          (project/write-save-data-to-disk! (project/dirty-save-data project) nil)
          (is (= line-endings-before (line-endings-by-resource project))))))))

(defn- workspace-file
  ^File [workspace proj-path]
  (assert (= \/ (first proj-path)))
  (File. (workspace/project-path workspace) (subs proj-path 1)))

(defn- spit-file!
  [workspace proj-path content]
  (let [f (workspace-file workspace proj-path)]
    (fs/create-parent-directories! f)
    (spit-until-new-mtime f content)))

(def ^:private slurp-file (comp slurp workspace-file))

(defn- touch-file!
  [workspace proj-path]
  (let [f (workspace-file workspace proj-path)]
    (fs/create-parent-directories! f)
    (touch-until-new-mtime f)))

(def ^:private delete-file! (comp fs/delete-file! workspace-file))

(deftest async-reload-test
  (with-clean-system
    (let [[workspace project] (setup-scratch world)]
      (test-util/run-event-loop!
        (fn [exit-event-loop!]
          (let [dirty-save-data-before (project/dirty-save-data project)]

            ;; Edited externally.
            (touch-file! workspace "/added_externally.md")
            (spit-file! workspace "/script/test_module.lua" "-- Edited externally")

            (disk/async-reload! progress/null-render-progress! workspace [] nil
                                (fn [successful?]
                                  (when (is successful?)

                                    (testing "Save data unaffected."
                                      (is (= dirty-save-data-before (project/dirty-save-data project))))

                                    (testing "External modifications are seen by the editor."
                                      (is (= ["-- Edited externally"] (g/node-value (project/get-resource-node project "/script/test_module.lua") :lines))))

                                    (testing "Externally added files are seen by the editor."
                                      (is (some? (workspace/find-resource workspace "/added_externally.md")))
                                      (is (some? (project/get-resource-node project "/added_externally.md"))))

                                    (testing "Can delete externally added files from within the editor."
                                      (delete-file! workspace "/added_externally.md")
                                      (workspace/resource-sync! workspace)
                                      (is (nil? (workspace/find-resource workspace "/added_externally.md")))
                                      (is (nil? (project/get-resource-node project "/added_externally.md")))))

                                  (exit-event-loop!)))))))))

(deftest async-save-test
  (with-clean-system
    (let [[workspace project] (setup-scratch world)]
      (test-util/run-event-loop!
        (fn [exit-event-loop!]

          ;; Edited by us.
          (test-util/code-editor-source! (test-util/resource-node project "/script/props.script") "-- Edited by us")

          ;; Edited externally.
          (touch-file! workspace "/added_externally.md")
          (spit-file! workspace "/script/test_module.lua" "-- Edited externally")

          (disk/async-save! progress/null-render-progress! progress/null-render-progress! project nil
                            (fn [successful?]
                              (when (is successful?)

                                (testing "No files are still in need of saving."
                                  (let [dirty-proj-paths (into #{} (map (comp resource/proj-path :resource)) (project/dirty-save-data project))]
                                    (is (not (contains? dirty-proj-paths "/added_externally.md")))
                                    (is (not (contains? dirty-proj-paths "/script/props.script")))
                                    (is (not (contains? dirty-proj-paths "/script/test_module.lua")))))

                                (testing "Externally modified files are not overwritten by the editor."
                                  (is (= "-- Edited externally" (slurp (workspace/find-resource workspace "/script/test_module.lua")))))

                                (testing "External modifications are seen by the editor."
                                  (is (= ["-- Edited externally"] (g/node-value (project/get-resource-node project "/script/test_module.lua") :lines))))

                                (testing "Externally added files are seen by the editor."
                                  (is (some? (workspace/find-resource workspace "/added_externally.md")))
                                  (is (some? (project/get-resource-node project "/added_externally.md"))))

                                (testing "Can delete externally added files from within the editor."
                                  (delete-file! workspace "/added_externally.md")
                                  (workspace/resource-sync! workspace)
                                  (is (nil? (workspace/find-resource workspace "/added_externally.md")))
                                  (is (nil? (project/get-resource-node project "/added_externally.md")))))

                              (exit-event-loop!))))))))
