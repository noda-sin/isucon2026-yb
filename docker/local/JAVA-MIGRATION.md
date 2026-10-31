> 更新: Java全42 APIとgeo profileを実装済みです。下記は当初の移植設計記録です。現在の実行結果は [VALIDATION-JAVA.md](VALIDATION-JAVA.md)、制限は [Java README](../../webapp/java/README.md) を参照してください。

# Java 移植計画（未実装）

現時点の起動対象は初期 Go 実装です。Java サーバー、Java Docker image、Java への切り替え設定は未実装です。この文書は全機能の移植範囲を固定する計画であり、Java でアプリが動作した証拠ではありません。

## 基準と規模

基準は、このリポジトリの `bdaaccf`（取得元初期版 `838544a` の保存 snapshot）です。Go は約3,800行、42本の業務ルートを持ちます。ローカル追加の `GET /healthz` は別途移植します。ルート数だけでなく、共有する認証・アクセス判定・画像処理・SQL とトランザクションの移植が必要です。所要時間の約束はせず、以下の機能単位で完了を判定します。

実装先候補は `webapp/java/`。JDK 21、HTTP router、JSON serializer、HikariCP と JDBC を使う構成を候補とし、フレームワークは実装開始時に固定します。SQL を直接移植し、初回移植と同時に ORM 化・SQL 最適化・全テーブル地域partition化を行いません。各 HTTP request の transaction は同じ借用 JDBC connection 上で完結させ、commit/rollback、connection返却を必ず行います。

## 全42 APIの移植台帳

`webapp/go/server.go` から抽出。`none` はセッション middleware がない意味で、個別 handler の検証が不要な意味ではありません。全 API に IP ban と action log の共通処理があります。

| # | route | Go handler | middleware認証 |
|---|---|---|---|
| 1 | `POST /api/initialize` | `initializeHandler` | none |
| 2 | `POST /api/auth/register` | `registerHandler` | none |
| 3 | `POST /api/auth/login` | `loginHandler` | none |
| 4 | `POST /api/auth/logout` | `logoutHandler` | user |
| 5 | `GET /api/users` | `searchUsersHandler` | user + region |
| 6 | `GET /api/users/:userId` | `getUserHandler` | user + region |
| 7 | `GET /api/me/export` | `exportPostsHandler` | user + region |
| 8 | `GET /api/me` | `getMeHandler` | user + region |
| 9 | `PATCH /api/me` | `updateMeHandler` | user |
| 10 | `PUT /api/me/profile-image` | `putProfileImageHandler` | user |
| 11 | `DELETE /api/me/profile-image` | `deleteProfileImageHandler` | user |
| 12 | `POST /api/me/account-deletion` | `deleteAccountHandler` | user + region |
| 13 | `POST /api/posts` | `createPostHandler` | user + region |
| 14 | `GET /api/posting-window/current` | `getCurrentPostingWindowHandler` | user + region |
| 15 | `GET /api/posts/me/current` | `getMyCurrentPostHandler` | user + region |
| 16 | `GET /api/posts/:postId` | `getPostHandler` | user + region |
| 17 | `PATCH /api/posts/:postId` | `updatePostHandler` | user + region |
| 18 | `DELETE /api/posts/:postId` | `deletePostHandler` | user + region |
| 19 | `GET /api/feeds/:feed` | `getFeedHandler` | user + region |
| 20 | `GET /api/memories` | `getMemoriesHandler` | user + region |
| 21 | `GET /api/memories/:date` | `getMemoriesByDateHandler` | user + region |
| 22 | `POST /api/posts/:postId/comments` | `createCommentHandler` | user + region |
| 23 | `GET /api/posts/:postId/comments` | `getCommentsHandler` | user + region |
| 24 | `DELETE /api/comments/:commentId` | `deleteCommentHandler` | user + region |
| 25 | `POST /api/posts/:postId/realmojis` | `replaceRealMojiHandler` | user + region |
| 26 | `GET /api/posts/:postId/realmojis` | `getRealMojisHandler` | user + region |
| 27 | `DELETE /api/realmojis/:realMojiId` | `deleteRealMojiHandler` | user + region |
| 28 | `GET /api/images/:imageId` | `getImageHandler` | none |
| 29 | `GET /api/notifications` | `getNotificationsHandler` | user + region |
| 30 | `POST /api/friend-requests` | `createFriendRequestHandler` | user + region |
| 31 | `POST /api/friend-requests/:requestId/:action` | `transitionFriendRequestHandler` | user + region |
| 32 | `GET /api/relationships/:kind` | `getRelationshipsHandler` | user + region |
| 33 | `DELETE /api/friendships/:userId` | `deleteFriendshipHandler` | user + region |
| 34 | `PUT /api/follows/:userId` | `followHandler` | user + region |
| 35 | `DELETE /api/follows/:userId` | `unfollowHandler` | user + region |
| 36 | `PUT /api/blocks/:userId` | `blockHandler` | user + region |
| 37 | `DELETE /api/blocks/:userId` | `unblockHandler` | user + region |
| 38 | `POST /api/admin/auth/login` | `adminLoginHandler` | none |
| 39 | `POST /api/admin/auth/logout` | `adminLogoutHandler` | admin |
| 40 | `PUT /api/admin/users/:userId/certification` | `certifyUserHandler` | admin |
| 41 | `DELETE /api/admin/users/:userId/certification` | `deleteUserCertificationHandler` | admin |
| 42 | `POST /api/admin/posting-window` | `openPostingWindowHandler` | admin |

