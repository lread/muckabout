(ns clj-http.lite.client
  "Batteries-included HTTP client.

  Among the many functions here you'll likely be most interested in
  [[get]] [[head]] [[put]] [[post]] [[delete]] or the slightly lower level [[request]]."
  (:require [clj-http.lite.core :as core]
            [clj-http.lite.links :refer [wrap-links]]
            [clj-http.lite.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.net UnknownHostException)
           (java.nio.charset Charset))
  (:refer-clojure :exclude (get update)))

(set! *warn-on-reflection* true)

(defn update [m k f & args]
  (assoc m k (apply f (m k) args)))

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn parse-url [url]
  (let [url-parsed (io/as-url url)]
    {:scheme (keyword (.getProtocol url-parsed))
     :server-name (.getHost url-parsed)
     :server-port (when-pos (.getPort url-parsed))
     :uri (.getPath url-parsed)
     :user-info (.getUserInfo url-parsed)
     :query-string (.getQuery url-parsed)}))

(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 307})

(defn wrap-exceptions [client]
  (fn [req]
    (let [{:keys [status] :as resp} (client req)]
      (if (or (not (clojure.core/get req :throw-exceptions true))
              (unexceptional-status? status))
        resp
        (throw (ex-info (str "clj-http: status " (:status resp)) resp))))))

(declare wrap-redirects)

(defn follow-redirect [client req resp]
  (let [url (get-in resp [:headers "location"])]
    ((wrap-redirects client) (assoc req :url url))))

