# Java / geo validation — 2026-10-31

Apple Silicon / Docker Desktop29.9.2 / Compose5.6.1、VM8CPU/7.75GiB。公式YB2025.2.8.0-b60、Temurin21、smart JDBC42.7.3-yb-7。合成データだけを使用。

## 実行成功

- Docker JDK21 build、CoreTest7＋MediaTest6（13 unit tests、0 failures）。
- GoとJavaそれぞれ216ケース、42 APIすべて。無効initializeと不可逆認定の取消しを除き全routeの成功経路あり。正規化した両実装のstatus/body/対象headersを比較して一致。
- 独立HMAC1000回による署名検証、画像実体bytes、ZIPmanifest、SSE通知、region mismatch、session失効、friend/follow/block、投稿可視性、地域移動後の既存session/post/imageを検証。
- profile/post/RealMojiのJPEG末尾1/2/10/50/100byte欠損を拒否。異なるContent-TypeのJSONは415。
- 15 entity、45region partition。全entityのview DML/default/ロック、globalID/username/pair/部分一意性、FK孤児/削除拒否、限定roleの直接registryアクセス拒否。
- user地域変更が所有11entityへ原子的にCASCADE。関係先が別ownerならそのowner地域を維持。
- 競合5ケース: view RC snapshot、地域間table lock、user移動対子INSERT、別地域同時username登録、古い更新による上書き防止＋再試行。
- 45実体＋57副次indexの全102 tabletで実Raft voting replicaがRF3/local2＋remote1、leaderがlocal地域。
- smart JDBCの12接続サンプルが各地域で正確に2 local TServerを利用。6 TServerのpg_stat_activityでも各appのApplicationNameが対応地域だけに存在。
- Javaから各地域の2 TServerを跨ぐ実RC statement snapshotと通常行ロックの待機→commit後再開が成功。

[契約coverage](tests/contract-coverage.md)、[JDBC検証手順と結果](tests/JDBC-README.md)、[geo検証](geo/README.md)。

- 全構成のdown→up（volume保持）後もJavaユーザー総数・地域別件数・bootstrap作成日時が一致。geo-initは既存DBを保持してskip。再起動後の3地域登録/login/移動/logoutと同時username登録も成功。

## 発見して修正した問題

- Java ImageIOが切断JPEGを補修して受理するため、decode warningを拒否し回帰試験を追加。
- multipartの同名field/fileを分け、Goと同じ選択規則へ修正。不正UTF8、数値文法、Content-Typeも照合。
- アプリ再作成後にnginxが古いDocker IPへ接続し、別地域へ転送される差を発見。Docker DNS resolverを5秒TTLで使うよう修正。
- YBのregion partition作成時のPK tablespace継承、副次indexのpg_default配置を修正。metadataだけでなく実replicaを確認。
- inet_server_addr()::textは/32を含むため、JDBC地域判定試験ではhost(inet_server_addr())を使用。

## 固定YB版のNOWAIT制限

複数partitionを読むFOR UPDATE NOWAITで、Asia/Americaは期待SQLSTATE55P03ではなく40001（内部retry上限）になりました。Europeは55P03でした。[upstream #34844](https://github.com/yugabyte/yugabyte-db/issues/34844)と同じ条件の既知制約です。アプリ本体はNOWAITを使用しません。

通常のJDBC試験は地域接続・RC・待機型ロックだけをPASSとし、NOWAITはKNOWN_LIMITATIONと明示します。`ISUREAL_REQUIRE_PARTITION_NOWAIT=true` のstrict試験はAsiaで正しくexit1になることも確認しました。将来YB版を変えるときに再試験が必要です。

## 適用範囲

- Go初期原本を保持し、Javaは独立DB/role/profile。元の本番環境には接続していません。
- 公式seed、公式ベンチマーク、任意入力の完全byte互換は未検証。JSON APIの契約を対象とし、EchoのXML/form binder fallbackは含めません。詳細はJava README。
- view競合の40001/40P01は確認済みrollback後に最大4attemptでtransaction全体を再試行。接続断など結果不明のcommitを自動再実行しません。
- Java geo logical export/restoreは未検証。初期化時の限定roleによる合成seed INSERTは検証済みです。
- netemは設定・ON/OFF・測定コードと静的検証のみ。NET_ADMIN付与、tc設定、遅延実測は承認前のため未実行。
- 一台のMacであり実際の地域障害耐性はありません。global制約registryへのcross-region処理も残ります。
