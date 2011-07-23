(ns ^{:author "Anthony Rosequist"
      :doc "Some JDK7 NIO functions.

Unless otherwise specified, all path parameters must be
compatible with (to-path)."}
  clj-jdk7.nio
  (:require [clojure.contrib.condition :as con])
  (:import
    [clojure.lang Keyword]
    [java.io File]
    [java.net URI]
    [java.nio.file ClosedWatchServiceException Files FileSystems LinkOption Path Paths StandardWatchEventKinds WatchEvent$Kind]
    [java.nio.file.attribute FileAttribute]))

(def ^{:doc "A java.nio.file.FileSystem object representing the current file system.

  Defaults to FileSystems/getDefault"}
  *file-system*
  (FileSystems/getDefault))

(def ^{:doc "The default directory to use in (create-temp-file)."}
  *default-temp-dir*
  (System/getProperty "java.io.tmpdir"))

(defmulti to-path "Creates a java.nio.file.Path object" class)
(defmethod to-path String [s] (.getPath *file-system* s (into-array String '())))
(defmethod to-path File [f] (.toPath f))
(defmethod to-path URI [u] (Paths/get u))
(defmethod to-path Path [p] p)

(defn- empty-varargs
  "Currently, Clojure requires something to be passed to a Java variadic
  parameter. This helps with that -- given a class, it returns an
  empty array of that class."
  [c]
  (into-array c '()))

(def empty-attrs (empty-varargs FileAttribute))

(defn create-link
  "Creates a link from src to dest.

  symbolic? defaults to false"
  ([src dest]
    (create-link false src dest))
  ([symbolic? src dest]
    (if symbolic?
      (Files/createSymbolicLink (to-path dest) (to-path src) empty-attrs)
      (Files/createLink (to-path dest) (to-path src)))))

(defn dir?
  "Returns true if the path exists and is a directory."
  ([path]
    (dir? true path))
  ([follow-links? path]
    (if follow-links?
      (Files/isDirectory (to-path path) (empty-varargs LinkOption))
      (Files/isDirectory (to-path path) (into-array [LinkOption/NOFOLLOW_LINKS])))))

(defn executable?
  "Returns true if the path exists and is executable."
  [path]
  (Files/isExecutable (to-path path)))

(defn hidden?
  "Returns true if the file is considered hidden."
  [path]
  (Files/isHidden (to-path path)))

(defn readable?
  "Returns true if the file exists and is readable."
  [path]
  (Files/isReadable (to-path path)))

(defn writable?
  "Returns true if the file exists and is writable."
  [path]
  (Files/isWritable (to-path path)))

(defn symlink?
  "Returns true if the file exists and is a symbolic link."
  [path]
  (Files/isSymbolicLink (to-path path)))

(defn create-dir
  "Creates a directory."
  ([path]
    (create-dir true path))
  ([create-parents? path]
    (if create-parents?
      (Files/createDirectories (to-path path) empty-attrs)
      (Files/createDirectory (to-path path) empty-attrs))))

(defn delete
  "Deletes a path."
  ([path]
    (delete false path))
  ([fail-on-missing? path]
    (if fail-on-missing?
      (Files/delete (to-path path))
      (Files/deleteIfExists (to-path path)))))

(defn create-temp-file
  "Creates a temp file.

  opts may include any (or none) of:

  :dir the path to the directory in which to create the file
  :prefix a prefix string to be used in generating the file name
  :suffix a suffix string to be used in generating the file name

  If dir isn't specified, *default-temp-dir* is used."
  [& opts]
  (let [opt-map (apply hash-map opts)
        dir (or (:dir opt-map) *default-temp-dir*)
        prefix (:prefix opt-map)
        suffix (:suffix opt-map)]
    (Files/createTempFile (to-path dir) prefix suffix empty-attrs)))

(defmacro with-temp-file
  "bindings => [name init...] or name

  Evaluates body in a try expression with names bound to the values
  of the inits, and a finally clause that calls (.delete name) on each
  name in reverse order.

  If a single name is used instead of a bindings vector, it is bound
  to the result of (create-temp-file)."
  [bindings & body]
  (if (vector? bindings)
    (cond
      (empty? bindings) `(do ~@body)
      (odd? (count bindings)) (con/raise :type ::Args :message "with-temp-file requires an even number of forms in the binding vector")
      (not (symbol? (bindings 0))) (con/raise :type ::Args :message "with-temp-file only allows Symbols in bindings")
      :else (let [name    (bindings 0)
                  init-fn (bindings 1)
                  rest    (subvec bindings 2)]
              `(let [~name ~init-fn]
                 (try
                   (with-temp-file ~rest ~@body)
                   (finally
                     (delete ~name))))))
    (cond
      (not (symbol? bindings)) (con/raise :type ::Args :message "with-temp-file requires a Symbol")
      :else `(with-temp-file [~bindings (create-temp-file)]
               ~@body))))

(def events
  {:create StandardWatchEventKinds/ENTRY_CREATE
   :delete StandardWatchEventKinds/ENTRY_DELETE
   :modify StandardWatchEventKinds/ENTRY_MODIFY})

(defmulti to-event class)
(defmethod to-event Keyword [k] (events k))
(defmethod to-event WatchEvent$Kind [k] k)

(defn add-fs-watch
  "Adds a watch function to filesystem changes. I don't like this
  function's definition -- it needs to be fixed later.

  watch fn must be a fn of 2 args: the file that was affected (as
  a Path object) and the type of event (one of the evts parameters).

  path is the directory to watch

  evts must be a seq of objects compatible with (to-event),
  typically :create, :delete, and :modify.

  opts can include any (or none) of:

  :daemon? a boolean to indicate whether the watch thread should be a daemon
  :overflow-fn a fn with 2 args (same as watch-fn) to catch overflow events

  If unspecified, daemon? is false and overflow-fn does nothing.

  Returns a function that will remove this watch when invoked."
  [watch-fn path evts & opts]
  (let [opt-map (apply hash-map opts)
        daemon? (:daemon? opt-map)
        overflow-fn (or (:overflow-fn opt-map) (fn [_ _]))
        watch-service (.newWatchService *file-system*)
        evt-map (into {} (for [evt evts] {evt (to-event evt)}))
        arg-map (into {} (for [e evt-map] {(val e) (key e)}))
        watching? (atom true)]
    (.register (to-path path) watch-service (into-array (map to-event evts)))
    (doto
      (Thread. #(while @watching?
                  (try
                    (let [watch-key (.take watch-service)]
                      (doseq [evt (.pollEvents watch-key)]
                        (if (= (.kind evt) (StandardWatchEventKinds/OVERFLOW))
                          (overflow-fn (.context evt) (arg-map (.kind evt)))
                          (watch-fn (.context evt) (arg-map (.kind evt)))))
                      (.reset watch-key))
                    (catch ClosedWatchServiceException e))))
      (.setDaemon daemon?)
      (.start))
    (fn []
      (reset! watching? false)
      (.close watch-service))))