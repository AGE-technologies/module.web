(ns duct.module.web
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [duct.core :as core]
            [duct.core.env :as env]
            [duct.core.merge :as merge]
            [duct.handler.error :as err]
            [duct.middleware.web :as mw]
            [duct.router.cascading :as router]
            [integrant.core :as ig]
            [ring.middleware.defaults :as defaults]))

(derive :duct.server.http/jetty :duct.server/http)

(def ^:private server-port
  (env/env '["PORT" Int :or 3000]))

(defn- get-environment [config options]
  (:environment options (:duct.core/environment config :production)))

(defn- get-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn- name-to-path [sym]
  (-> sym name (str/replace "-" "_") (str/replace "." "/")))

(defn- derived-key [m k default]
  (if-let [kv (ig/find-derived-1 m k)] (key kv) default))

(defn- http-server-key [config]
  (derived-key config :duct.server/http :duct.server.http/jetty))

(defn- server-config [config]
  {(http-server-key config) {:port (merge/displace server-port)}})

(defn- router-config [config]
  (if-not (ig/find-derived-1 config :duct/router)
    {:duct.router/cascading []}
    {}))

(def ^:private logging-config
  {::mw/log-requests {:logger (ig/ref :duct/logger)}
   ::mw/log-errors   {:logger (ig/ref :duct/logger)}
   ::core/handler    {:middleware ^:distinct [(ig/ref ::mw/log-requests)
                                              (ig/ref ::mw/log-errors)]}})

(def ^:private error-configs
  {:production
   {::core/handler {:middleware ^:distinct [(ig/ref ::mw/hide-errors)]}}
   :development
   {::core/handler {:middleware ^:distinct [(ig/ref ::mw/stacktrace)]}}})

(def ^:private base-config
  {::mw/not-found    {:error-handler (ig/ref ::err/not-found)}
   ::mw/hide-errors  {:error-handler (ig/ref ::err/internal-error)}
   ::core/handler    {:router  (ig/ref :duct/router)}
   :duct.server/http {:handler (ig/ref ::core/handler)
                      :logger  (ig/ref :duct/logger)}})

(def ^:private api-config
  {::err/not-found      {:response (merge/displace "Resource Not Found")}
   ::err/internal-error {:response (merge/displace "Internal Server Error")}
   ::mw/stacktrace      {}
   ::mw/defaults        (merge/displace defaults/api-defaults)
   ::core/handler       {:middleware ^:distinct [(ig/ref ::mw/not-found)
                                                 (ig/ref ::mw/defaults)]}})

(def ^:private error-404 (io/resource "duct/module/web/errors/404.html"))
(def ^:private error-500 (io/resource "duct/module/web/errors/500.html"))

(defn- site-resource-paths [project-ns]
  ["duct/module/web/public" (str (name-to-path project-ns) "/public")])

(defn- site-defaults [project-ns]
  (assoc-in defaults/site-defaults [:static :resources] (site-resource-paths project-ns)))

(defn- site-config [project-ns]
  {::err/not-found      {:response (merge/displace error-404)}
   ::err/internal-error {:response (merge/displace error-500)}
   ::mw/webjars         {}
   ::mw/stacktrace      {}
   ::mw/defaults        (merge/displace (site-defaults project-ns))
   ::core/handler       {:middleware ^:distinct [(ig/ref ::mw/not-found)
                                                 (ig/ref ::mw/webjars)
                                                 (ig/ref ::mw/defaults)]}})

(derive ::api  :duct/module)
(derive ::site :duct/module)

(defmethod ig/init-key ::api [_ options]
  {:req #{:duct/logger}
   :fn  (fn [config]
          (core/merge-configs config
                              (server-config config)
                              (router-config config)
                              base-config
                              api-config
                              logging-config
                              (error-configs (get-environment config options))))})

(defmethod ig/init-key ::site [_ options]
  {:req #{:duct/logger}
   :fn  (fn [config]
          (core/merge-configs config
                              (server-config config)
                              (router-config config)
                              base-config
                              (site-config (get-project-ns config options))
                              logging-config
                              (error-configs (get-environment config options))))})
