(ns orcpub.routes
  (:require [io.pedestal.http :as http]          
            [io.pedestal.http.route :as route]
            [io.pedestal.test :as test]
            [io.pedestal.http.ring-middlewares :as ring]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor.error :as error-int]
            [io.pedestal.interceptor.chain :refer [terminate]]
            #_[com.stuartsierra.component :as component]
            [buddy.auth.protocols :as proto]
            [buddy.auth.backends :as backends]
            [buddy.sign.jwt :as jwt]
            [buddy.hashers :as hashers]
            [clojure.java.io :as io]
            [clj-time.core :refer [hours from-now]]
            [clojure.string :as s]
            [orcpub.dnd.e5.skills :as skill5e]
            [orcpub.dnd.e5.character :as char5e]
            [datomic.api :as d])
  (:import (org.apache.pdfbox.pdmodel.interactive.form PDCheckBox PDComboBox PDListBox PDRadioButton PDTextField)
           (org.apache.pdfbox.pdmodel PDDocument PDPageContentStream)
           (org.apache.pdfbox.pdmodel.graphics.image PDImageXObject)
           (java.io ByteArrayOutputStream ByteArrayInputStream)
           (org.apache.pdfbox.pdmodel.graphics.image JPEGFactory LosslessFactory)
           (org.eclipse.jetty.server.handler.gzip GzipHandler)
           (javax.imageio ImageIO)
           (java.net URL))
  (:gen-class))

(defonce secret "lakdsjflkdjflakdsjflaskdjflaksjdflsadjlfjdl")

(def backend (backends/jws {:secret secret}))

#_(def encryption {:secret privkey
                 :options {:alg :rsa-oaep
                           :enc :a128-hs256}})

(def conn)

(defn lookup-user [db username password]
  (prn "DB" db)
  (first
   (d/q '{:find [[?e]]
          :in [$ ?username ?password]
          :where [(or [?e :orcpub.user/username ?username]
                      [?e :orcpub.user/email ?username])
                  [?e :orcpub.user/password ?enc]
                  [(buddy.hashers/check ?password ?enc)]]}
        db username password)))

(def check-auth
  {:name :check-auth
   :enter (fn [{:keys [request] :as context}]
     (let [req (try (some->> (proto/-parse backend request)
                             (proto/-authenticate backend request))
                    (catch Exception _))]
       (if (:identity req)
         (assoc context :request req)
         (-> context
             terminate
             (assoc :response {:status 401 :body {:message "Unauthorized"}})))))})

