(ns asimov.defaults
  (:require [clojure.java.shell :as sh]))


(defn default-client-host []
  (System/getenv "ROS_IP"))

(declare default-client-host
         default-master-host
         default-master-port
         default-host)

(defn default-master-host []
  "192.168.254.133"
  #_(-> (re-find #"http://([^:]*):([1-9]*)"
               (System/getenv "ROS_MASTER_URI"))
      second))

(defn default-master-port []
  11311
  #_(-> (re-find #"http://([^:]*):([1-9]*)"
               (System/getenv "ROS_MASTER_URI"))
        (nth 2)
        Integer/parseInt))


(defn default-hosts []
  {})