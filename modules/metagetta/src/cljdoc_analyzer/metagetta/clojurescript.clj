(ns ^:no-doc cljdoc-analyzer.metagetta.clojurescript
  "Read raw documentation information from ClojureScript source directory."
  (:require [clojure.java.io :as io]
            [cljs.analyzer.api :as ana]
            [cljs.compiler.api :as comp]
            [cljs.env]
            [cljdoc-analyzer.metagetta.utils :as utils]))

(defn- cljs-filename? [filename]
  (or (.endsWith filename ".cljs")
      (.endsWith filename ".cljc")))

(defn- cljs-file? [file]
  (and (.isFile file)
       (-> file .getName cljs-filename?)))

(defn- remove-quote [x]
  (if (and (seq? x) (= (first x) 'quote))
    (second x)
    x))

(defn- strip-parent [parent]
  (let [len (inc (count (.getPath parent)))]
    (fn [child]
      (let [child-name (.getPath child)]
        (when (>= (count child-name) len)
          (io/file (subs child-name len)))))))

(defn- find-files [file]
  (when (.isDirectory file)
    (->> (file-seq file)
         (filter cljs-file?)
         (keep (strip-parent file)))))

(defn- protocol-methods [protocol vars]
  (let [proto-name (name (:name protocol))]
    (filter #(if-let [p (:protocol %)] (= proto-name (name p))) vars)))

(defn- multimethod? [var]
  (= (:tag var) 'cljs.core/MultiFn))

(defn- var-type [opts]
  (cond
   (:macro opts)           :macro
   (:protocol-symbol opts) :protocol
   (multimethod? opts)     :multimethod
   :else                   :var))

(defn- read-var [source-path file vars var]
  (let [vt (var-type var)
        normalize (partial utils/normalize-to-source-path source-path)]
    (-> var
        (select-keys [:name :file :line :arglists :doc :dynamic
                      :added :deprecated
                      :no-doc :skip-wiki])
        (utils/update-some :name (comp symbol name))
        (utils/update-some :arglists remove-quote)
        (utils/update-some :doc utils/correct-indent)
        (utils/update-some :file normalize)
        (utils/assoc-some  :type    vt
                     :members (->> (protocol-methods var vars)
                                   (map (partial read-var source-path file vars))
                                   (map utils/remove-empties)
                                   (map #(dissoc % :file :line))))
        utils/remove-empties)))

(defn- unreferenced-protocol-fn?
  "Tools like potemkin import-vars can create a new function in one namespace point to an existing function within a protocol.
  In these cases, we want to include the new function."
  [source-path actual-file vars]
  (let [meta-file (utils/normalize-to-source-path source-path (:file vars))
        actual-file (utils/normalize-to-source-path source-path (str actual-file))]
    (and (:protocol vars) (= meta-file actual-file))))

(defn- read-publics [state namespace source-path file]
  (let [vars (vals (ana/ns-publics state namespace))
        unreferenced-protocol? (partial unreferenced-protocol-fn? source-path file)]
    (->> vars
         (remove :anonymous)
         (remove unreferenced-protocol?)
         (map (partial read-var source-path file vars)))))

(defn- fakenize-namespace!
  "For each required namespace which is a string, generate a fake js module in
  the compiler environment.
  Return the updated compiler environment, 'state'.

  Rational:
  Required namespaces that are strings correspond to js library used by the package
  currently parsed. The clojurescript package generally doesn't involved their js
  libraries. The js dependencies are usually managed by npm/yarn package managed.
  https://github.com/cljdoc/cljdoc-analyzer/issues/18"
  [state js-dependencies]
  ;; fake all string requires since otherwise npm indexing is required
  (doseq [dependency js-dependencies
          :when (not (contains? (:js-dependency-index @state) dependency))]
    (swap! state assoc-in [:js-dependency-index dependency] (gensym "fake$module")))
  state)

(defn- analyze-file [js-dependencies file]
  (let [state
        (fakenize-namespace! (cljs.env/default-compiler-env) js-dependencies)]
    (ana/no-warn
     ;; The 'with-core-cljs' wrapping function ensures the namespace 'cljs.core'
     ;; is available under the sub-call to 'analyze-file'.
     ;; https://github.com/cljdoc/cljdoc/issues/261
     (comp/with-core-cljs state nil #(ana/analyze-file file)))
    state))

(defn- read-file [source-path js-dependencies file exception-handler]
  (try
    (let [source  (io/file source-path file)
          ns-name (:ns (ana/parse-ns source))
          state   (analyze-file js-dependencies source)]
      {ns-name
       (-> (ana/find-ns state ns-name)
           (select-keys [:name :doc])
           (utils/update-some :doc utils/correct-indent)
           (merge (-> ns-name meta (select-keys [:no-doc :skip-wiki :author :deprecated :added])))
           (utils/remove-empties)
           (assoc :publics (read-publics state ns-name source-path file)))})
    (catch Exception e
      (exception-handler e file))))

(defn- ns-merger [val-first val-next]
  (update val-first :publics #(seq (into (set %) (:publics val-next)))))

(defn- get-js-package-dependencies
  "Compute the set of all js dependencies used by a package from its root path.

  Example: for the package 'lilactown-hx-0.5.2', #{\"react\"} is returned."
  [package-root-path]
  (->> (find-files package-root-path)
       (map (fn [file]
              (->> file
                   (io/file package-root-path)
                   (ana/parse-ns)
                   :requires)))
       (reduce clojure.set/union)
       (filter string?)
       (into #{})))

(defn read-namespaces
  "Read ClojureScript namespaces from a source directory and return
  a list of namespaces with their public vars.

  Supported options using the second argument:
    :exception-handler - function (fn [ex file]) to handle exceptions
    while reading a namespace

  The keys in the maps are:
    :name      - the name of the namespace
    :doc       - the doc-string on the namespace
    :author    - if the metadata is there, we return it
    :no-doc    - request for namespace not to be documented
    :skip-wiki - legacy synonym for :no-doc
    :publics
      :name       - the name of a public function, macro, or value
      :file       - the file the var was declared in
      :line       - the line at which the var was declared
      :arglists   - the arguments the function or macro takes
      :doc        - the doc-string of the var
      :type       - one of :macro, :protocol or :var
      :added      - the library version the var was added in
      :deprecated - the library version the var was deprecated in
      :no-doc     - request for var not to be documented
      :skip-wiki    - legacy synonym for :no-doc"
  ([path] (read-namespaces path {}))
  ([path {:keys [exception-handler]
           :or {exception-handler (partial utils/default-exception-handler "ClojureScript")}}]
   (let [path (io/file (utils/canonical-path path))
         js-dependencies (get-js-package-dependencies path)
         file-reader #(read-file path js-dependencies % exception-handler)]
     (->> (find-files path)
          (map file-reader)
          (apply merge-with ns-merger)
          (vals)))))