(defn login-response
  [{:keys [json-params db] :as request}]
  (let [{:keys [username password]} json-params
        user (lookup-user db username password)
        valid? (some? user)]
    (prn "VALID?" valid?)
    (if-not valid?
      {:status 401 :body {:message "Wrong credentials"}}
      (let [claims {:user user
                    :exp (-> 3 hours from-now)}
            token (jwt/sign claims secret)]
        {:status 200 :body {:user (d/pull db '[*] user)
                            :token token}}))))

(defn login [{:keys [json-params db] :as request}]
  (prn "LOGIN" login)
  (try
    (login-response request)
    (catch Exception e (prn "E" e))))

(defn register [{:keys [json-params db] :as request}]
  (let [{:keys [username email password first-and-last-name]} json-params]
    (cond
      (d/q '[:find ?e :where [?e :orcpub.user/email email]] db)
      {:status 400 :body {:message "Email address is already taken"}}

      (d/q '[:find ?e :where [?e :orcpub.user/username username]] db)
      {:status 400 :body {:message "username is already taken"}}

      :else
      (do @(d/transact
            conn
            [{:orcpub.user/email email
              :orcpub.user/username username
              :orcpub.user/password (hashers/encrypt password)
              :orcpub.user/first-and-last-name first-and-last-name}])
          (login-response (assoc request :db (d/db conn)))))))

(def font-sizes
  (merge
   (zipmap (map :key skill5e/skills) (repeat 8))
   (zipmap (map (fn [k] (keyword (str (name k) "-save"))) char5e/ability-keys) (repeat 8))
   {:features-and-traits 8
    :features-and-traits-2 8
    :attacks-and-spellcasting 8
    :backstory 8
    :other-profs 8
    :equipment 8
    :weapon-name-1 8
    :weapon-name-2 8
    :weapon-name-3 8}))

(defn write-fields! [doc fields flatten]
  (let [catalog (.getDocumentCatalog doc)
        form (.getAcroForm catalog)]
    (.setNeedAppearances form true)
    (doseq [[k v] fields]
      (try
        (let [field (.getField form (name k))]
          (when field
            (if (and (font-sizes k) flatten)
              (.setDefaultAppearance field (str "/Helv " (font-sizes k) " Tf 0 0 0 rg")))
            (.setValue
             field
             (cond 
               (instance? PDCheckBox field) (if v "Yes" "Off")
               (instance? PDTextField field) (str v)
               :else nil))))
        (catch Exception e (prn "failed writing field: " k v (clojure.stacktrace/print-stack-trace e)))))
    (when flatten
      (.setNeedAppearances form false)
      (.flatten form))))

(defn content-stream [doc page]
  (PDPageContentStream. doc page true false true))

(defn in-to-sz [inches]
  (float (* 72 inches)))

(defn in-to-coord-x [inches]
  (in-to-sz inches))

(defn in-to-coord-y [inches]
  (in-to-sz (- 11 inches)))

(defn scale [[r-h r-w] [i-h i-w]]
  (let [height-to-width (/ i-h i-w)
        rect-height-to-width (/ r-h r-w)
        height-ratio (/ r-h i-h)]
    (if (> height-to-width rect-height-to-width)
      [r-h (* r-h (/ i-w i-h))]
      [(* r-w (/ i-h i-w)) r-w])))

(defn draw-image! [doc page url x y width height]
  (let [lower-case-url (s/lower-case url)]
    (try
      (with-open [img (if (or (s/ends-with? lower-case-url "jpg")
                              (s/ends-with? lower-case-url "jpeg"))
                        (JPEGFactory/createFromStream doc (.openStream (URL. url)))
                        (LosslessFactory/createFromImage doc (ImageIO/read (URL. url))))
                  c-stream (content-stream doc page)]
        (let [[scaled-height scaled-width] (scale [height width] [(.getHeight img) (.getWidth img)])]
          (.drawImage
           c-stream
           img
           (in-to-coord-x (+ x (if (< scaled-width width)
                                 (/ (- width scaled-width) 2)
                                 0)))
           (in-to-coord-y (+ height y (if (< scaled-height height)
                                        (/ (- scaled-height height) 2)
                                        0)))
           (in-to-sz scaled-width)
           (in-to-sz scaled-height))))
      (catch Exception e (prn "failed loading image" (clojure.stacktrace/print-stack-trace e))))))

(defn get-page [doc index]
  (.getPage doc index))

(defn character-pdf-2 [req]
  (prn "CHARACTER PDFxU" req)
  (let [body-map (io.pedestal.http.route/parse-query-string (slurp (:body req)))
        fields (clojure.edn/read-string (:body body-map))
        {:keys [image-url image-url-failed faction-image-url faction-image-url-failed]} fields
        input (.openStream (io/resource (cond
                                          (find fields :spellcasting-class-6) "fillable-char-sheet-6-spells.pdf"
                                          (find fields :spellcasting-class-5) "fillable-char-sheet-5-spells.pdf"
                                          (find fields :spellcasting-class-4) "fillable-char-sheet-4-spells.pdf"
                                          (find fields :spellcasting-class-3) "fillable-char-sheet-3-spells.pdf"
                                          (find fields :spellcasting-class-2) "fillable-char-sheet-2-spells.pdf"
                                          (find fields :spellcasting-class-1) "fillable-char-sheet-1-spells.pdf"
                                          :else "fillable-char-sheet-0-spells.pdf")))
        output (ByteArrayOutputStream.)
        user-agent (get-in req [:headers "user-agent"])
        chrome? (re-matches #".*Chrome.*" user-agent)]
    (with-open [doc (PDDocument/load input)]
      (write-fields! doc fields (not chrome?))
      (if (and image-url
               (re-matches #"^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]" image-url)
               (not image-url-failed))
        (draw-image! doc (get-page doc 1) image-url 0.45 1.75 2.35 3.15))
      (if (and faction-image-url
               (re-matches #"^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]" faction-image-url)
               (not faction-image-url-failed))
        (draw-image! doc (get-page doc 1) faction-image-url 5.88 2.4 1.905 1.52))
      (.save doc output))
    (let [a (.toByteArray output)]
      {:status 200 :body (ByteArrayInputStream. a)})))

(defn html-response
  [html]
  {:status 200 :body html :headers {"Content-Type" "text/html"}})

(defn index
  [req]
  (html-response
   (slurp (io/resource "public/index.html"))))

(def service-error-handler
  (error-int/error-dispatch [ctx ex]
    
    [{:exception-type :java.lang.ArithmeticException :interceptor ::another-bad-one}]
    (assoc ctx :response {:status 400 :body "Another bad one"})

    
    [{:exception-type :java.lang.ArithmeticException}]
    (assoc ctx :response {:status 400 :body "A bad one"})

    :else
    (do
      (prn "EXCEPTION" ex)
      (assoc ctx :io.pedestal.interceptor.chain/error ex))))

(def db-interceptor
  {:name :db-interceptor
   :enter (fn [context]
            (let [db (d/db conn)]
              (prn "CONN" conn db)
              (update context :request assoc :db db)))})

(def routes
  (route/expand-routes
   [[["/" {:get `index}]
     ["/register" ^:interceptors [(body-params/body-params) #_db-interceptor]
      {:post `register}]
     ["/login" ^:interceptors [(body-params/body-params) #_db-interceptor]
      {:post `login}]
     ["/character.pdf" {:post `character-pdf-2}]]]))
