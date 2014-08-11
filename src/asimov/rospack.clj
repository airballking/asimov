(ns asimov.rospack
  (:require [clojure.java.shell :as sh]))

(defn rospack-dependencies
  "Returns the dependency directories of a ROS package.

Expects:
 package:string the name of the ROS package.

Returns a sequence strings which denote the directories of the dependencies."
  [package]
  (map rospack-find (rospack-depends package)))

(defn- rospack
  [& args]
  (let [{:keys [exit out err]} (apply sh/sh "rospack" args)]
    (if (not= 0 exit)
      (throw (Exception. err))
      out)))

(defn- rospack-find
  "Calls rospack for the directory of 'package'."
  [package]
  (clojure.string/replace (rospack "find" package) #"\n" ""))

(defn- rospack-depends
  "Calls rospack for all ROS dependencies of 'package'"
  [package]
  (clojure.string/split (rospack "depends" package) #"\n"))