(defn wrap-redirects [client]
  (fn [{:keys [request-method follow-redirects] :as req}]
    (let [{:keys [status] :as resp} (client req)]
      (cond
       (= false follow-redirects)
       resp
       (and (#{301 302 307} status) (#{:get :head} request-method))
       (follow-redirect client req resp)
       (and (= 303 status) (= :head request-method))
       (follow-redirect client (assoc req :request-method :get) resp)
       :else
       resp))))

(defn wrap-decompression [client]
  (fn [req]
    (if (get-in req [:headers "Accept-Encoding"])
      (client req)
      (let [req-c (update req :headers assoc "Accept-Encoding" "gzip, deflate")
            resp-c (client req-c)]
        (case (or (get-in resp-c [:headers "Content-Encoding"])
                  (get-in resp-c [:headers "content-encoding"]))
          "gzip" (update resp-c :body util/gunzip)
          "deflate" (update resp-c :body util/inflate)
          resp-c)))))

(defn wrap-output-coercion [client]
  (fn [{:keys [as] :as req}]
    (let [{:keys [body] :as resp} (client req)]
      (if body
        (cond
         (keyword? as)
         (condp = as
           ;; Don't do anything for streams
           :stream resp
           ;; Don't do anything when it's a byte-array
           :byte-array resp
           ;; Automatically determine response type
           :auto
           (assoc resp
             :body
             (let [typestring (get-in resp [:headers "content-type"])]
               (cond
                (.startsWith (str typestring) "text/")
                (if-let [charset (second (re-find #"charset=(.*)"
                                                  (str typestring)))]
                  (String. #^"[B" body ^String charset)
                  (String. #^"[B" body "UTF-8"))

                :else
                (String. #^"[B" body "UTF-8"))))
           ;; No :as matches found
           (assoc resp :body (String. #^"[B" body "UTF-8")))
         ;; Try the charset given if a string is specified
         (string? as)
         (assoc resp :body (String. #^"[B" body ^String as))
         ;; Return a regular UTF-8 string body
         :else
         (assoc resp :body (String. #^"[B" body "UTF-8")))
        resp))))

(defn wrap-input-coercion [client]
  (fn [{:keys [body body-encoding _length] :as req}]
    (if body
      (cond
        (string? body)
        (let [encoding-name (or body-encoding "UTF-8")
              charset (Charset/forName encoding-name)]
          (client (-> req (assoc :body (.getBytes ^String body charset)
                                 :character-encoding encoding-name))))
        :else
        (client req))
      (client req))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn wrap-content-type [client]
  (fn [{:keys [content-type] :as req}]
    (if content-type
      (client (-> req (assoc :content-type
                        (content-type-value content-type))))
      (client req))))

(defn wrap-accept [client]
  (fn [{:keys [accept] :as req}]
    (if accept
      (client (-> req (dissoc :accept)
                  (assoc-in [:headers "Accept"]
                            (content-type-value accept))))
      (client req))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding [client]
  (fn [{:keys [accept-encoding] :as req}]
    (if accept-encoding
      (client (-> req (dissoc :accept-encoding)
                  (assoc-in [:headers "Accept-Encoding"]
                            (accept-encoding-value accept-encoding))))
      (client req))))

(defn generate-query-string [params]
  (str/join "&"
            (mapcat (fn [[k v]]
                      (if (sequential? v)
                        (map #(str (util/url-encode (name %1))
                                   "="
                                   (util/url-encode (str %2)))
                             (repeat k) v)
                        [(str (util/url-encode (name k))
                              "="
                              (util/url-encode (str v)))]))
                    params)))

(defn wrap-query-params [client]
  (fn [{:keys [query-params] :as req}]
    (if query-params
      (client (-> req (dissoc :query-params)
                  (assoc :query-string
                    (generate-query-string query-params))))
      (client req))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (util/base64-encode (util/utf8-bytes basic-auth)))))

(defn wrap-basic-auth [client]
  (fn [req]
    (if-let [basic-auth (:basic-auth req)]
      (client (-> req (dissoc :basic-auth)
                  (assoc-in [:headers "Authorization"]
                            (basic-auth-value basic-auth))))
      (client req))))

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info [client]
  (fn [req]
    (if-let [[user password] (parse-user-info (:user-info req))]
      (client (assoc req :basic-auth [user password]))
      (client req))))

(defn wrap-method [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req (dissoc :method)
                  (assoc :request-method m)))
      (client req))))

(defn wrap-form-params [client]
  (fn [{:keys [form-params request-method] :as req}]
    (if (and form-params (= :post request-method))
      (client (-> req
                  (dissoc :form-params)
                  (assoc :content-type (content-type-value
                                        :x-www-form-urlencoded)
                         :body (generate-query-string form-params))))
      (client req))))

(defn- nest-params
  [req param-key]
  (if-let [params (req param-key)]
    (let [nested (walk/prewalk
                   #(if (and (vector? %) (map? (second %)))
                      (let [[fk m] %]
                        (reduce
                          (fn [m [sk v]]
                            (assoc m (str (name fk)
                                          \[ (name sk) \]) v))
                          {}
                          m))
                      %)
                   params)]
      (assoc req param-key nested))
    req))

(defn wrap-nested-params [client]
  (fn [req]
    (client (-> req (nest-params :form-params) (nest-params :query-params)))))

(defn wrap-url [client]
  (fn [req]
    (if-let [url (:url req)]
      (client (-> req (dissoc :url) (merge (parse-url url))))
      (client req))))

(defn wrap-unknown-host [client]
  (fn [{:keys [ignore-unknown-host?] :as req}]
    (try
      (client req)
      (catch UnknownHostException e
        (if ignore-unknown-host?
          nil
          (throw e))))))

(defn wrap-oauth [client]
  (fn [{:keys [oauth-token] :as req}]
    (if oauth-token
      (client (-> req (dissoc :oauth-token)
                  (assoc-in [:headers "Authorization"]
                            (str "Bearer " oauth-token))))
      (client req))))

(defn wrap-request
  "Returns a batteries-included HTTP request function."
  [request]
  ;; note to the uninitiated: wrapper behaviour is applied to requests in order listed here but
  ;; from last to first
  (-> request
      wrap-query-params
      wrap-basic-auth
      wrap-oauth
      wrap-user-info
      wrap-url
      wrap-redirects
      wrap-decompression
      wrap-input-coercion
      wrap-output-coercion
      wrap-exceptions
      wrap-accept
      wrap-accept-encoding
      wrap-content-type
      wrap-form-params
      wrap-nested-params
      wrap-method
      wrap-links
      wrap-unknown-host))

(def ^{:arglists '([req])}
  request
  "Returns response map for executed HTTP `req` map.

   Notice that some `req` key entries will be overwritten by automatic conversion to other key entries:

   Request method
   * `:method` -  ex. `:get`,`:head`,`:post`,`:put`,`:delete`, converts to `:request-method` with same value

   Request URL
   * `:url` - ex. `\"https://joe:blow@example.com:443/some/path?q=clojure\"`, converts to:
     * `:scheme` - protocol `:https`
     * `:server-name` - host `\"example.com\"`
     * `:server-port` - `443` (if not specified, will be inferred from `:scheme`)
     * `:uri` - path `\"/some/path\"`
     * `:user-info` -  `\"joe:blow\"`, converts to:
       * `:basic-auth` - which automatically converts to appropriate `:headers`
     * `:query-string` - `\"q=clojure\"`
   * `:query-params` - ex. `{\"q\" \"clojure\"}` or `{:q \"clojure\"}` converts to `:query-string` (see above)

   Request body & headers
   * `:body` - can be a string, byte array, File or input stream
   * `:body-encoding` - charset ex. `\"UTF-16\"`, defaults to `\"UTF-8\"`, iff `:body` is string converts to:
      * `:body` encoded in charset
      * `:character-encoding` set to charset which converts to appropriate `:headers` iff `:content-type` also set
   * `:content-type` - media type of request body, converts to appropriate `:headers` entry, specify:
     * keyword as shorthand, ex. `:json` for `\"application/json\"`
     * string for verboten, ex. `\"text/html\"`
   * `:form-params` - ex. `{\"q\" \"clojure\"}` or `{:q \"clojure\"}`, iff `:method` is `:post`: converts to:
      * urlencoded `:body`
      * appropriate `:headers` entry
   * `:oauth-token` - bearer authorization token, ex. `\"my70k3nh3r3\"`, converts to appropriate `:headers` entry
   * `:basic-auth` - basic authentication, converts to appropriate `:headers` entry, (see also `:url` and `:user-info`), specify:
     * vector `[\"uname\" \"pass\"]` becomes `\"uname:pass\"`
     * use string for verboten
   * `:accept-encoding` - vector of accepted response encodings, ex. `[:gzip :deflate :identity]`, converts to appropriate `:headers` entry
   * `:accept` - accept response of media type, converts to appropriate `:headers` entry, specify
     * keyword as shorthand, ex. `:json` for `\"application/json\"`
     * string for verboten, ex. `\"text/html\"`
   * `:headers` - explicitly set request headers, ex. `{\"Cache-Control\" \"no-cache\"}`

   Request behaviour
   * `:as` - specifies how response body should be coerced:
     * `:stream`
     * `:byte-array`
     * `:auto` - to string decoded with `charset` in response `:headers` `content-type` `charset` else UTF-8
     * `\"charset\"` - to string decoded with `charset` ex. `\"utf-16\"`
     * else - to string decoded with UTF-8
   * `:follow-redirects` - specify `false` to not follow response redirects
   * `:throw-exceptions` - specify `false` to not throw on https status error codes
   * `:ignore-unknown-host?` - specify `true` to not throw on unknown host
   * `:insecure?` - allow connection with an invalid SSL certificate
   * `:conn-timeout` - number of milliseconds to wait to establish a connection
   * `:socket-timeout` - number of milliseconds to wait for data to be available to read
   * `:save-request?` - specify `true` to include ultimate converted `:request` used in response map
   * `:chunk-size` - in bytes, enables streaming of HTTP request body with chunk-size bytes,
see [JDK docs](https://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html#setChunkedStreamingMode-int-)
for details

  Response map keys:
  * `:status` - http status code, see `:throw-exceptions` above
  * `:headers` - response headers
  * `:body` - response body, gzip and deflate responses are accepted and decompressed. See `:as` above.
  * `:request` - see `:save-request?` above

  See [README](/README.md#usage) for example usages."
  (wrap-request #'core/request))

(defn get
  "Executes HTTP GET request for `url` and, optionally, more `req` attributes.
  See [[request]]."
  [url & [req]]
  (request (merge req {:method :get :url url})))

(defn head
  "Executes HTTP HEAD request for `url` and, optionally, more `req` attributes.
  See [[request]]."
  [url & [req]]
  (request (merge req {:method :head :url url})))

(defn post
  "Executes HTTP POST request for `url` and, optionally, more `req` attributes.
  See [[request]]."
  [url & [req]]
  (request (merge req {:method :post :url url})))

(defn put
  "Executes HTTP PUT request for `url` and, optionally, more `req` attributes.
  See [[request]]."
  [url & [req]]
  (request (merge req {:method :put :url url})))

(defn delete
  "Executes HTTP DELETE request for `url` and, optionally, more `req` attributes.
  See [[request]]."
  [url & [req]]
  (request (merge req {:method :delete :url url})))

(defmacro with-connection-pool
  "This macro is a no-op, but left in to support backward-compatibility
  with clj-http."
  [_opts & body]
  `(do
     ~@body))
