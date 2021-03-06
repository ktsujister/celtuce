(ns celtuce.connector
  (:require
   [clojure.java.io :as io]
   [celtuce.codec :refer [nippy-codec]]
   [celtuce.commands :as cmds])
  (:import 
   (java.util.concurrent TimeUnit)
   (com.lambdaworks.redis
    RedisClient
    ClientOptions ClientOptions$Builder ClientOptions$DisconnectedBehavior
    SocketOptions SslOptions)
   (com.lambdaworks.redis.cluster
    ClusterClientOptions ClusterClientOptions$Builder
    ClusterTopologyRefreshOptions ClusterTopologyRefreshOptions$RefreshTrigger)
   (com.lambdaworks.redis.codec RedisCodec)
   (com.lambdaworks.redis.api StatefulRedisConnection)
   (com.lambdaworks.redis.cluster RedisClusterClient)
   (com.lambdaworks.redis.cluster.api StatefulRedisClusterConnection)
   (com.lambdaworks.redis.pubsub StatefulRedisPubSubConnection RedisPubSubListener)))

(defprotocol RedisConnector
  "Manipulate Redis client and stateful connection"
  (commands-sync  [this])
  (flush-commands [this])
  (set-options    [this options])
  (reset          [this])
  (shutdown       [this]))

(def kw->tunit
  {:nanoseconds  TimeUnit/NANOSECONDS
   :microseconds TimeUnit/MICROSECONDS
   :milliseconds TimeUnit/MILLISECONDS
   :seconds      TimeUnit/SECONDS
   :minutes      TimeUnit/MINUTES
   :hours        TimeUnit/HOURS
   :days         TimeUnit/DAYS})

(def kw->dbehavior
  {:default          ClientOptions$DisconnectedBehavior/DEFAULT
   :accept-commands  ClientOptions$DisconnectedBehavior/ACCEPT_COMMANDS
   :reject-commmands ClientOptions$DisconnectedBehavior/REJECT_COMMANDS})

(def kw->rtrigger
  {:moved-redirect
   ClusterTopologyRefreshOptions$RefreshTrigger/MOVED_REDIRECT
   :ask-redirect
   ClusterTopologyRefreshOptions$RefreshTrigger/ASK_REDIRECT
   :persistent-reconnects
   ClusterTopologyRefreshOptions$RefreshTrigger/PERSISTENT_RECONNECTS})

(defn- ^SocketOptions socket-options
  "Internal helper to build SocketOptions, used by b-client-options"
  [opts]
  (cond-> (SocketOptions/builder)
    (and (contains? opts :timeout)
         (contains? opts :unit))
    (.connectTimeout (:timeout opts) (kw->tunit (:unit opts)))
    (contains? opts :keep-alive)
    (.keepAlive (:keep-alive opts))
    (contains? opts :tcp-no-delay)
    (.tcpNoDelay (:tcp-no-delay opts))
    true (.build)))

(defn- ^SslOptions ssl-options
  "Internal helper to build SslOptions, used by b-client-options"
  [opts]
  (cond-> (SslOptions/builder)
    ;; provider setup
    (contains? opts :provider)
    (cond->
      (= :open-ssl (:provider opts)) (.openSslProvider)
      (= :jdk      (:provider opts)) (.jdkSslProvider))
    ;; keystore setup
    (contains? opts :keystore)
    (cond->
      (and (contains? (:keystore opts) :file)
           (contains? (:keystore opts) :password)) 
      (.keystore (io/as-file (-> opts :keystore :file))
                 (chars      (-> opts :keystore :password)))
      (contains? (:keystore opts) :file)
      (.keystore (io/as-file (-> opts :keystore :file)))
      (and (contains? (:keystore opts) :url)
           (contains? (:keystore opts) :password)) 
      (.keystore (io/as-url (-> opts :keystore :url))
                 (chars     (-> opts :keystore :password)))
      (contains? (:keystore opts) :url)
      (.keystore (io/as-url (-> opts :keystore :url))))
    ;; truststore setup
    (contains? opts :truststore)
    (cond->
      (and (contains? (:truststore opts) :file)
           (contains? (:truststore opts) :password)) 
      (.truststore (io/as-file (-> opts :truststore :file))
                   (-> opts :truststore :password str))
      (contains? (:truststore opts) :file)
      (.truststore (io/as-file (-> opts :truststore :file)))
      (and (contains? (:truststore opts) :url)
           (contains? (:truststore opts) :password)) 
      (.truststore (io/as-url (-> opts :truststore :url))
                   ^String (-> opts :truststore :password str))
      (contains? (:truststore opts) :url)
      (.truststore (io/as-url (-> opts :truststore :url))))
    ;; finally, build
    true (.build)))

