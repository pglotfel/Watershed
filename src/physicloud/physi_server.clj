(ns physicloud.physi-server
  (:require [aleph.tcp :as tcp] 
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [byte-streams :as b]
            [watershed.core :as w]
            [taoensso.nippy :as nippy]
            [aleph.udp :as udp]
            [watershed.utils :as u])
  (:use [gloss.core]
        [gloss.io]))

(def ^:private B-ary (Class/forName "[B"))
(def ^:private delimiter "|!|")
(def ^:private frame (string :utf-8 :delimiters [delimiter]))

(defn manifold-step 
  ([] (s/stream))
  ([s] (s/close! s))
  ([s input] (s/put! s input)))

(defn manifold-connect 
  [in out] 
  (s/connect in out {:upstream? true}))

(defn assemble-phy 
  [& outlines] 
  (apply w/assemble manifold-step manifold-connect outlines))

(defn- ^String delimit 
  [^String s]
  (str s delimiter))

(defn encode' 
  [msg]
  (encode frame (pr-str msg)))
  
(defn- defrost 
  [msg] 
  (nippy/thaw (b/convert msg B-ary)))

(defn- handler 
  [f clients ch client-info]
  (let [index (.indexOf clients (:remote-addr client-info))]
    (if (> index -1)
      (do
        (println "Client: " client-info " connected.")
        (f index ch))
      (throw (IllegalStateException. (str "Unexpected client, " client-info ", tried to connect to the server."))))))

(defn physi-client 
  [{:keys [host port interval] :or {port 10000 interval 2000}}]
  
  (assert (some? host) "A host IP address must be specified.")  
  
  (let [c-data {:host host :port port :insecure? true :sll? true}]
    (d/loop [c (->                         
                 (tcp/client c-data)                         
                 (d/timeout! interval nil))]          
       (d/chain
         c
         (fn [x] 
           (if x
             x    
             (do 
               (println "Connecting to " host " ...")
               (d/recur (-> 
                          (tcp/client c-data)
                          (d/timeout! interval nil))))))))))

