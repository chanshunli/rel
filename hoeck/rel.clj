; writer   Copyright (c) 2008, Erik Soehnel All rights reserved.
;
;   Redistribution and use in source and binary forms, with or without
;   modification, are permitted provided that the following conditions
;   are met:
;
;     * Redistributions of source code must retain the above copyright
;       notice, this list of conditions and the following disclaimer.
;
;     * Redistributions in binary form must reproduce the above
;       copyright notice, this list of conditions and the following
;       disclaimer in the documentation and/or other materials
;       provided with the distribution.
;
;   THIS SOFTWARE IS PROVIDED BY THE AUTHOR 'AS IS' AND ANY EXPRESSED
;   OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
;   WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
;   ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
;   DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
;   DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
;   GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
;   INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
;   WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
;   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
;   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns hoeck.rel
  (:require ;[hoeck.rel.sql  :as sql]
            ;[hoeck.rel.sql-utils  :as sql-utils]
            [hoeck.rel.operators :as op]
            ;[hoeck.rel.iris :as iris]
            [hoeck.rel.structmaps :as st]
            [hoeck.rel.conditions :as cd]
            [hoeck.rel.testdata :as td]
            hoeck.magic-map.MagicMap ;; my broken compile needs to load the namespaces here
            hoeck.value-mapped-map.ValueMappedMap)
  (:use hoeck.library
        hoeck.magic-map
        hoeck.value-mapped-map
        hoeck.rel.conditions
        clojure.contrib.def
        clojure.contrib.duck-streams
        clojure.contrib.pprint))

(defmacro defaliases [& name-orig-pairs]
  `(do ~@(map #(list `defalias (first %) (second %)) (partition 2 name-orig-pairs))))

(defmacro def-lots-of-aliases 
  "Define aliases from namespace-name/sym to sym.
  body syntax: (namespace-name symbols-to-alias-here*)*"
  [& body]
  `(do ~@(mapcat (fn [[ns & lots-of-aliases]]
                   (map (fn [sym]
                          `(defalias ~sym ~(symbol (str ns) (str sym))))
                        lots-of-aliases))
          body)))

;;(def-lots-of-aliases
;;   (op make-relation) ;; union intersection difference
;;   (iris with-empty-universe <- ?-)
;;   )

(defalias make-relation op/make-relation)


;; global relation

(def *relations* {})

(defn relation-or-lookup [keyword-or-relation]
  (if (keyword? keyword-or-relation) 
    (keyword-or-relation *relations*)
    keyword-or-relation))

(defn fields [R]
  (op/fields (relation-or-lookup R)))

(defn relations 
  "Return a relation of [:field :name] of all relations
currently bound to *relations*"
  []
  (make-relation (doall (mapcat (fn [r] (map #(hash-map :relation r
                                                        :field %)
                                             (fields r)))
                                (keys *relations*)))))

;; rename

(defn rename* [R name-newname-map]
  (op/rename (relation-or-lookup R) name-newname-map))

(defmacro rename [R & name-newname-pairs]
  `(rename* ~R (hash-map ~@name-newname-pairs)))

(defn as
  "rename all fields of a relation such that they have a common prefix"
  [R prefix]
  (rename* R (zipmap (fields R) (map #(keyword (str prefix "-" (name %))) (fields R)))))


;; select

(defn select*
  [R condition]
  (op/select (relation-or-lookup R) condition))

(defmacro select 
  ;; todo: pattern-like matching, eg: [a ? b] matches (condition (and (= *0 a) (= *2 b)))
  ;;       or {:name 'mathias} matches, computes to ->  #{[1 mathias weiland] [2 mathias probst]} == (condition (= *name 'mathias))
  ;;       maybe with nested, destructuring-like expressions like {:name #{mathias, robert}}
  ;;       or [? #{mathias, robert} dresden]
  ;;       or [(< 10 ?) ? (not= #{dresden, rostock})]
  ;;        -> use all of {[()]} 
  ;;       multiarg-select: keyword value -> hashmap access like `get' where (name :keyword) == field-name
  ;;       => qbe ????
  "Macro around select."
  [R condition]
  `(select* (relation-or-lookup ~R) (cd/condition ~condition)))


;; project

(defn project* [R condition-list]
  (op/project (relation-or-lookup R) condition-list))

(defmacro project
  "Convienience macro for the project operation."
  [R & exprs]
  `(project* (relation-or-lookup ~R) 
             (list ~@(map #(cond (op/field? %)
                                   %
                                 (vector? %)
                                   `(cd/condition ~(first %) ~(second %))
                                 :else 
                                   `(cd/condition ~%))
                          exprs))))