(defn- ^ClientOptions$Builder b-client-options
  "Sets up a ClientOptions builder from a map of options"
  ([opts]
   (b-client-options (ClientOptions/builder) opts))
  ([^ClientOptions$Builder builder opts]
   (cond-> builder
     (contains? opts :ping-before-activate-connection)
     (.pingBeforeActivateConnection (:ping-before-activate-connection opts))
     (contains? opts :auto-reconnect)
     (.autoReconnect (:auto-reconnect opts))
     (contains? opts :suspend-reconnect-on-protocol-failure)
     (.suspendReconnectOnProtocolFailure (:suspend-reconnect-on-protocol-failure opts))
     (contains? opts :cancel-commands-on-reconnect-failure)
     (.cancelCommandsOnReconnectFailure (:cancel-commands-on-reconnect-failure opts))
     (contains? opts :request-queue-size)
     (.requestQueueSize (:request-queue-size opts))
     (contains? opts :disconnected-behavior)
     (.disconnectedBehavior (-> opts :disconnected-behavior kw->dbehavior))
     (contains? opts :socket-options)
     (.socketOptions (socket-options (:socket-options opts)))
     (contains? opts :ssl-options)
     (.sslOptions (ssl-options (:ssl-options opts))))))

(defn- ^ClusterTopologyRefreshOptions cluster-topo-refresh-options
  "Internal helper to build ClusterTopologyRefreshOptions,
  used by b-cluster-client-options"
  [opts]
  (cond-> (ClusterTopologyRefreshOptions/builder)
    (contains? opts :enable-periodic-refresh)
    (.enablePeriodicRefresh (:enable-periodic-refresh opts))
    (contains? opts :refresh-period)
    (.refreshPeriod (-> opts :refresh-period :period)
                    (-> opts :refresh-period :unit kw->tunit))
    (contains? opts :close-stale-connections)
    (.closeStaleConnections (:close-stale-connections opts))
    (contains? opts :dynamic-refresh-sources)
    (.dynamicRefreshSources (:dynamic-refresh-sources opts))
    (contains? opts :enable-adaptive-refresh-trigger)
    (cond->
      (= :all (:enable-adaptive-refresh-trigger opts))
      (.enableAllAdaptiveRefreshTriggers)
      (set? (:enable-adaptive-refresh-trigger opts))
      (.enableAdaptiveRefreshTrigger
       (into-array ClusterTopologyRefreshOptions$RefreshTrigger
                   (->> opts :enable-adaptive-refresh-trigger (map kw->rtrigger)))))
    (contains? opts :adaptive-refresh-triggers-timeout)
    (.adaptiveRefreshTriggersTimeout
     (-> opts :adaptive-refresh-triggers-timeout :timeout)
     (-> opts :adaptive-refresh-triggers-timeout :unit kw->tunit))
    (contains? opts :refresh-triggers-reconnect-attempts)
    (.refreshTriggersReconnectAttempts (:refresh-triggers-reconnect-attempts opts))
    true (.build)))

(defn- ^ClusterClientOptions$Builder b-cluster-client-options
  "Sets up a ClusterClientOptions builder from a map of options"
  [opts]
  (cond-> (ClusterClientOptions/builder)
    (contains? opts :validate-cluster-node-membership)
    (.validateClusterNodeMembership (:validate-cluster-node-membership opts))
    (contains? opts :max-redirects)
    (.maxRedirects (:max-redirects opts))
    (contains? opts :topology-refresh-options)
    (.topologyRefreshOptions
     (cluster-topo-refresh-options (:topology-refresh-options opts)))
    true (b-client-options opts)))

;;
;; Redis Server
;;

