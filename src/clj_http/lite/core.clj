(ns clj-http.lite.core
  "Core HTTP request/response implementation."
  (:require [clojure.java.io :as io])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net HttpURLConnection URL)
           (javax.net.ssl HostnameVerifier HttpsURLConnection SSLContext SSLSession TrustManager X509TrustManager)))

(set! *warn-on-reflection* true)

(defn parse-headers
  "Returns a map of names to values for URLConnection `conn`.

   If a header name appears more than once (like `set-cookie`) then the value
   will be a vector containing the values in the order they appeared
   in the headers."
  [conn]
  (loop [i 1 headers {}]
    (let [k (.getHeaderFieldKey ^HttpURLConnection conn i)
          v (.getHeaderField ^HttpURLConnection conn i)]
      (if k
        (recur (inc i) (update-in headers [k] conj v))
        (zipmap (for [k (keys headers)]
                  (.toLowerCase ^String k))
                (for [v (vals headers)]
                  (if (= 1 (count v))
                    (first v)
                    (vec v))))))))

(defn- coerce-body-entity
  "Return body response from HttpURLConnection `conn` coerced to either a byte-array,
  or a stream."
  [{:keys [as]} conn]
  (let [ins (try
              (.getInputStream ^HttpURLConnection conn)
              (catch Exception _e
                (.getErrorStream ^HttpURLConnection conn)))]
    (if (or (= :stream as) (nil? ins))
      ins
      (with-open [ins ^InputStream ins
                  baos (ByteArrayOutputStream.)]
        (io/copy ins baos)
        (.flush baos)
        (.toByteArray baos)))))

(def ^:private trust-all-hostname-verifier
  (delay
    (proxy [HostnameVerifier] []
      (verify [^String hostname ^SSLSession session] true))))

(def ^:private trust-all-ssl-socket-factory
  (delay
    (.getSocketFactory
      (doto (SSLContext/getInstance "SSL")
        (.init nil (into-array TrustManager [(reify X509TrustManager
                                               (getAcceptedIssuers [_this] nil)
                                               (checkClientTrusted [_this _certs _authType])
                                               (checkServerTrusted [_this _certs _authType]))])
               (new java.security.SecureRandom))))))

(defn- trust-all-ssl!
  [conn]
  (when (instance? HttpsURLConnection conn)
    (let [^HttpsURLConnection ssl-conn conn]
      (.setHostnameVerifier ssl-conn @trust-all-hostname-verifier)
      (.setSSLSocketFactory ssl-conn @trust-all-ssl-socket-factory))))

(defn request
  "Executes the HTTP request corresponding to the given Ring `req` map and
   returns the Ring response map corresponding to the resulting HTTP response."
  [{:keys [request-method scheme server-name server-port uri query-string
           headers content-type character-encoding body socket-timeout
           conn-timeout insecure? save-request? follow-redirects
           chunk-size] :as req}]
  (let [http-url (str (name scheme) "://" server-name
                      (when server-port (str ":" server-port))
                      uri
                      (when query-string (str "?" query-string)))
        ^HttpURLConnection conn (.openConnection (URL. http-url))]
    (when insecure?
      (trust-all-ssl! conn))
    (when (and content-type character-encoding)
      (.setRequestProperty conn "Content-Type" (str content-type
                                                    "; charset="
                                                    character-encoding)))
    (when (and content-type (not character-encoding))
      (.setRequestProperty conn "Content-Type" content-type))
    (doseq [[h v] headers]
      (.setRequestProperty conn h v))
    (when (false? follow-redirects)
      (.setInstanceFollowRedirects conn false))
    (.setRequestMethod conn (.toUpperCase (name request-method)))
    (when body
      (.setDoOutput conn true))
    (when socket-timeout
      (.setReadTimeout conn socket-timeout))
    (when conn-timeout
      (.setConnectTimeout conn conn-timeout))
    (when chunk-size
      (.setChunkedStreamingMode conn chunk-size))
    (.connect conn)
    (when body
      (with-open [out (.getOutputStream conn)]
        (io/copy body out)))
    (merge {:headers (parse-headers conn)
            :status (.getResponseCode conn)
            :body (when-not (= request-method :head)
                    (coerce-body-entity req conn))}
           (when save-request?
             {:request (assoc (dissoc req :save-request?)
                              :http-url http-url)}))))
