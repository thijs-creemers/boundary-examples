(ns blog.post.shell.http
  "Public HTTP handlers for the blog post module.

   This is the IMPERATIVE SHELL that handles HTTP I/O.
   Exposes two read-only routes:
     GET /          — home page (list of published posts)
     GET /posts/:slug — single post detail

   Write operations (create / edit / delete) are handled by boundary-admin."
  (:require [blog.post.ports :as ports]
            [blog.post.core.ui :as ui]
            [blog.shared.ui.layout :as layout]
            [ring.util.response :as response]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- render-page
  "Render a full HTML page response."
  [title content opts]
  (-> (layout/render (layout/page title content opts))
      (response/response)
      (response/content-type "text/html")))

(defn- not-found-page
  "Render a 404 response."
  [opts]
  (-> (render-page "Not Found"
                   [:div
                    [:h1 "Not Found"]
                    [:p "The page you're looking for doesn't exist."]
                    [:a {:href "/"} "Go home"]]
                   opts)
      (response/status 404)))

;; =============================================================================
;; Public Handlers
;; =============================================================================

(defn home-handler
  "GET / — Home page listing published posts.

   Args:
     repo: IPostRepository implementation"
  [repo]
  (fn [request]
    (let [posts (ports/list-published-posts repo)
          opts  {:active    :home
                 :blog-name (:blog-name (:config request) "My Blog")
                 :flash     (:flash request)}]
      (render-page "Latest Posts"
                   (ui/home-page-content posts)
                   opts))))

(defn post-handler
  "GET /posts/:slug — Single published post view.

   Returns HTTP 404 when the slug is unknown or the post is not published.

   Args:
     repo: IPostRepository implementation"
  [repo]
  (fn [request]
    (let [slug (get-in request [:path-params :slug])
          post (ports/find-post-by-slug repo slug)
          opts {:blog-name (:blog-name (:config request) "My Blog")
                :flash     (:flash request)}]
      (if post
        (render-page (:title post)
                     (ui/post-page-content post)
                     (assoc opts :description (:excerpt post)))
        (not-found-page opts)))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Return reitit route data for the public post module.

   Args:
     repo: IPostRepository implementation

   Returns:
     Vector of reitit route vectors."
  [repo]
  [["/"          {:get {:handler (home-handler repo)}}]
   ["/posts/:slug" {:get {:handler (post-handler repo)}}]])