;; union, difference, intersection

(defn make-set-op-fn
  "Return a function which extends the given 2-argument set function
  to take more than 2 relations."
  [set-op]
  (fn me 
    ([R] (relation-or-lookup R))
    ([R S] (set-op (relation-or-lookup R) (relation-or-lookup S)))
    ([R S & more]
       (apply me (set-op (relation-or-lookup R) (relation-or-lookup S)) more))))

(def union (make-set-op-fn op/union))
(def intersection (make-set-op-fn op/intersection))
(def difference (make-set-op-fn op/difference))


;; joins

(defn make-join-op-fn [join-op]
  "return a function which implements the given join-op for more than two relation-field pairs."
  (fn multiarg-join    
    ([R r S s]
       (join-op (relation-or-lookup R) r (relation-or-lookup S) s))
    ([R r S s & more]
       (if (= 1 (count more)) (throw (java.lang.IllegalArgumentException. "wrong number of arguments to join")))
       (apply multiarg-join (join-op (relation-or-lookup R) r (relation-or-lookup S) s) r more))))

(def join (make-join-op-fn op/join))

;; fixme:
(def right-outer-join (make-join-op-fn (fn [R r S s] (union (op/join R r S s) R))))
;; fixme:
(def outer-join (make-join-op-fn (fn [R S r s] (union (join R r S s) (join S s R r) R S))))

