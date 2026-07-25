(ns blog.post.ports
  "Port definitions for the post module (public read-only surface).

   The public blog site only reads posts; all write operations are
   handled by boundary-admin against the same `posts` table.")

;; =============================================================================
;; Repository Port
;; =============================================================================

(defprotocol IPostRepository
  "Read-only repository interface for post persistence.

   Implementations handle actual database operations.
   All methods are side-effectful (I/O operations)."

  (find-post-by-slug [this slug]
    "Find a published post by its URL slug.
     Returns the post map or nil if not found / not published.")

  (list-published-posts [this]
    "List all published posts ordered by published_at desc.
     Returns a vector of post maps."))