## 実装する単位

1. 起動・接続・HTTP共通部: 環境変数、region検証、healthcheck、JSON errorとHTTP status、IP抽出・ban、action log、リクエスト中断、DB接続pool、transaction管理。ローカルの `/api/initialize` 無効化も保持します。
2. ユーザーと認証: 登録、グローバルusername競合、SHA-256 secret/token保存、session失効、ユーザー検索、プロフィール更新、地域移動、管理者認証・認定、account deletion。GoのUnicode code point数をJavaのUTF-16文字数に置換しません。
3. 画像と投稿: profile image、画像本体のDB保存、署名URL、posting window、投稿作成/参照/更新/削除、通知。HMAC-SHA256の1,000反復、`exp:imageId`、URL-safe Base64無padding、24時間有効期限、期限切れ/署名不正判定をfixtureで比較します。
4. 関係とリアクション: friend request状態遷移、friendships、follows、block、comments、realmojis。双方向block、可視性、同時操作、論理削除とreplaceの境界を含みます。
5. 読み取りとexport: feed種別、ページング/順序、memoriesと地域日付、通知、ZIP export。ZIPは圧縮バイト列そのものより、entry名・順序・画像byte列・`posts.json`・応答headerを検証します。
6. コンテナ/切り替え: 同一3地域構成にJavaを別profileとして追加し、各地域で独立poolを起動します。Goへの復帰方法を残し、すべての互換試験成功後のみJavaを既定候補にします。

## Yugabyte smart JDBC の固定候補

