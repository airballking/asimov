(ns asimov.tcpros
  (:require [clojure.core.async :as as]
            [clojure.set :as set]
            [lamina.core :as l]
            [aleph.tcp   :as a]
            [aleph.formats :as f]
            [gloss.core  :as g]
            [gloss.io    :as i]
            [taoensso.timbre :as t]
            [asimov.message :as msg]
            [asimov.util :as u]))

(def header-frame
  (g/finite-frame :uint32-le
                  (g/repeated (g/finite-frame :uint32-le
                                              [(g/string :ascii :delimiters [\=])
                                               (g/string :ascii)])
                              :prefix :none)))

(defn encode-header [h]
  (->> h
       (map (fn [[k v]] [(name k) v]))
       (into [])
       (i/encode header-frame)))

(defn decode-header [ch]
  (let [ch* (i/decode-channel-headers ch header-frame)
        h (l/read-channel ch*)]
    [ch* (future (->> @h
                      (map (fn [[k v]] [(keyword k) v]))
                      (into {})))]))

(defn subscribe! [addr callerid topic msg-def]
  (let [ch> (->> (select-keys addr [:host :port])
                 a/tcp-client
                 l/wait-for-result)
        [ch< inh] (decode-header (l/mapcat* f/bytes->byte-buffers ch>))]
    (l/enqueue ch> (encode-header {:message_definition (:cat msg-def)
                                   :callerid callerid
                                   :topic topic
                                   :md5sum (:md5 msg-def)
                                   :type (msg/serialize-id msg-def)}))
    (i/decode-channel ch< (:frame msg-def))))

(defn handler-fn[node]
  (fn [ch> client-info]
    (future
      (t/trace "Incomming connection:" client-info)
      (let [n @node
            [ch< inh] (decode-header (l/mapcat* f/bytes->byte-buffers ch>))
            inh @inh
            reply! #(l/enqueue ch> (encode-header %))
            reply-error! (fn [e]
                           (t/error client-info ":" e)
                           (reply! {:error e}))
            topic (get-in n [:pub (:topic inh)])
            msg-def (:msg-def topic)]
        (t/trace "received Header: " inh)
        (cond
         (not topic)
         (reply-error! (format "No such topic:%s" (:topic inh)))
         (not= (:md5 msg-def) (:md5sum inh))
         (reply-error! (format "Mismatched md5:%s/%s"
                               (:md5 msg-def)
                               (:md5sum inh)))
         (and (:pedantic? topic)
              (not= (:cat msg-def) (:message_definition inh)))
         (reply-error! (format "Mismatched cat:%s/%s"
                               (:cat msg-def)
                               (:message_definition inh)))
         :else
         (do
           (t/trace client-info ":Response seems ok, will reply.")
           (reply! {:md5sum (:md5 msg-def)
                    :type (msg/serialize-id msg-def)})
           (t/trace client-info ":Reply send.")
           (let [ch (as/chan)]
             (t/trace client-info ":Will start go loop.")
             (as/go-loop []
               (if-let [msg (as/<! ch)]
                 (do (l/enqueue ch> (i/encode (:frame msg-def) msg))
                     (recur))
                 (l/close ch>)))
             (t/trace client-info ":Will add new connection.")
             (swap! node update-in
                    [:pub (:topic inh) :connections]
                    conj {:client client-info
                          :chan ch})
             (as/tap (:mult topic) ch))))))))

(defn rand-port []
  (+ (rand-int (- 65535 49152)) 49152))

(defn listen! [node]
  (let [handler (handler-fn node)
        ports (take 1000 (distinct (repeatedly rand-port)))
        server (some #(try
                        {:server (a/start-tcp-server handler {:port %})
                         :port %}
                        (catch org.jboss.netty.channel.ChannelException e
                          (t/log :info "caught exception: " e)))
                     ports)]
    (if server
      server
      (throw (ex-info "Could not find a free port."
                      {:type ::no-free-port :ports ports})))))
