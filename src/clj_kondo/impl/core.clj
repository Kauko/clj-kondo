(ns clj-kondo.impl.core
  "Implementation details of clj-kondo.core"
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [clj-kondo.impl.analysis.java :as java]
   [clj-kondo.impl.analyzer :as ana]
   [clj-kondo.impl.config :as config]
   [clj-kondo.impl.findings :as findings]
   [clj-kondo.impl.utils :as utils :refer [one-of print-err! map-vals assoc-some
                                           ->uri]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str])
  (:import [java.util.jar JarFile JarFile$JarFileEntry]))

(set! *warn-on-reflection* true)

(def dev? (= "true" (System/getenv "CLJ_KONDO_DEV")))

(def version
  (str/trim
   (slurp (io/resource "CLJ_KONDO_VERSION"))))

(def cache-version "v1")

(defn format-output [config]
  (if-let [^String pattern (-> config :output :pattern)]
    (fn [filename row col level message]
      (-> pattern
          (str/replace "{{filename}}" filename)
          (str/replace "{{row}}" (str row))
          (str/replace "{{col}}" (str col))
          (str/replace "{{level}}" (name level))
          (str/replace "{{LEVEL}}" (str/upper-case (name level)))
          (str/replace "{{message}}" message)))
    (fn [filename row col level message]
      (str filename ":" row ":" col ": " (name level) ": " message))))

;;;; process config

(declare read-edn-file)

(defn read-fn
  [^java.io.File cfg-file process-fn file-to-read]
  (let [f (io/file (.getParent cfg-file) file-to-read)]
    (if (.exists f)
      (process-fn f)
      (binding [*out* *err*]
        (println "WARNING: included file" (.getCanonicalPath f) "does not exist.")))))

(defn opts [^java.io.File cfg-file]
  (let [include #(read-fn cfg-file read-edn-file %)]
    {:readers
     {'include include
      ;;'include-edn include
      ;;'include-string #(read-fn cfg-file slurp %)
      }}))

(defn read-edn-file [^java.io.File f]
  (try (edn/read-string (opts f) (slurp f))
       (catch Exception e
         (binding [*out* *err*]
           (println "WARNING: error while reading"
                    (.getCanonicalPath f) (format "(%s)" (.getMessage e)))))))

(defn- read-config [config]
  (cond (map? config)
        config
        (string? config)
        (if (or (str/starts-with? config "{")
                (str/starts-with? config "^"))
          (edn/read-string config)
          ;; config is a string that represents a file
          (read-edn-file (io/file config)))))

(declare resolve-config-paths)

(defn process-cfg-dir
  "Reads config from dir if not already passed and adds dir to
  classpath (even if there is no config in it)."
  ([dir]
   (let [cfg (let [dir (io/file dir)
                   f (io/file dir "config.edn")]
               (when (.exists f)
                 (read-edn-file f)))]
     (process-cfg-dir dir cfg)))
  ([dir cfg]
   (let [cfg (assoc cfg :classpath [dir])]
     (resolve-config-paths dir cfg))))

(defn sanitize-path [cfg-dir path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (when (.exists f) f)
      (let [f (io/file cfg-dir path)]
        (when (.exists f)
          f)))))