;; functional join:
;; example: 
;;       (let [R #{{:a 1} {:a 2}}]
;;          (fjoin R #(take % (range 99)) :b)
;;       -> #{{:a 1 :b 10}
;;            {:a 2 :b 10}
;;            {:a 2 :b 11}}
;;       == (make-relation (mapcat #(assoc % :b (take (:a %) (range 10 20))) R) :fields [:a :b])
;;       )

;; xproduct

(defn xproduct [R S]
  (op/xproduct (relation-or-lookup R) (relation-or-lookup S)))

;; tools

(defn order-by [R field <-or->]
  (op/order-by (relation-or-lookup R) field <-or->))

(defn group-by
  "Return a hasmap of field-values to sets of tuples
  where they occur in relation R. (aka sql-group by or index)."
  ([R field & more-fields]
     (let [R (relation-or-lookup R)
           gi (fn group-index [index-set fields]
                (if-let [field (first fields)]
                  (magic-map (fn ([] (map field index-set))
                                 ([k] (group-index (clojure.set/intersection index-set (((op/index R) field) k)) (next fields)))))
                  index-set))]
         (magic-map (fn ([] (keys ((op/index R) field)))
                        ([k] (gi (((op/index R) field) k) more-fields)))))))

(defn field-seq
  "Return a seq of field values from a relation.
  When given more than one field, return values in vectors."
  ([R field]
     (map field R ;;(project* R [field])
          ))
  ([R field & more-fields]
     (let [f (cons field more-fields)]
       (map #(vec (map (partial get %)f)) R))))

(defn field-map
  "Return a map of key-field values to value-field values."
  [R key-field value-field]
  (value-mapped-map #(map value-field %)
                    (group-by R key-field)))
                      
(defn like
  "Return true if the expr matches string symbol or keyword x
  Expr is eiter a string or a symbol. It is matched against x ignoring
  case and using `*' as a wildcard."
  [expr x] ;; sql-like-like, match everything easily, simplified regex
  (let [x (if (or (symbol? x) (keyword? x)) (name x) (str x))]
    (.matches (.toLowerCase x) 
              (str "^" (.replace (.toLowerCase (str expr)) "*" ".*") "$"))))

(defn rlike
  "Return true if string symbol or keyword X matches the regular expression EXPR."
  [regular-expression x]
  (let [x (if (or (symbol? x) (keyword? x)) (name x) (str x))]
    (.matches x regular-expression)))

;; pretty printing

(defn- determine-column-sizes
  "Given a relation R, return a list of column-sizes according to opts."
  [R opts]
  (if (not (empty? R))
    (let [max-col-widths (map #(pipe (project* R (list %))
                                     (map pr-str)
                                     (map count)
                                     (map (partial + -1))
                                     (reduce max))
                              (fields R))
          pretty-col-widths (pipe max-col-widths
                                  (map (partial min (:max-colsize opts 80)))
                                  (map (partial max (:min-colsize opts 3))))
          small-fields-count (count (filter (partial <= (:min-colsize opts 0)) pretty-col-widths))
          amount (if (< small-fields-count 0)
                   (/ (- (reduce + pretty-col-widths) (:max-linesize opts 80))
                      small-fields-count)
                   0)]
      (zipmap (fields R) (if (< 0 amount)
                           (map #(max (:min-colsize opts) (- % amount)) pretty-col-widths)
                           pretty-col-widths)))))


(def *pretty-print-relation-opts* {:max-lines 60, :max-colsize 80, :max-linesize 200 :min-colsize 1})
;;  :max-lines =^ *print-length*

(defn pretty-print-relation
  "Print a relation pretty readably to :writer (default *out*), try 
  to align fields correctly while not to exceeding :max-linesize and
  other opts."
  [R & opts]
  (cond (empty? R)
          (print (set R))
        :else
          (let [opts (as-keyargs opts (assoc (or *pretty-print-relation-opts* {}) :writer *out*))
                max-lines (:max-lines opts *print-length*)
                max-linesize (:max-linesize opts)
                R (if max-lines
                    (make-relation (take (inc max-lines) R) :fields (fields R))
                    R)
                sizes (or (not max-linesize) (determine-column-sizes R opts))
                pr-field (fn [tuple field-name comma]
                           (let [v (get tuple field-name)
                                 s (sizes field-name)]
                             (str (str-cut (str field-name " "
                                                (str-align (str (pr-str v) (if comma "," ""))
                                                           (- s (count (str field-name)) (if comma 1 2))
                                                           (if (or (string? v) (symbol? v) (keyword? v)) :left :right)))
                                           s)
                                  (if comma " " ""))))
                pr-tuple (fn [tuple] (str "{" (apply str (map (partial pr-field tuple)
                                                              (fields R)
                                                              (concat (drop 1 (fields R)) '(false)))) "}"))]
            (let [w (:writer opts)]
              (binding [*out* w]
                (print "#{")
                (if max-linesize 
                  (print (pr-tuple (first R)))
                  (print (str (first R) ",")))
                (let [[tup-pr, tup-remain] (if max-lines
                                             (split-at (dec max-lines) (next R))
                                             [(next R) nil])]
                  (doseq [r tup-pr]
                    (println)
                    (if max-linesize 
                      (print (str "  " (pr-tuple r)))
                      (print (str "  " r ","))))
                  (when (seq tup-remain) (println) (print "  ...")))
                (println "}"))))))

;; establish default pretty-printing of relations, needs awareness for *print-** variables
(defmethod print-method hoeck.rel.Relation
  [R, w]
  (pretty-print-relation R :writer w))


;; saving & loading
(defn save-relation [R f]
  (with-out-writer f
    (binding [*pretty-print-relation-opts* (assoc *pretty-print-relation-opts* :max-lines nil :max-linesize nil)]
      (print (relation-or-lookup R)))))

;; sql stuff needs hoeck.rel
;(require '[hoeck.rel.sql-utils :as sql-utils])
;(require '[hoeck.rel.sql :as sql])

;(def-lots-of-aliases 
;  (sql-utils default-derby-args default-sybase-args))

;(defaliases
;  sql-connection sql-utils/make-connection-fn)
