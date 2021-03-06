
(in-ns 'hoeck.prolog)


;; internal tests:

(deftest term-var-tests
  (let [vars   '(A ?a Bb BB ?X Z1 A-a)
        vnames '(A a Bb BB X Z1 A-a)
        non-vars '(?3 ? a-a --- nil (A1))]
    (is (anonymous-var? '_) "the anonymous var")
    (is (pipe (concat vars non-vars)
              (map anonymous-var?)
              (every? false?))
        "no anonymous vars")
    (is (= (map get-variable-name vars) (map name vnames)) "varname extraction")
    (is (every? false? (map get-variable-name non-vars))) "no varname corner-cases"))


(deftest term-construction
  ;; comparing clojure term creation with what
  ;; the builtin tuprolog parse constructs
  ;; compare the string outputs of the two resulting Terms instead
  ;; of using the .isEqual or .equals method, because the a.tuprolog.Vars
  ;; generated by make-term and read-term are _always_ not equal (they store
  ;; a creation date/timestamp) internally
  (are (= (str (read-term _1)) (str (make-term _2)))
       ;; atoms
       "1" 1
       "1.0001" 1.0001
       "p" 'p
       ;; won't work: "single-atom" 'single-atom due: "-" is a prolog operator
       "single_atom" 'single_atom
       ;; vars
       "X" 'X
       "Z" 'Z
       "Z0001" 'Z0001
       ;; equals
       "1=1" '(= 1 1)
       "1=2=3" '(= 1 2 3)
       "a=X=Y99=Zoo" '(= a X Y99 Zoo)
       ;; predicates
       "p(X)" '(p X)
       "p(X,1)" '(p X 1)
       "predicate(Variable,constant)" '(predicate Variable constant)
       ;; clauses (rules)
       "p(1)" '(<- (p 1))
       "pred(Var1)" '(<- (pred Var1))
       "p(X) :- p(1)" '(<- (p X) (p 1))
       "p(X) :- p(1), p(2)" '(<- (p X) (p 1) (p 2))
       "p(X) :- p(1), p(2), p(a)" '(<- (p X) (p 1) (p 2) (p a))
       ;; complex expression
       "predicate2(X,1) :- predicate2(p(X), a(1)), X=a(Y)=b(X), b(Y)"
       '(<- (predicate2 X 1)
            (predicate2 (p X) (a 1))
            (= X (a Y) (b X))
            (b Y))
       ;; lists
       "[]" []
       "[a]" '[a]
       "[a,b,c,d]" '[a b c d]
       "[a|3]" '(. a 3)
       "[1,2,3,4|5]" '[1 2 3 4 & 5]
       "[p(X,[a,b]),99]" '[(p X [a b]) 99]
       "[p(X,[a,b])|99]" '(. (p X [a b]) 99)
       "[p(X,[a,b])|99]" '[(p X [a b]) & 99]))

(deftest term-deconstruction
  ;; testing the make-clojure-term function
  (are (= (make-clojure-term (make-term _1)) _1)
       ;; atoms
       1, 1.1, 'ppp, 'pred-a, 'predicate-1, 'Var, 'X
       ;; predicates
       '(p X) '(predicate X Y 1) '(p (p X) Y) '(p (q (r (s X) (t X))))
       ;; lists
       [] [1 2 3] '[(pred X (func Y)) (func Y) 1 2]
       ;; dotted lists
       '(. 1 2)))


;; interface tests:

(deftest interpreter-test
  (binding [*rules* (atom #{})]
    (<- (child charlotte caroline))
    (<- (child caroline laura))
    (<- (child martha charlotte))
    (<- (child laura rose))

    (<- (descend X Y) (child X Y))
    (<- (descend X Y) (child X Z) (descend Z Y))       

    (is (= (set (map 'X (?- (descend martha X))))
           '#{rose laura caroline charlotte}))))


