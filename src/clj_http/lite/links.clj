(ns clj-http.lite.links
  "Namespace dealing with HTTP link headers

  Imported from https://github.com/dakrone/clj-http/blob/217393258e7863514debece4eb7b23a7a3fa8bd9/src/clj_http/links.clj")

(set! *warn-on-reflection* true)

(def ^:private quoted-string
  #"\"((?:[^\"]|\\\")*)\"")

(def ^:private token
  #"([^,\";]*)")

(def ^:private link-param
  (re-pattern (str "(\\w+)=(?:" quoted-string "|" token ")")))

(def ^:private uri-reference
  #"<([^>]*)>")

(def ^:private link-value
  (re-pattern (str uri-reference "((?:\\s*;\\s*" link-param ")*)")))

(def ^:private link-header
  (re-pattern (str "(?:\\s*(" link-value ")\\s*,?\\s*)")))

(defn read-link-params [params]
  (into {}
        (for [[_ name quot tok] (re-seq link-param params)]
          [(keyword name) (or quot tok)])))

(defn read-link-value [value]
  (let [[_ uri params] (re-matches link-value value)
        param-map      (read-link-params params)]
    [(keyword (:rel param-map))
     (-> param-map
         (assoc :href uri)
         (dissoc :rel))]))

(defn read-link-headers [header]
  (->> (re-seq link-header header)
       (map second)
       (map read-link-value)
       (into {})))

(defn- links-response
  [response]
  (if-let [link-headers (get-in response [:headers "link"])]
    (let [link-headers (if (coll? link-headers)
                         link-headers
                         [link-headers])]
      (assoc response
        :links
        (into {} (map read-link-headers link-headers))))
    response))

(defn wrap-links
  "Returns request wrapper fn for `client` that adds
  a `:links` key to the response map that contains parsed link headers.

  The links are returned as a map, with the 'rel' value being the key. The
  URI is placed under the 'href' key, to mimic the HTML link element.

  e.g. Link: <http://example.com/page2.html>; rel=next; title=\"Page 2\"
  => {:links {:next {:href \"http://example.com/page2.html\"
  :title \"Page 2\"}}}"
  [client]
  (fn
    ([request]
      (links-response (client request)))
    ([request respond raise]
      (client request #(respond (links-response %)) raise))))
