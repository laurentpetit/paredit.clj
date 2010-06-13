(ns paredit.loc-utils
  (:require [clojure.zip :as zip])
  (:require [clojure.contrib.zip-filter :as zf]))

(defn ^String loc-tag [loc]
  (and loc 
    (:tag (zip/node (if (string? (zip/node loc)) (zip/up loc) loc)))))

(defn same-parent? [loc & locs]
  (let [loc-parent-path (butlast (zip/path loc))]
    (every? #(= (butlast (zip/path %)) loc-parent-path) locs)))

(defn loc-depth 
  "returns the depth in the tree of the given loc"
  [loc]
  (count (zip/path loc)))

(defn up-to-depth
  "finds from the loc the ancestor loc at the given depth."
  [loc depth]
  (let [delta (- (loc-depth loc) depth)]
    (cond 
      (zero? delta) loc
      :else (nth (iterate zip/up loc) delta))))

(defn punct-loc?
  "true if the loc corresponds to punctuation."
  [loc]
  (and
    loc
    (string? (zip/node loc)) 
    (not (#{" " "a" ";" "\\" "\""} (loc-tag (zip/up loc))))))

(defn root-loc [loc] (if-let [up (zip/up loc)] (recur up) loc))

(defn rlefts
  "like clojure.zip/lefts, but in reverse order (optimized lazy sequence)"
  [loc]
  (rest (take-while identity (iterate zip/left loc))))

(defn next-leaves
  "seq of next leaves locs" ;; TODO correct this aberration: next-leaves includes the current leave ... (or change the name ...)
  [loc]
  (and loc (remove zip/branch? (take-while (complement zip/end?) (iterate zip/next loc)))))

(defn previous-leaves
  "seq of previous leaves locs"
  [loc]
  (and loc (remove zip/branch? (take-while (complement nil?) (iterate zip/prev (zip/prev loc))))))

(declare start-offset)
(defn end-offset [loc]
  (cond
    (string? (zip/node loc))
      (if-let [l (zip/left loc)]
        (+ (end-offset l) (.length ^String (zip/node loc)))
        (+ (start-offset (zip/up loc)) (.length ^String (zip/node loc))))
    :else 
      (-> loc zip/node :end-offset)))

(defn start-offset [loc]
  (cond
    (nil? loc) 0
    (string? (zip/node loc))
      (if-let [l (zip/left loc)]
        (end-offset l)
        (start-offset (zip/up loc)))
    :else 
      (-> loc zip/node :offset)))

(defn loc-col [loc]
  (loop [loc (zip/prev loc) col 0]
    (cond
      (nil? loc) 
        col
      (string? (zip/node loc))
        (if (.contains (zip/node loc) "\n")
          (+ col (dec (-> (zip/node loc) (.substring (.lastIndexOf (zip/node loc) "\n")) .length)))
          (recur (zip/prev loc) (+ col (.length (zip/node loc)))))
      :else
        (recur (zip/prev loc) col))))

(defn loc-text [loc]
  (apply str (map zip/node 
               (filter (comp string? zip/node) (zf/descendants loc)))))

(defn loc-count [loc]
  (.length ^String (loc-text loc)))
    
  
(defn loc-parse-node [loc] ; wrong name, and also, will return (foo) if located at ( or at ) ... so definitely wrong name ...
  (if (string? (zip/node loc))
    (zip/up loc)
    loc))

(defn parse-leave
  "returns a leave which corresponds to a parse information: either a (punct-loc?) (beware: a bare String, not a node with meta-data,
   or a parse atom" 
  [loc]
  (cond 
    (punct-loc? loc) loc
    (string? (zip/node loc)) (zip/up loc)
    :else loc))

(defn parse-node
  "transforms the loc in a parse-leave, and if a punct, returns the parent loc"
  [loc]
  (let [loc (parse-leave loc)] 
    (if (punct-loc? loc) (zip/up loc) loc)))

(defn parsed-root-loc
  ([parsed] (parsed-root-loc parsed false))
  ([parsed only-valid?]
    (let [valid? (= 1 (-> parsed :accumulated-state count))]
      (when (or valid? (not only-valid?))
        (-> parsed :accumulated-state first zip/xml-zip)))))

(defn contains-offset?
  "returns the loc itself if it contains the offset, else nil"
  [loc offset]
   (and
     (<= (start-offset loc) offset (dec (end-offset loc)))
     loc))

(defn loc-for-offset 
  "returns a zipper location or nil if does not contain the offset"
  ([loc offset] (loc-for-offset loc offset nil))
  ([loc offset last-match]
    (if (or (nil? loc) (not (contains-offset? loc offset)) (not (zip/branch? loc)))
      last-match
      (recur 
        (some  
          #(contains-offset? % offset) 
          (take-while identity (iterate zip/right (zip/down loc)))) 
        offset
        loc))))

(defn leave-for-offset 
  "returns a zipper location of a leave containing or starting at the given offset."
  ([loc offset]
    (let [l (first (filter #(do (zip/node %) (contains-offset? % offset)) (next-leaves loc)))]
      (or l (root-loc loc)))))

(defn loc-containing-offset
  ([loc offset]
    (if (= 0 offset)
      (root-loc loc)
      (let [match (some #(contains-offset? % offset) (take-while (complement zip/end?) (iterate zip/next (zip/next (root-loc loc)))))]
        (cond
          (nil? match) (root-loc loc)
          (= offset (start-offset match)) (zip/up match)
          :else match)))))

(defn start-punct?
  "true if the loc is a punct starting a form"
  [loc]
  (and
    (punct-loc? loc)
    (= (start-offset loc) (start-offset (parse-node loc)))))

(defn end-punct?
  "true if the loc is a punct ending a form"
  [loc]
  (and
    (punct-loc? loc)
    (= (end-offset loc) (end-offset (parse-node loc)))))