(defn physi-server  
  "Creates a PhysiCloud server that waits for the given clients to connect."
  [{:keys [port] :or {port 10000}} & clients] 
  (let [clients (into [] clients)      
        ds (into [] (repeatedly (count clients) d/deferred))     
        f (fn [i x] (d/success! (ds i) x))                     
        server (tcp/start-server #(handler f clients % %2) {:port port})]    
    (d/chain (apply d/zip ds) (fn [x] (->                                                         
                                        (zipmap clients x)                                                         
                                        (assoc ::cleanup server))))))

(defn make-key   
  [append k]   
  (keyword (str append (name k))))

(defn selector  
  [pred stream]   
  (let [s (s/map identity stream)           
        output (s/stream)]         
         (d/loop           
           [v (s/take! s)]          
           (d/chain v (fn [x]                 
                        (if (s/closed? output)                                
                          (s/close! s)                                                              
                          (if (nil? x)
                            (s/close! output)
                            (do
                              (let [result (pred x)]                                  
                                (if result (s/put! output result))) 
                              (d/recur (s/take! s))))))))        
         output))
      
(defn take-within 
  [fn' stream timeout default] 
  (let [s (s/map identity stream)
        output (s/stream)]    
    (d/loop
      [v (d/timeout! (s/take! s) timeout default)]
      (d/chain v (fn [x] 
                   (if (s/closed? output)
                     (s/close! s)
                     (if (nil? x)
                       (do
                         (s/put! output default)
                         (s/close! output))
                       (if (= x default)
                         (do                        
                           (s/put! output default)
                           (s/close! s)
                           (s/close! output))                        
                         (do
                           (s/put! output (fn' x)) 
                           (d/recur (d/timeout! (s/take! s) timeout default)))))))))
    output))

(defn- acc-fn  
  [[accumulation new]] 
  (merge accumulation new))

(defn- watch-fn   
  [streams accumulation expected] 
  (when (>= (count (keys accumulation)) expected)   
    ;EW. Do something better than this in the future...
    (future
      (Thread/sleep 5000)
      (doall (map #(if (s/stream? %) (s/close! %)) streams)))))

(defn elect-leader 
  
  "Creates a UDP network to elect a leader.  Returns the leader and respondents [leader respondents]"
  
  [ip number-of-neighbors {:keys [udp-duration udp-interval udp-port] :or {udp-duration 5000 udp-interval 1000 udp-port 8999}}]
  
  (let [leader (atom nil)
        
        socket @(udp/socket {:port udp-port :broadcast? true})
        
        respondents (atom [])
        
        msg {:message (nippy/freeze [(u/cpu-units) ip]) :port udp-port 
             :host (let [split (butlast (clojure.string/split ip #"\."))]
                     (str (clojure.string/join (interleave split (repeat (count split) "."))) "255"))}
        
        system (assemble-phy
                
                 (w/outline :broadcast [] (fn [] (s/periodically udp-interval (fn [] msg))))
                
                 (w/outline :connect [:broadcast] (fn [stream] (s/connect stream socket)))
                         
                 (w/outline :socket [] (fn [] (s/map (fn [x] (hash-map (:host x) x)) socket)))
                
                 (w/outline :result [:socket] (fn [x] (s/reduce merge (s/map identity x))))
                
                 (w/outline :accumulator [:accumulator :socket]                                 
                                 (fn 
                                   ([] {})
                                   ([& streams] (s/map acc-fn (apply s/zip streams)))))
                           
                 (w/outline :watch [:accumulator [:all :without [:watch]]] (fn [stream & streams] (s/consume #(watch-fn streams % number-of-neighbors) (s/map identity stream)))))]
    
    (reduce (fn [max [k v]]  
              (let [[v' l'] (defrost (:message v))]
                (swap! respondents conj l')
                 (if (> v' max)
                   (do 
                     (reset! leader l')
                     v')
                   max)))            
            -1            
            @(apply w/output :result system))
    [@leader (distinct @respondents)]))      

(defn find-first
  [pred coll] 
  (first (filter pred coll)))

;Check to see if network is still cyclic...

(defn cleanup 
  [system]
  ((:server (::cleanup system))))

(defn cpu 
  [{:keys [requires provides ip port neighbors udp-duration udp-interval udp-port] :or {port 10000} :as opts}]
  {:pre [(some? requires) (some? provides) (some? neighbors)]}
  
  (let [[leader respondents] (elect-leader ip neighbors opts) 
        
        client (physi-client {:host leader :port port})     
        
        server (if (= leader ip) @(apply physi-server ip respondents))
        
        client @client
        
        server-sys (atom {})]    
    
    (s/put! client (pr-str [requires provides ip]))  
    
    (if server      
      (let [woserver (dissoc server ::cleanup)        
            cs (keys woserver)
            ss (vals woserver)]        
           
        (reset! server-sys @(d/chain (apply d/zip (map s/take! ss)) 
                 
                                   (fn [responses] 
                                     (let [connections (doall (map (fn [r] (apply hash-map (doall (interleave [:requires :provides :ip] 
                                                                                                              (read-string (b/convert r String))))))                                                                
                                                                     responses))
                         
                                           cs' (mapv keyword (remove #(= leader %) cs))
                                           
                                           sys (->> 
                                     
                                                 (mapcat (fn [x]                                                                                           
                                               
                                                           [(w/outline (make-key "providing-" x) []
                                                                       (fn []                                            
                                                                         (->> 
                                                                           (decode-stream (get server (name x)) frame)                                                                  
                                                                           (s/filter not-empty)
                                                                           (s/map (fn [x] (read-string x))))))       
                                                
                                                            (w/outline (make-key "receiving-" x)
                                                                       (->> 
                                                                         (let [pred (set (:requires (get connections x)))]
                                                                           (reduce (fn [coll r] 
                                                                                     (if (some pred (:provides (get connections r)))
                                                                                       (conj coll (make-key "providing-" r))
                                                                                       coll)) 
                                                                                   []
                                                                                   cs'))
                                                                         (cons (make-key "providing-" leader))
                                                                         distinct
                                                                         vec)
                                                                       (fn [& streams] 
                                                                         (let [recipient (get server (name x))                                                  
                                                                          intermediate (s/stream)]
                                                                           (doseq [s streams] 
                                                                             (s/connect-via s (fn [x] (s/put! intermediate (encode' x))) intermediate))
                                                                           (s/connect-via intermediate (fn [x] (d/zip (doall (map #(s/put! recipient %) x)))) recipient))))])
                                                         cs')
                                     
                                                 (cons (w/outline (make-key "providing-" leader) [] 
                                                                  (fn []                                            
                                                                    (->> 
                                                                      (decode-stream (get server leader) frame)                                                                  
                                                                      (s/filter not-empty)
                                                                      (s/map (fn [x] (read-string x)))))))
                                     
                                                 (cons (w/outline (make-key "receiving-" leader) (mapv #(make-key "providing-" %) cs') 
                                                                  (fn [& streams] 
                                                                    (let [recipient (get server leader)                                                  
                                                                          intermediate (s/stream)]
                                                                      (doseq [s streams] 
                                                                        (s/connect-via s (fn [x] (s/put! intermediate (encode' x))) intermediate))
                                                                      (s/connect-via intermediate (fn [x] (d/zip (doall (map #(s/put! recipient %) x)))) recipient))))))] 
                                       
                                       ;generate dependencies!
                     
                                       (apply assemble-phy sys)))))
        
        ;#### Let all the clients know that everything is connected
        
        (doseq [c cs]
          (when-not (= c ip)          
            (s/put! (get server c) (pr-str ::connected)))))
      
      ;Add in more complex checks here in the future
      
      ;#### Block until server is properly initialized ####
      
      (println (b/convert @(s/take! client) String)))     
    
      (-> 
      
        (let [ret {:client client}]
          (if server
            (do
              (->              
                (assoc ret :server server)
                (assoc :server-sys @server-sys)
                (update-in [:server ::cleanup] (fn [x] (comp (fn [] (doseq [s (vals server)] (s/close! s))) x)))))
            ret))
        
        (assoc :system 
               
               (let [id (last (clojure.string/split ip #"\."))
                     
                     hb-vector [:heartbeat id]
                     
                     rec-id (keyword (str "heartbeat-received-" id))
                     
                     status-map {:connection-status ::connected}             
                     
                     rs (mapv (fn [r] (w/outline r [:client] (fn [stream] 
                                                               (selector (fn [packet]                                                                                              
                                                                           (let [[sndr val] packet]
                                                                             (if (= sndr r) val))) stream)))) 
                             requires)
                     
                     ps (mapv (fn [p] (w/outline (make-key "providing-" p) [p]                                       
                                                 (fn [stream] (s/map (fn [x] [p x]) stream))                                     
                                                 :data-out)) 
                   
                             provides)
                     
                     hb-resp (if (= leader ip)
                               [(w/outline :heartbeat-respond [:client]                                       
                                           (fn [stream] (selector (fn [packet]                                                                          
                                                                    (let [[sndr msg] packet]
                                                                      (if (= sndr :heartbeat)                                                                   
                                                                        (do
                                                                          (println "Got heartbeat from " msg ", on server!")
                                                                          [(keyword (str "heartbeat-received-" msg))])))) stream))                               
                                           :data-out)]
                               [])
                     
                     hb-cl (if (= leader ip)                       
                             []
                             [(w/outline :heartbeat []
                                         (fn [] (s/periodically 5000 (fn [] hb-vector)))
                                         :data-out)
                              
                              (w/outline :heartbeat-receive 
                                         [:client]
                                         (fn [stream] 
                                           (selector (fn [packet]                                                                                              
                                                       (let [[sndr] packet]
                                                         (if (= sndr rec-id)                                                                   
                                                           (do
                                                             (println "Got heartbeat on client!")
                                                             status-map)))) stream)))
                              
                              (w/outline 
                                :heartbeat-status 
                                [:heartbeat-receive]                      
                                (fn [stream] (take-within identity stream 20000 {:connection-status ::disconnected})))
                              
                              (w/outline :heartbeat-watch [:heartbeat-status [:all :without [:heartbeat-watch]]]
                                         (fn [stream & streams] 
                                           (s/consume (fn [x] 
                                                        (if (= (:connection-status x) ::disconnected)
                                                          (doall (map #(if (s/stream? %) (s/close! %)) streams)))) 
                                                      (s/map identity stream))))
                              
                              (w/outline
                                 :system-status
                                 ;Change this to get a bunch of data...
                                 [:heartbeat-status]
                                 (fn [stream] (s/reduce merge (s/map identity stream))))])]               
                 
                 (->>
                   
                   (concat rs ps hb-resp hb-cl)
                   
                   (cons (w/outline :client [] (fn [] 
                                                                  
                                                 (->> 
                                                                                                                                                 
                                                   (decode-stream client frame)
                                                                    
                                                   (s/filter not-empty)
                                                                  
                                                   (s/map (fn [x] (read-string x)))))))    
                   
                   (cons (w/outline :out 
                                     [[:data-out]] 
                                     (fn 
                                       [& streams] 
                                       (doseq [s streams] 
                                         (s/connect-via s (fn [x] (println "SENDING: " x) (d/zip (doall (map #(s/put! client %) (encode' x))))) client)))))))))))

(defn physicloud-instance
  [{:keys [requires provides ip port neighbors udp-duration udp-interval udp-port] :as opts} & tasks] 
  
  (loop [t-sys (cpu opts)
         
         sys (:system t-sys)
        
         c-sys (apply assemble-phy (concat sys tasks))]       
    
    (let [status (find-first #(= (:title %) :system-status) c-sys)]      
      
      (when (and status (= (:connection-status @(:output status)) ::disconnected))
        (println "Connection lost!  Reconnecting...")
        (let [t-sys (cpu opts)              
              sys (:system t-sys)]                 
          (recur t-sys          
                 sys             
                 (->>      
                   (concat sys tasks)    
                   (apply assemble-phy))))))))
  
  
  


















