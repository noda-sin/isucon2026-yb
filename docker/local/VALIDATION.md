# Local validation — 2026-10-31

実行環境: Apple Silicon macOS、Docker Desktop Engine 29.9.2 / Compose 5.6.1、VM 8 CPU / 7.75 GiB。YugabyteDB公式 `2025.2.8.0-b60` arm64イメージ。公式seedなし、合成smokeのみ。

## Passed

- `go test ./...`、GoアプリDocker build、検証バイナリDocker build。
- `docker compose config --quiet`。
- 3 Master / 6 TServer、3 Goアプリ / 3 nginxがhealthy。
- `yb_servers()` でAsia / Europe / America各2 TServerを確認。
- 各地域で実Read Committed: 同一transaction内の次statementが別connectionのcommit済み更新を読み取る。
- 各地域の行ロック排他と解除後の再開、地域間transaction advisory lock。
- global username一意性、FK、user地域変更のcommit/rollback。
- probeのregion partition振分け、移動、全3tabletのlocal2 + remote1 voting配置、local leader。
- API smoke: 3地域で登録/login/me、重複拒否、region mismatch、地域移動、logout。3地域同時の同username登録は201×1、409×2。
- UI index HTML: localhost18080 / 18081 / 18082が200。
- `docker compose down` (volume保持) → `up -d` 後、ユーザー数7、bootstrap作成日時、probe markerが同一。initは既存DBをresetせずskip。
- `ysql_dump --data-only` で合成backup取得、独立検証DBへ復元。初期schema全51table（画像日付partition含む）の件数とrow JSON hashが一致。

## 実起動で修正した点

- Master web UIはコンテナhostnameにbindされるためhealthcheckもその宛先を使用。
- 空白を含む `ysql_default_transaction_isolation` flagがYSQL設定生成で引用符エラーを起こしたため除去。実装有効flagは維持し、pgx接続時のRCを明示、実snapshot試験を実施。
- pgx URLの `+` は空白へ復号されなかったため、接続パラメータを `%20` にした。
- region partition親の主キーにglobal tablespaceが固定されるのを避け、probe作成前に `RESET default_tablespace`。
- 通常起動のSQL/UIをイメージに内包し、ホストbind mount依存をなくした。外部seedは明示overrideだけ。
- backupバイナリは固定image内の `/home/yugabyte/postgres/bin/ysql_dump`。

## 未実装・未検証

- Java実装: 未実装。全42 APIの移植範囲とsmart JDBC設定案をJAVA-MIGRATION.mdに整理。
- アプリ本体のregion partition化: 未実装。元PK/FKを保持するglobal RF3。local2+remote1はprobeだけ。
- Go接続は地域内の固定TServer。smart routing/自動接続failoverは未実装。
- 公式seed全量のimport/restore、42 API全項目、画像/SSE/ZIP/管理機能の完全回帰、性能ベンチマークは未検証。
- ホスト単体のため地域障害・ネットワーク遅延・本番耐久性を証明しない。
- 外部seed overrideのMacファイル共有はユーザー環境に依存。今回のrestore試験は標準入力でdata-only SQLを独立DBへ投入したもので、公式seed/overrideの全経路検証ではない。

失敗調査用DBと復元検証DBは合成データのみで、本番からの接続やデータ取得はしていない。
