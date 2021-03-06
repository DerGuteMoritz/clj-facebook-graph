; Copyright (c) Maximilian Weber. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file epl-v10.html at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns clj-facebook-graph.ring-middleware
  "Middleware for Ring to realize a simple Facebook authentication."
  (:use [clj-facebook-graph.helper :only [parse-params build-url]]
        [clj-facebook-graph.auth :only [get-access-token facebook-auth-url-str with-facebook-auth]]
        [ring.util.response :only [redirect]]
        [clj-http.client :only [generate-query-string]])
  (:import clj_facebook_graph.FacebookGraphException))

(defn add-facebook-auth
  "Adds the facebook-auth information (includes the access token) to the ring session."
  [session access-token]
  (assoc session :facebook-auth {:access-token access-token}))

(defn wrap-facebook-extract-callback-code
  "Extracts the code from the request which the Facebook Graph API appends as query
   parameter in the case of an successful authentication to the redirect_uri. It's
   flexible so that every URL and not only the login URL of your web application
   can be the redirect_uri of the Facebook authentication process.
   A redirect is triggered to the redirect_uri with out the code query parameter
   to get rid of the 'code' parameter in the web browser of your user, thereby the
   access token is associated with the user's session.
   An example:

   You have a path in your web application like:
   http://www.yourwebapp.com/albums/1511

   which displays the albums of a Facebook friend with the id 1511 of your user
   (also a Facebook user). For this reason your application needs a Facebook access token
   on behalf of your user to get access to the albums of the user's friend.
   After the user has successfully logged into Facebook, Facebook redirects the user back
   to your specified redirect_uri (in this case 'http://www.yourwebapp.com/albums/1511')
   with a query parameter 'code' appended. This code can be used by your web application to
   get an Facebook access token. So here the web browser of your user comes back
   from Facebook to the following URL of your application:

   http://www.yourwebapp.com/albums/1511?code=123-example-code

   This middleware extracts the code and use it to receive an access token from Facebook. Then
   it triggers a redirect to:

   http://www.yourwebapp.com/albums/1511

   To get rid of the 'code' query parameter in the browser of your user. In the same step the
   received access token is associated with the user's session in your web application.

   Pay attention that you have specified the correct :redirect-uri in your facebook-app-info
   otherwise this middleware can not detect if the request is a redirect for the Facebook
   authentication or just a request with a code query parameter.
   "
  [handler facebook-app-info]
  (let [{:keys [client-id client-secret]} facebook-app-info]
    (fn [request]
      (let [code (get-in request [:params "code"])
            callback-path (.getPath (java.net.URI. (:redirect-uri facebook-app-info)))]
        (if (and code
                 (= callback-path (:uri request)))
          (let [query-params (parse-params (:query-string request))
                query-params (dissoc query-params "code")
                redirect-uri (build-url (assoc request :query-string
                                               (generate-query-string query-params)))
                access-token (get-access-token client-id redirect-uri client-secret code)
                session (add-facebook-auth (:session request) access-token)]
            (assoc (redirect redirect-uri) :session session))
          (handler request))))))

(defn wrap-facebook-access-token-required
  "If the middleware for Facebook access through clj-http throws a FacebookGraphException
   which was caused by an OAuthException error then this peace of Ring middleware triggers a
   redirect to the Facebook authentication page. The following OAuthException errors are
   handled at the moment:
     - :invalid-access-token - The access token which is used by your application is invalid,
                               mostly it is expired.
     - :access-token-required - At the moment your application has not been using an access
                               at all to do Facebook Graph API requests, so your user have to
                               do a Facebook login first. 
   See #'wrap-facebook-extract-callback-code for an example request flow.
   If you need a Facebook at any point of the Ring request processing, you can throw
   a FacebookGraphException with the following error: {:error :facebook-login-required}."
  [handler facebook-app-info]
  (let [{:keys [client-id permissions]} facebook-app-info
        auth-errors #{[:OAuthException :invalid-access-token]
                      [:OAuthException :access-token-required]}]
    (fn [request]
      (try
        (handler request)
        (catch FacebookGraphException e
          (if (let [error (:error @e)] (or (auth-errors error)
                                           (= :facebook-login-required error)))
            (let [redirect-uri (build-url request)]
              (redirect (facebook-auth-url-str client-id redirect-uri permissions)))
            (throw e)))))))

(defn wrap-facebook-auth [handler]
  "Binds the facebook-auth (access-token) information to the thread bounded *facebook-auth*
   variable (by using with-facebook-auth) so it can be used by the Ring-style middleware
   for clj-http to access the Facebook Graph API."
  (fn [request]
    (if-let [facebook-auth (get-in request [:session :facebook-auth])]
      (with-facebook-auth facebook-auth (handler request))
      (handler request))))