公式ドキュメント掲載の `com.yugabyte:jdbc-yugabytedb:42.7.3-yb-7` を候補として固定します。driver classは `com.yugabyte.Driver`、URL schemeは `jdbc:yugabytedb:`。これは依存解決・実接続をまだ実施していない設定案です。[公式JDBC導入例](https://docs.yugabyte.com/stable/develop/drivers-orms/java/yugabyte-jdbc/)

```xml
<dependency>
  <groupId>com.yugabyte</groupId>
  <artifactId>jdbc-yugabytedb</artifactId>
  <version>42.7.3-yb-7</version>
</dependency>
```

Asiaの起点URLは `jdbc:yugabytedb://tserver-asia-1:5433,tserver-asia-2:5433/isureal`。Europe/Americaは同じ形式で地域名を置換します。資格情報は環境変数から `Properties` へ渡し、URL・ログに含めません。JavaコンテナはTServerと同じCompose networkに配置し、`yb_servers()` が返すアドレスへ到達できることを確認します。ホスト側localhost公開portへの接続を、発見後のコンテナIPへの接続と混同しません。

| property | Asiaの提案値 |
|---|---|
| `load-balance` | `true` |
| `topology-keys` | `local.asia.asia:1` |
| `fallback-to-topology-keys-only` | `true` |
| `yb-servers-refresh-interval` | `30` |
| `connectTimeout` | `10` |
| `sslmode` | `disable`（ローカル専用） |

Europe/Americaは `local.europe.europe:1` / `local.america.america:1`。この案では地域内の2 TServerに限定し、地域内全停止は接続失敗として見えるようにします。地域間fallbackが必要になった場合は、全3地域を`:1/:2/:3`で列挙した別ポリシーをレビューします。公式仕様上、topology指定にはload balancing有効化が必要です。これは新規connectionの接続先選択であり、既存transactionの移動やデータpartition配置を実現する機能ではありません。[公式JDBC参照](https://docs.yugabyte.com/stable/develop/drivers-orms/java/yugabyte-jdbc-reference/)

各connectionを `Connection.TRANSACTION_READ_COMMITTED` に設定し、pool返却時の状態復旧も試験します。サーバー側 `yb_enable_read_committed_isolation=true` は必須です。`SHOW transaction_isolation` だけでなく、同一transactionの2文目が他sessionのcommitを観測することを確認します。[公式Read Committed仕様](https://docs.yugabyte.com/stable/architecture/transactions/read-committed/)

## 完了の判定条件

- **依存/build**: 固定JDK/JDBCと選定したHTTP/JSON/poolの正確なversionを記録し、clean build、unit tests、コンテナ起動を確認。ライブラリ同士のdriver競合を確認。
- **全API契約**: 同じ合成fixtureを独立DBに用意してGo/Javaへ同じリクエスト列を実行し、42ルートすべての成功・主要失敗・認証・地域条件を比較。UUID/時刻など非決定値だけ明示的に正規化。空配列とnull、数値/文字列、時刻精度・timezone、Unicode長、HTTP status/header、error code、並び順を比較。
- **整合性**: 複数地域で同じusernameを同時登録して成功が1件、他は409。PK/FK違反、参照中削除、session失効、地域移動前後のアクセス、画像所有者/署名、blockとfriend操作競合を試験。`region` をUNIQUEに加えてグローバル一意性を緩めない。
- **RC/lock**: 同一transaction内のstatement snapshot、`FOR UPDATE` / `FOR NO KEY UPDATE`待機、競合commit/rollback、元SQLのtable lock、deadlock/timeout時のrollback、poolへのconnection返却を複数sessionで確認。エラーを無制限にretryせず、SQLSTATEと応答の取り扱いを明記。
- **配置/locality**: Java各地域から複数の独立connectionを作り、実接続先が地域内2 TServerだけになることを記録。片方停止時に新規接続がもう片方へ向くことを確認。tablets/leaderのlocal2+remote1は別途DBの配置検証で確認し、JDBC設定だけで達成したと判定しない。
- **永続性**: 合成ユーザー・投稿を作成し、通常停止/再起動後もAPIから同じIDと画像を取得。volume削除操作はこの検証に混ぜない。
- **公式データ**: 配布後にdata-only importと別途公式benchmarkを実施する。合成smoke成功を公式benchmark互換や性能保証の代替にしない。現時点は公式データ・Javaとも未検証。

## 取得元とライセンス

初期実装はローカル保存repoから複製したsnapshotで、元repoは変更しません。競技中の最適化は移植基準に含めません。このsnapshotにLICENSE/COPYING/NOTICEファイルは見つかっていません（2026-10-31にtracked treeと作業treeを確認）。MIT等を推測して新設せず、著作権・配布条件は公式公開時に照合します。ライセンス不在は自由な再配布許諾を意味しません。既存READMEの取得元と公開に関する記述を保持します。

[公式まとめ](https://isucon.net/archives/60124520.html)は `isucon/isucon2026` を問題リポジトリとして案内していますが、この調査時の公開URL取得は404でした。公式Java実装は確認できません。snapshotに存在するのはGo/Node.js/Perl/PHP/Python/Ruby/Rustです。今後公式Java実装が公開された場合は、ライセンスとAPI版の一致を確認してから移植方針を再評価します。