(defn sanitize-paths [cfg-dir paths]
  (keep #(sanitize-path cfg-dir %) paths))

(defn home-config []
  (let [home-dir  (if-let [xdg-config-home (System/getenv "XDG_CONFIG_HOME")]
                    (io/file xdg-config-home "clj-kondo")
                    (io/file (System/getProperty "user.home") ".config" "clj-kondo"))]
    (when (.exists home-dir)
      (process-cfg-dir home-dir))))

(defn resolve-config-paths
  "Takes config from .clj-kondo/config.edn (or other cfg-dir),
  inspects :config-paths, merges configs from left to right with cfg
  last."
  [cfg-dir cfg]
  (if-let [config-paths (seq (:config-paths cfg))]
    (if-let [paths (sanitize-paths cfg-dir config-paths)]
      (let [configs (map process-cfg-dir paths)
            merged (reduce config/merge-config! nil configs)
            ;; cfg is merged last
            cfg (config/merge-config! merged cfg)]
        cfg)
      cfg)
    cfg))

(defn resolve-config [^java.io.File cfg-dir configs debug]
  (let [local-config (when cfg-dir
                       (let [f (io/file cfg-dir "config.edn")]
                         (when (.exists f)
                           (read-edn-file f))))
        auto-load-configs? (and cfg-dir
                                (not (false? (:auto-load-configs local-config))))
        local-config-paths (:config-paths local-config)
        local-config-paths-set (set local-config-paths)
        discovered (when auto-load-configs?
                     (into []
                           (comp (map fs/parent)
                                 (map #(fs/relativize cfg-dir %))
                                 (map str)
                                 (filter #(not (contains? local-config-paths-set %))))
                           (fs/glob cfg-dir "**/**/config.edn"
                                    {:max-depth 3})))
        _ (when (and debug
                     auto-load-configs?
                     (seq discovered))
            (binding [*out* *err*]
              (run! #(println "[clj-kondo] Auto-loading config path:" %) discovered)))
        skip-home? (some-> local-config-paths meta :replace)
        ;; local config exists implicitly when configs are discovered, even when
        ;; local-config was nil
        local-config (if (seq discovered)
                       (update local-config :config-paths
                               (fnil into []) (distinct) discovered)
                       local-config)
        config
        (reduce config/merge-config!
                config/default-config
                (cons
                 (when-not skip-home? (home-config))
                 (cons (when cfg-dir (process-cfg-dir cfg-dir local-config))
                       (map read-config configs))))]
    (cond-> config
      cfg-dir (assoc :cfg-dir (.getCanonicalPath cfg-dir)))))

;;;; process cache

(defn resolve-cache-dir [cfg-dir cache cache-dir]
  (when-let [cache-dir (or cache-dir
                           ;; for backward compatibility
                           (when-not (true? cache)
                             cache)
                           (when cfg-dir (io/file cfg-dir ".cache")))]
    (io/file cache-dir cache-version)))

;;;; find cache/config dir

(defn source-file? [filename]
  (when-let [[_ ext] (re-find #"\.(\w+)$" filename)]
    (one-of (keyword ext) [:clj :cljs :cljc :edn])))

(defn config-dir
  ([] (config-dir
       (io/file
        (System/getProperty "user.dir"))))
  ([start]
   (let [start (io/file start)
         ;; NOTE: .getParentFile doesn't work on relative files
         start (.getAbsoluteFile start)
         start (if (.isFile start)
                 (.getParentFile start)
                 start)]
     (when start
       (loop [dir start]
         (let [cfg-dir (io/file dir ".clj-kondo")]
           (if (.exists cfg-dir)
             (if (.isDirectory cfg-dir)
               cfg-dir
               (throw (Exception. (str cfg-dir " must be a directory"))))
             (when-let [parent (.getParentFile dir)]
               (recur parent)))))))))

;;;; jar processing

(defn copy-config-entry
  [ctx entry-name source cfg-dir]
  (try
    (let [dirs (str/split entry-name #"/")
          root (rest (drop-while #(not= "clj-kondo.exports" %) dirs))
          copied-dir (apply io/file (take 2 root))
          dest (apply io/file cfg-dir root)]
      (swap! (:detected-configs ctx) conj (str copied-dir))
      (io/make-parents dest)
      (spit dest source))
    (catch Exception e (prn (.getMessage e)))))

(defn sources-from-jar
  [ctx ^java.io.File jar-file canonical?]
  (with-open [jar (JarFile. jar-file)]
    (let [cfg-dir (:config-dir ctx)
          entries (enumeration-seq (.entries jar))
          entries (filter (fn [^JarFile$JarFileEntry x]
                            (let [nm (.getName x)]
                              (when-not (.isDirectory x)
                                (when (or (str/ends-with? nm ".class")
                                          (str/ends-with? nm ".java"))
                                  (when (and (java/analyze-class-defs? ctx)
                                             (not (str/includes? nm "$"))
                                             (not (str/ends-with? nm "__init.class")))
                                    (java/reg-class-def! ctx {:jar (if canonical?
                                                                          (str (.getCanonicalPath jar-file))
                                                                          (str jar-file))
                                                                   :entry nm})))
                                (source-file? nm)))) entries)]
      ;; Important that we close the `JarFile` so this has to be strict see GH
      ;; issue #542. Maybe it makes sense to refactor loading source using
      ;; transducers so we don't have to load the entire source of a jar file in
      ;; memory at once?
      (into [] (keep (fn [^JarFile$JarFileEntry entry]
                       (let [entry-name (.getName entry)
                             source (slurp (.getInputStream jar entry))]
                         (if (and cfg-dir
                                  (str/includes? entry-name "clj-kondo.exports"))
                           ;; never lint exported hook code
                           (when (:copy-configs ctx)
                             ;; only copy when copy-configs is true
                             (copy-config-entry ctx entry-name source cfg-dir))
                           {:uri (->uri (str (.getCanonicalPath jar-file)) entry-name nil)
                            :filename (str (when canonical?
                                             (str (.getCanonicalPath jar-file) ":"))
                                           entry-name)
                            :source source
                            :group-id jar-file}))))
            entries))))

;;;; dir processing

(def file-pat
  (re-pattern (str/re-quote-replacement (System/getProperty "file.separator"))))

(defn copy-config-file
  [ctx path cfg-dir]
  (try
    (let [base-file (str path)
          dirs (str/split base-file file-pat)
          root (rest (drop-while #(not= "clj-kondo.exports" %) dirs))
          copied-dir (apply io/file (take 2 root))
          dest (apply io/file cfg-dir root)]
      (swap! (:detected-configs ctx) conj (str copied-dir))
      (io/make-parents dest)
      (io/copy (io/file base-file) dest))
    (catch Exception e (prn (.getMessage e)))))

(defn seen?
  "Atomically adds f to the seen atom and returns if it changed or not."
  [f seen debug]
  (let [[old new]
        (swap-vals! seen conj f)
        seen? (= old new)]
    (when (and debug seen?)
      (utils/stderr "[clj-kondo] Already seen the file" f "before, skipping"))
    seen?))

(defn sources-from-dir
  [ctx dir canonical?]
  (let [seen (:seen-files ctx)
        cfg-dir (:config-dir ctx)
        files (file-seq dir)
        debug (:debug ctx)]
    (keep (fn [^java.io.File file]
            (let [canonical (.getCanonicalPath file)]
              (when-not (seen? canonical seen debug)
                (let [path (.getPath file)
                      nm (if canonical?
                           (.getCanonicalPath file)
                           (.getPath file))
                      can-read? (.canRead file)
                      is-file? (.isFile file)
                      _ (when (and is-file?
                                   (java/analyze-class-defs? ctx)
                                   (or (str/ends-with? nm ".class")
                                       (str/ends-with? nm ".java")))
                          (java/reg-class-def! ctx {:file nm}))
                      source? (and is-file? (source-file? nm))]
                  (if (and cfg-dir source?
                           (str/includes? path "clj-kondo.exports"))
                    ;; never lint exported hook code, when coming from dir.
                    ;; should be ok when editing single hook file, it won't be persisted to cache
                    (when (:copy-configs ctx)
                      ;; only copy when copy-configs is true
                      (copy-config-file ctx file cfg-dir))
                    (cond
                      (and can-read? source?)
                      {:uri (->uri nil nil nm)
                       :filename nm
                       :source (slurp file)
                       :group-id dir}
                      (and (not can-read?) source?)
                      (print-err! (str nm ":0:0:") "warning: can't read, check file permissions")
                      :else nil))))))
          files)))

;;;; threadpool

(defn lint-task [ctx ^java.util.concurrent.LinkedBlockingDeque deque dev?]
  (loop []
    (when-let [group (.pollFirst deque)]
      (try
        (doseq [{:keys [:filename :source :lang :uri]} group]
          (ana/analyze-input ctx filename uri source lang dev?))
        (catch Exception e (binding [*out* *err*]
                             (prn e))))
      (recur))))

(defn parallel-lint [ctx sources dev?]
  (let [source-groups (group-by :group-id sources)
        source-groups (filter seq (vals source-groups))
        deque     (java.util.concurrent.LinkedBlockingDeque. ^java.util.List source-groups)
        _ (reset! (:sources ctx) []) ;; clean up garbage
        cnt       (+ 2 (int (* 0.6 (.. Runtime getRuntime availableProcessors))))
        latch     (java.util.concurrent.CountDownLatch. cnt)
        es        (java.util.concurrent.Executors/newFixedThreadPool cnt)]
    (dotimes [_ cnt]
      (.execute es
                (bound-fn []
                  (lint-task ctx deque dev?)
                  (.countDown latch))))
    (.await latch)
    (.shutdown es)))

;;;; file processing

(defn lang-from-file [file default-language]
  (if-let [[_ ext] (re-find #"\.(\w+)$" file)]
    (let [k (keyword ext)]
      (or (get #{:clj :cljs :cljc :edn} k)
          default-language))
    default-language))

(def path-separator (System/getProperty "path.separator"))

(defn classpath? [f]
  (str/includes? f path-separator))

(defn schedule [ctx {:keys [:filename :source :lang :uri] :as m} dev?]
  (swap! (:files ctx) inc)
  (if (:parallel ctx)
    (swap! (:sources ctx) conj m)
    (when-not (:skip-lint ctx)
      (ana/analyze-input ctx filename uri source lang dev?))))

(defn process-file [ctx path default-language canonical? filename]
  (let [seen-files (:seen-files ctx)]
    (try
      (let [path (str path) ;; always assume path to be a string in the body of
            ;; this function
            file (io/file path) ;; and file to be a java.io.File
            canonical (when (.exists file)
                        ;; calling canonical-path on non-existing
                        ;; files (e.g. classpaths) can cause errors
                        (.getCanonicalPath file))
            debug (:debug ctx)]
        (cond
          canonical ;; implies the file exiss
          (if (.isFile file)
            (when-not (seen? canonical seen-files debug)
              (if (str/ends-with? canonical ".jar")
                ;; process jar file
                (let [jar-name (.getName file)
                      config-hash (force (:config-hash ctx))
                      cache-dir (:cache-dir ctx)
                      skip-mark (str jar-name "." config-hash)
                      skip-entry (when cache-dir (io/file cache-dir "skip" skip-mark))]
                  (if (and cache-dir (:dependencies ctx)
                           (not (str/includes? jar-name "SNAPSHOT"))
                           (.exists skip-entry)
                           (= path (slurp skip-entry)))
                    (utils/stderr "[clj-kondo]" jar-name "was already linted, skipping")
                    (do (run! #(schedule ctx (assoc % :lang (lang-from-file (:filename %) default-language))
                                         dev?)
                              (sources-from-jar ctx file canonical?))
                        (swap! (:mark-linted ctx) conj [skip-mark path]))))
                ;; assume normal source file
                (let [fn (if canonical?
                           canonical
                           path)]
                  (if (str/ends-with? canonical ".java")
                    (java/reg-class-def! ctx {:file canonical})
                    (schedule ctx {:filename fn
                                   :uri (->uri nil nil fn)
                                   :source (slurp file)
                                   :lang (lang-from-file path default-language)}
                              dev?)))))
            ;; assume directory
            (run! #(schedule ctx (assoc % :lang (lang-from-file (:filename %) default-language)) dev?)
                  (sources-from-dir ctx file canonical?)))
          (= "-" path)
          (schedule ctx {:filename (or filename "<stdin>")
                         :source (slurp *in*)
                         :lang (if filename
                                 (lang-from-file filename default-language)
                                 default-language)} dev?)
          (classpath? path)
          (run! #(process-file ctx % default-language canonical? filename)
                (str/split path
                           (re-pattern path-separator)))
          :else
          (when-not (:skip-lint ctx)
            (findings/reg-finding! ctx
                                   {:filename (if canonical?
                                              ;; canonical path on weird file
                                              ;; crashes on Windows
                                                (try (.getCanonicalPath file)
                                                     (catch Exception _ path))
                                                path)
                                    :type :file
                                    :col 0
                                    :row 0
                                    :message "file does not exist"}))))
      (catch Throwable e
        (if dev?
          (throw e)
          (when-not (:skip-lint ctx)
            (findings/reg-finding! ctx {:filename (if canonical?
                                                    (.getCanonicalPath (io/file path))
                                                    path)
                                        :type :file
                                        :col 0
                                        :row 0
                                        :message "Could not process file."})))))))

(defn inactive-config-imports [ctx]
  (when-let [cfg-dir (io/file (:config-dir ctx))]
    (when-let [new-configs (-> (set/difference (->> ctx
                                                    :detected-configs
                                                    deref
                                                    (map utils/unixify-path)
                                                    set)
                                               (->> ctx
                                                    :config
                                                    :config-paths
                                                    (map utils/unixify-path)
                                                    set))
                               vec
                               sort
                               seq)]
      (let [rel-cfg-dir (str (if (.isAbsolute cfg-dir)
                               (.relativize (.normalize (.toPath (.getAbsoluteFile (io/file "."))))
                                            (.normalize (.toPath cfg-dir)))
                               cfg-dir))]
        (for [new-config new-configs]
          {:imported-config (-> (io/file rel-cfg-dir new-config) str utils/unixify-path)
           :suggested-config-path (str \" new-config \")
           :config-file (-> (io/file rel-cfg-dir "config.edn") str utils/unixify-path)})))))

(defn print-inactive-config-imports [inactives]
  (binding [*out* *err*]
    (doseq [{:keys [imported-config suggested-config-path config-file]} inactives]
      (println (format "Imported config to %s. To activate, add %s to :config-paths in %s."
                       imported-config suggested-config-path config-file)))))

(defn process-files [ctx files default-lang filename]
  (let [ctx (assoc ctx :seen-files (atom #{}))
        cache-dir (:cache-dir ctx)
        ctx (assoc ctx :detected-configs (atom [])
                   :mark-linted (atom []))
        canonical? (-> ctx :config :output :canonical-paths)]
    (run! #(process-file ctx % default-lang canonical? filename) files)
    (when (and (:parallel ctx)
               (not (:skip-lint ctx)))
      (parallel-lint ctx @(:sources ctx) dev?))
    (when (and cache-dir (:dependencies ctx))
      (doseq [[mark path] @(:mark-linted ctx)]
        (let [skip-file (io/file cache-dir "skip" mark)]
          (io/make-parents skip-file)
          (spit skip-file path))))
    (print-inactive-config-imports (inactive-config-imports ctx))))

;;;; index defs and calls by language and namespace

(defn mmerge
  "Merges maps no deeper than two levels"
  [a b]
  (merge-with merge a b))

(defn format-vars [vars]
  (map-vals (fn [md]
              (-> md
                  (select-keys [:row :col
                                :macro :private :deprecated
                                :fixed-arities :varargs-min-arity
                                :name :ns :top-ns :imported-ns :imported-var
                                :arities])))
            vars))

(defn namespaces->indexed [namespaces]
  (when namespaces
    (map-vals (fn [{:keys [:filename :vars :proxied-namespaces]}]
                (some-> (assoc-some (format-vars vars)
                                    :proxied-namespaces proxied-namespaces)
                        (assoc :filename filename)))
              namespaces)))

(defn namespaces->indexed-cljc [namespaces lang]
  (when namespaces
    (map-vals (fn [v]
                (let [vars (:vars v)
                      filename (:filename v)]
                  {:filename filename
                   lang (format-vars vars)}))
              namespaces)))

(defn namespaces->indexed-defs [ctx]
  (let [namespaces @(:namespaces ctx)
        clj (namespaces->indexed (get-in namespaces [:clj :clj]))
        cljs (namespaces->indexed (get-in namespaces [:cljs :cljs]))
        cljc-clj (namespaces->indexed-cljc (get-in namespaces [:cljc :clj])
                                           :clj)
        cljc-cljs (namespaces->indexed-cljc (get-in namespaces [:cljc :cljs])
                                            :cljs)]
    {:clj {:defs clj}
     :cljs {:defs cljs}
     :cljc {:defs (mmerge cljc-clj cljc-cljs)}}))

(defn index-defs-and-calls [ctx]
  (let [indexed-defs (namespaces->indexed-defs ctx)]
    (assoc indexed-defs :used-namespaces @(:used-namespaces ctx))))

;;;; summary

(def zinc (fnil inc 0))

(defn summarize [findings]
  (reduce (fn [acc finding]
            (let [level (:level finding)]
              (update acc level zinc)))
          {:error 0 :warning 0 :info 0 :type :summary}
          findings))

;;;; filter/remove output

(defn filter-findings [config findings]
  (let [print-debug? (:debug config)
        filter-output (not-empty (-> config :output :include-files))
        remove-output (not-empty (-> config :output :exclude-files))]
    (for [f findings
          :let [filename (:filename f)
                tp (:type f)
                level (:level f)]
          :when (and filename level (not= :off level))
          :when (if (= :debug tp)
                  print-debug?
                  true)
          :when (if filter-output
                  (some (fn [pattern]
                          (re-find (re-pattern pattern) filename))
                        filter-output)
                  true)
          :when (not-any? (fn [pattern]
                            (re-find (re-pattern pattern) filename))
                          remove-output)]
      f)))

;;;; aux

(defn config-hash [cfg]
  (let [config-bytes (.getBytes (str cfg))
        digest (java.security.MessageDigest/getInstance "SHA-256")
        config-hash (.digest digest config-bytes)
        config-hash (format "%032x" (BigInteger. 1 config-hash))]
    config-hash))

;;;; Scratch

(comment)
