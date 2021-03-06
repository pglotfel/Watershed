(ns watershed.core
  (:require [manifold.deferred :as d]
            [watershed.graph :as g]
            [clojure.set :as st]
            [manifold.stream :as s])
  (:use [watershed.utils]))

(set! *warn-on-reflection* true)

(defmulti parse-vertex 
  (fn [env vertex step con]
    (:type vertex)))

(defmethod parse-vertex :cyclic
  [env {:keys [title sieve]} step _]
  (assoc env title (step)))

(defmethod parse-vertex :source
  [env {:keys [title sieve]} step _]
  (assoc env title (step)))

(defmethod parse-vertex :estuary
  [env {:keys [title sieve tributaries]} _ _] 
  (assoc env title (apply sieve (map env tributaries))))

(defmethod parse-vertex :river
  [env {:keys [title sieve tributaries]} _ _]  
  (assoc env title (apply sieve (map env tributaries))))

(defmethod parse-vertex :aliased 
  [env {:keys [title sieve tributaries]} _ con] 
  (con (apply sieve (map env tributaries)) (title env))
  env)

(defmethod parse-vertex nil 
  [env _ _ _ ]
  env)

(defn- expand-dependencies
  [groups dependencies]
  (vec (flatten (map (fn [dependency]                
                       (if (vector? dependency)                      
                         (let [[id op args] dependency] 
                           (case op                            
                             :only (vec (filter (set args) (id groups)))
                             :without (vec (remove (set args) (id groups)))
                             (id groups)))                                                  
                         dependency))               
                     dependencies))))

(defn- dependents
  [outlines t] 
  (reduce      
    (fn [coll {:keys [title tributaries]}]              
      (if (some #{t} tributaries) (conj coll title) coll)) #{} outlines))

(defn- make-graph 
  [outlines] 
  (reduce (fn [m {:keys [title]}]                      
            (assoc m title {:edges (dependents outlines title)}))                     
          {} outlines))

(def ^{:private true} o {:title nil :tributaries nil :sieve nil :group nil})

(defn vertex
  ([title tributaries sieve] (vertex title tributaries sieve nil))
  ([title tributaries sieve group]
    (assoc o :title title :tributaries tributaries :sieve sieve :group group)))
    
(defn assemble 
  [step con & outlines] 
  
  ;Implement some checks...
  
  (let [ts (map :title outlines)]
    (assert (= (count ts) (count (distinct ts))) "Each vertex must have a distinct name!"))
  
  (let [compiler (fn [env o] (parse-vertex env o step con))   
             
        ;#### Expand dependencies and infer types! ####
        
        [sccs with-deps] (let [groups (-> 
                                        
                                        (reduce (fn [m {:keys [title group]}] 
                                                  (if group 
                                                    (update-in m [group] (fn [x] (conj x title))) 
                                                    m)) 
                                                {} outlines)
                                        
                                        (assoc :all (mapv :title outlines)))
                        
                               deps-expanded (map (fn [o] (assoc o :tributaries (expand-dependencies groups (:tributaries o)))) outlines) 
                        
                               graph (make-graph deps-expanded)
                        
                               transpose (g/transpose graph)
                        
                               sccs (->>
                                      
                                      (g/strongly-connected-components graph (g/transpose graph))                                                                      
                                                                       
                                      (remove
                                        (fn [vals]
                                          (if (= (count vals) 1)
                                            (let [val (vals 0)]
                                            (not (val (:edges (val graph))))))))
                                      
                                      (remove empty?))                                                           
                        
                               pred (apply (comp set concat) sccs)]   
                    
                           [sccs (map (comp                           
                                   
                                        ;#### Tag components in cycles... ####   
                           
                                        (fn [o]                            
                                          (if ((:title o) pred)
                                            (assoc o :type :cyclic)
                                            o))
                           
                                        ;#### Infer graph types! ####
                           
                                        (fn [o]   
                                          (if (:type o)
                                            o
                                            (let [title (:title o)                                
                                                  graph-es (:edges (title graph))                               
                                                  transpose-es (:edges (title transpose))]                            
                                              (if (empty? graph-es)
                                                (if (empty? transpose-es)
                                                  (throw (IllegalArgumentException. (str "You have a node, " title ", with no dependencies and no dependents...")))
                                                  (assoc o :type :estuary))
                                                (if (empty? transpose-es)
                                                  (assoc o :type :source) 
                                                  (assoc o :type :river))))))) 
                         
                                      deps-expanded)])                                 
             
        ;#### Get the sources and cycles for future reference! ####
        
        sources (filter #(= (:type %) :source) with-deps)
        
        cycles (filter #(= (:type %) :cyclic) with-deps)
                   
        ;#### First compiler pass... ####
              
        env (reduce compiler {} (concat 
          
                                  sources 
                
                                  cycles 
                
                                  ;#### Do a topological sort on the remaining nodes #### 
                                  
                                  (let [non-cyclic (into {} (map (fn [o] [(:title o) o]) 
                                                                 (remove (fn [o]                 
                                                                           (let [type (:type o)]
                                                                             (or (= type :cyclic) (= type :source) (= type :dam))))                  
                                                                           with-deps)))]
                                          
                                    (->> 
                                            
                                      (make-graph (vals non-cyclic))
                                            
                                      g/kahn-sort
                                            
                                      (map non-cyclic)))
                                           
                                  (map (fn [o] (assoc o :type :aliased)) (concat sources cycles))))]
       
    ;#### Next, I need to start all of the cycles.  Ooo, side effects! ####
    
    (doseq [o (| (mapcat (fn [group] (g/fvs (make-graph (filter (comp (set group) :title) cycles)))) sccs)
              
                 #(filter (comp (set %) :title) cycles) 
              
                 #(mapcat :tributaries %)
              
                 #(filter (comp (set %) :title) cycles))]                     
      
      (step ((:title o) env) ((:sieve o))))
    
    ;#### Associate streams back into the outlines! ####

    (map (fn [a b] (assoc a :output ((:title a) env) :type (:type b))) outlines with-deps)))

(defn output 
  "Retrieves the output of a given body" 
  [title & outlines]  
  (:output (first (filter #(= (:title %) title) outlines))))  



  
  
  
  
  
  
  
  
        
         
         
           
           
           
           
           
           
                 