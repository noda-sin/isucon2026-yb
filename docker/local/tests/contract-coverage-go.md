# API contract route coverage

| Route | go |
|---|---|
| DELETE /api/admin/users/:userId/certification | 401, 409 |
| DELETE /api/blocks/:userId | 204, 401, 403 |
| DELETE /api/comments/:commentId | 204, 401, 403, 404 |
| DELETE /api/follows/:userId | 204, 401, 409 |
| DELETE /api/friendships/:userId | 204, 401, 409 |
| DELETE /api/me/profile-image | 204, 401, 404 |
| DELETE /api/posts/:postId | 204, 401, 403 |
| DELETE /api/realmojis/:realMojiId | 204, 401, 403 |
| GET /api/feeds/:feed | 200, 400, 401 |
| GET /api/images/:imageId | 200, 403 |
| GET /api/me | 200, 401, 403 |
| GET /api/me/export | 200, 401 |
| GET /api/memories | 200, 400, 401 |
| GET /api/memories/:date | 200, 400, 401 |
| GET /api/notifications | 200, 401 |
| GET /api/posting-window/current | 200, 401 |
| GET /api/posts/:postId | 200, 401, 404 |
| GET /api/posts/:postId/comments | 200, 401 |
| GET /api/posts/:postId/realmojis | 200, 401 |
| GET /api/posts/me/current | 200, 401, 404 |
| GET /api/relationships/:kind | 200, 400, 401 |
| GET /api/users | 200, 400, 401 |
| GET /api/users/:userId | 200, 401, 404 |
| PATCH /api/me | 200, 400, 401, 415 |
| PATCH /api/posts/:postId | 200, 400, 401, 403 |
| POST /api/admin/auth/login | 200, 401 |
| POST /api/admin/auth/logout | 204, 401 |
| POST /api/admin/posting-window | 201, 400, 401, 409 |
| POST /api/auth/login | 200, 401 |
| POST /api/auth/logout | 204, 401 |
| POST /api/auth/register | 201, 400, 409 |
| POST /api/friend-requests | 201, 400, 401, 409 |
| POST /api/friend-requests/:requestId/:action | 200, 401, 403 |
| POST /api/initialize | 503 |
| POST /api/me/account-deletion | 204, 401 |
| POST /api/posts | 201, 400, 401, 409 |
| POST /api/posts/:postId/comments | 201, 400, 401, 403 |
| POST /api/posts/:postId/realmojis | 201, 400, 401, 403 |
| PUT /api/admin/users/:userId/certification | 204, 401, 409 |
| PUT /api/blocks/:userId | 204, 401, 409 |
| PUT /api/follows/:userId | 204, 401, 409 |
| PUT /api/me/profile-image | 204, 400, 401, 415 |
