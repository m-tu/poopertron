(ns app.core
  (:use org.httpkit.server
        [clojure.tools.logging :only [info]]
        overtone.at-at
        org.nfrac.cljbox2d.core)
  (:require [ring.middleware.reload :as reload]
            [clojure.data.json :as json]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [compojure.core :refer [defroutes GET]]))


(def poopertron (atom nil))

(defn ws-handler [request]
  (with-channel request channel
    (info channel "connected")
    (def clients (:clients poopertron))
    (swap! poopertron assoc :clients (assoc clients channel {}))
    (on-close channel (fn [status]
                        (swap! poopertron assoc :clients (dissoc clients channel))
                        (info "channel closed: " status)))
    (on-receive channel (fn [data]
                          (send! channel data)))))

(defroutes all-routes
  (GET "/" [] "gtfo")
  (GET "/feed" [] ws-handler)
  (route/not-found "not found"))

(defn in-dev? [args] true)

(defn stop-server []
  (let [state @poopertron]
    (when-not (nil? state)
      (stop-and-reset-pool! (:sched-pool state))
      (doseq [conn (keys (:clients state))]
        (close conn))
      ((:server state) :timeout 250)
      (reset! poopertron nil))))

(defn dynamic-entities [state]
  (let [bodies (bodyseq (:world state))]
    (filter #(= (body-type %) :dynamic) bodies)))

(defn move-entities [state]
  (let [bodies (dynamic-entities state)
        n (count bodies)]
    (doseq [b (repeatedly (rand-int n) #(rand-nth bodies))]
      (apply-impulse! b [(+ -3 (rand-int 7)) (+ -3 (rand-int 7))] (center b)))))


(defn state-broadcast [state]
  (let [channels (keys (:clients state))
        dyn-bodies (dynamic-entities state)]
    (def entities (json/write-str (map #(-> {:position (position %)
                                             :id (user-data %)}) dyn-bodies)))
    (doseq [channel channels]
      (send! channel entities))))

(defn start-server [& args]
  (let [handler (if (in-dev? args)
                 (reload/wrap-reload (site #'all-routes))
                 (site all-routes))]
    (stop-server)
    (let [server (run-server handler { :port 9001})
          world (new-world [0 0])
          clients {}]
      (body! world {:type :static} {:shape (edge [-25 25] [-25 -25])})
      (body! world {:type :static} {:shape (edge [-25 -25] [25 -25])})
      (body! world {:type :static} {:shape (edge [25 -25] [25 25])})
      (body! world {:type :static} {:shape (edge [25 25] [-25 25])})
      (dotimes [n 40]
        (body! world {:position [(+ 5 (rand-int 10)) 10] :user-data n}
               {:shape (circle 1) :restitution 0.4}))
      (reset! poopertron {:server server
                          :clients clients
                          :world world
                          :sched-pool (mk-pool)})
      (every 1000 #(move-entities @poopertron) (:sched-pool @poopertron))
      (every 100 #(state-broadcast @poopertron) (:sched-pool @poopertron))
      (every 20 #(step! (:world @poopertron) (/ 1 20)) (:sched-pool @poopertron)))))
    