(defrecord RedisServer 
    [^RedisClient redis-client
     client-options
     ^StatefulRedisConnection stateful-conn
     conn-options
     ^RedisCodec codec]
  RedisConnector
  (commands-sync [this]
    (require '[celtuce.impl.server])
    (.sync stateful-conn))
  (flush-commands [this] 
    (.flushCommands stateful-conn))
  (reset [this] 
    (.reset stateful-conn))
  (shutdown [this]
    (.close stateful-conn)
    (.shutdown redis-client)))

(defn redis-server
  [^String redis-uri & 
   {codec :codec
    client-options :client-options
    {auto-flush :auto-flush conn-timeout :timeout conn-unit :unit
     :or {auto-flush true}} :conn-options
    :or {codec (nippy-codec) client-opts {}}}]
  (let [redis-client (RedisClient/create redis-uri)
        _ (.setOptions redis-client (.build (b-client-options client-options)))
        stateful-conn (.connect redis-client ^RedisCodec codec)]
    (when (and conn-timeout conn-unit)
      (.setTimeout stateful-conn conn-timeout (kw->tunit conn-unit)))
    (.setAutoFlushCommands stateful-conn auto-flush)
    (map->RedisServer
     {:redis-client   redis-client
      :client-options client-options
      :codec          codec
      :stateful-conn  stateful-conn
      :conn-options   {:auto-flush auto-flush
                       :timeout    conn-timeout
                       :unit       conn-unit}})))

;;
;; Redis Cluster
;;

(defrecord RedisCluster 
    [^RedisClusterClient redis-client
     client-options
     ^StatefulRedisClusterConnection stateful-conn
     conn-options
     ^RedisCodec codec]
  RedisConnector
  (commands-sync [this]
    (require '[celtuce.impl.cluster])
    (.sync stateful-conn))
  (flush-commands [this] 
    (.flushCommands stateful-conn))
  (reset [this] 
    (.reset stateful-conn))
  (shutdown [this]
    (.close stateful-conn)
    (.shutdown redis-client)))

(defn redis-cluster
  [^String redis-uri &
   {codec :codec
    client-options :client-options
    {auto-flush :auto-flush conn-timeout :timeout conn-unit :unit
     :or {auto-flush true}} :conn-options
    :or {codec (nippy-codec) client-options {}}}]
  (let [redis-client (RedisClusterClient/create redis-uri)
        _ (.setOptions redis-client
                       (.build (b-cluster-client-options client-options)))
        stateful-conn (.connect redis-client codec)]
    (when (and conn-timeout conn-unit)
      (.setTimeout stateful-conn conn-timeout (kw->tunit conn-unit)))
    (.setAutoFlushCommands stateful-conn auto-flush)
    (map->RedisCluster
     {:redis-client   redis-client
      :client-options client-options
      :codec          codec
      :stateful-conn  stateful-conn
      :conn-options   {:auto-flush auto-flush
                       :timeout    conn-timeout
                       :unit       conn-unit}})))

;;
;; Redis PubSub
;;

(defprotocol Listenable
  "Register a celtuce.commands.PubSubListener on a stateful pubsub connection"
  (add-listener! [this listener]))

(defrecord RedisPubSub 
    [redis-client ^StatefulRedisPubSubConnection stateful-conn codec]
  RedisConnector
  (commands-sync [this]
    (require '[celtuce.impl.pubsub])
    (.sync stateful-conn))
  (flush-commands [this] 
    (.flushCommands stateful-conn))
  (reset [this] 
    (.reset stateful-conn))
  (shutdown [this]
    (.close stateful-conn)
    (condp instance? redis-client
      RedisClusterClient (.shutdown ^RedisClusterClient redis-client)
      RedisClient        (.shutdown ^RedisClient        redis-client)))
  Listenable
  (add-listener! [this listener]
    (.addListener
     stateful-conn
     (reify
       RedisPubSubListener
       (message [_ ch msg]
         (cmds/message listener ch msg))
       (message [_ p ch msg]
         (cmds/message listener p ch msg))
       (subscribed [_ ch cnt]
         (cmds/subscribed listener ch cnt))
       (unsubscribed [_ ch cnt]
         (cmds/unsubscribed listener ch cnt))
       (psubscribed [_ p cnt]
         (cmds/psubscribed listener p cnt))
       (punsubscribed [_ p cnt]
         (cmds/punsubscribed listener p cnt))))))

(defn as-pubsub [{:keys [redis-client ^RedisCodec codec] :as redis-connector}]
  (->RedisPubSub
   redis-client
   (condp instance? redis-client
     RedisClusterClient (.connectPubSub ^RedisClusterClient redis-client codec)
     RedisClient        (.connectPubSub ^RedisClient        redis-client codec))
   codec))

