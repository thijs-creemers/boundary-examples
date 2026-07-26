(ns blog.post.shell.persistence
  "SQLite persistence adapter for posts (read-only public surface).

   This is the IMPERATIVE SHELL in the FC/IS pattern.
   Contains all database I/O operations for the public blog site.
   Write operations are handled by boundary-admin against the same table."
  (:require [blog.post.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Data Conversion
;; =============================================================================

(defn- str->uuid
  "Parse a string to UUID, or return nil."
  [s]
  (when s
    (try (UUID/fromString s)
         (catch Exception _ nil))))

(defn- parse-instant
  "Parse an ISO-8601 string to Instant."
  [s]
  (when s
    (try (Instant/parse s)
         (catch Exception _ nil))))

(defn- db->post
  "Convert a qualified database row (with :posts/* keys) to a post entity."
  [row]
  (when row
    {:id         (str->uuid (:posts/id row))
     :author-id  (str->uuid (:posts/author_id row))
     :title      (:posts/title row)
     :slug       (:posts/slug row)
     :content    (:posts/content row)
     :excerpt    (:posts/excerpt row)
     :published  (= 1 (:posts/published row))
     :published-at (parse-instant (:posts/published_at row))
     :created-at   (parse-instant (:posts/created_at row))
     :updated-at   (parse-instant (:posts/updated_at row))}))

(defn- row->qualified
  "Re-key an unqualified result row to :posts/* qualified keys."
  [row]
  (update-keys row #(keyword "posts" (name %))))

;; =============================================================================
;; Repository Implementation
;; =============================================================================

(defrecord SQLitePostRepository [datasource]
  ports/IPostRepository

  (find-post-by-slug [_ slug]
    (let [result (jdbc/execute-one!
                  datasource
                  ["SELECT * FROM posts WHERE slug = ? AND published = 1" slug]
                  {:builder-fn rs/as-unqualified-maps})]
      (some-> result row->qualified db->post)))

  (list-published-posts [_]
    (let [rows (jdbc/execute!
                datasource
                ["SELECT * FROM posts WHERE published = 1 ORDER BY published_at DESC"]
                {:builder-fn rs/as-unqualified-maps})]
      (mapv (comp db->post row->qualified) rows))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-post-repository
  "Create a new SQLite post repository.

   Args:
     datasource: next.jdbc datasource

   Returns:
     IPostRepository implementation."
  [datasource]
  (->SQLitePostRepository datasource))
