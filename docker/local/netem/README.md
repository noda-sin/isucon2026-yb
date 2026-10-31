# Cross-region netem overlay — execution pending approval

**コードと静的検証のみ。コンテナ起動、NET_ADMIN付与、tc実行、実RTT測定は未実行です。**

この構成を起動するには、対象コンテナのnetwork namespaceを共有するsidecarへの`NET_ADMIN`追加について、具体的なユーザー承認が必要です。`NETEM_APPROVED=yes`は承認取得後にだけ指定します。値を設定しても承認そのものにはなりません。

## 権限と影響

- 対象は3 Master、6 TServer、3 Goアプリ、追加で3 Javaアプリの計12／15 namespaceです。各sidecarは`cap_drop: ALL`と`cap_add: [NET_ADMIN]`だけです。
- `NET_ADMIN`は共有先コンテナのネットワーク設定を変更できる権限です。本コードは対象IPv4/interfaceを確認し、専用handleのqdiscだけを操作しますが、capability自体はtc専用に限定されていません。
- `privileged`、host network、Docker socket mount、`NET_RAW`、ホストのnetwork namespace、追加host route／firewall変更は使用しません。root filesystemはread-only、状態保存は小さな`/run` tmpfsです。
- 初回適用は専用bridgeへの接続変更を伴うため、DB・アプリ・Webコンテナが再作成され、一時的に通信が切れます。名前付きDB volumeは保持します。同じMac上の実験用であり、本番環境には適用しません。
- RTTを増やすと、Raft選出、DDL、healthcheck、API応答が遅延／timeoutする可能性があります。初期値は**仮定の追加RTT 100ms**であり、実地域間RTTの測定値ではありません。

## 迂回経路を作らない構成

`compose.netem.yaml`は**base＋geo＋javaの全Compose定義と組み合わせるoverride**です。Goだけで使うときも4ファイルを読み、Java profileを無効のままにします。helperがこの順序を固定します。`compose.yaml + compose.netem.yaml`の2枚だけでは、Java serviceの元定義がなくconfigが失敗します。

ネットワークは`netem-lab`という単一bridge、IPv4 subnet `172.29.0.0/16`です。全serviceをこのnetworkへ明示接続し、従来のdefault networkとの二重接続を残しません。IPv6は無効です。

| 地域 | 分類CIDR | Master | TServer 1 / 2 | Go / Java | Web Go / Java |
|---|---|---|---|---|---|
| Asia | 172.29.1.0/24 | .1.10 | .1.11 / .1.12 | .1.20 / .1.21 | .1.30 / .1.31 |
| Europe | 172.29.2.0/24 | .2.10 | .2.11 / .2.12 | .2.20 / .2.21 | .2.30 / .2.31 |
| America | 172.29.3.0/24 | .3.10 | .3.11 / .3.12 | .3.20 / .3.21 | .3.30 / .3.31 |

表の省略アドレスはすべて`172.29` prefixです。初期化・検証jobは管理用`172.29.0.0/24`から動的割当し、地域遅延には含めません。Web→同地域アプリ、localhost公開ポート、DNS、同地域通信にも追加遅延を入れません。IPAM範囲が他のDocker network／VPNと衝突する場合は起動せず、prefix設計を変更して再生成・静的確認してください。

各endpointと同じnetwork namespaceで`tc`を設定するので、Java smart JDBCがdiscoveryで別TServerのIPを取得しても、宛先が別地域CIDRなら同じegress filterを通ります。YSQL 5433、Master RPC 7100、TServer RPC／replication 9100に限定せず、別地域宛てIPv4全体が対象です。アプリ側の接続文字列だけに遅延を入れるproxy方式ではありません。

root `prio` qdiscのpriomapは全優先度を無遅延bandへ送り、別地域の2 CIDRを明示filterした通信だけを`netem` childへ送ります。片道追加遅延は指定RTTの半分です。往路source egress 50ms＋復路destination egress 50msで追加RTT約100ms。ingress側には追加せず、二重加算を避けます。loss／rate／jitterは設定しません。queue上限を超える負荷ではdropが起き得るため、性能試験時はqdisc統計も確認してください。

## 承認後の操作例

以下は手順の説明であり、まだ実行されていません。repo rootから操作します。

```sh
# まず静的確認。capabilityもコンテナも作成しない。
python3 docker/local/netem/check_static.py
python3 docker/local/netem/control.py config --java

# 具体的なNET_ADMIN権限と再作成の承認後だけ設定。
export NETEM_APPROVED=yes

# Go+Java+DB全namespaceへ追加RTT100ms。
python3 docker/local/netem/control.py on --java --rtt-ms 100
python3 docker/local/netem/control.py status --java

# qdisc解除後、sidecarを通常停止。DB/appは停止しない。
python3 docker/local/netem/control.py off --java
```

Goだけなら`--java`を省略します。ただしJavaアプリがすでに稼働している場合、helperは`--java`なしを拒否します。shaping漏れを避けるためです。`netem` profileはDBとGoの12 sidecar、`netem-java` profileはJavaの3 sidecarだけです。`--profile java`単独ではどちらのsidecarも起動しません。

`on`は専用qdiscを入れ直すため、繰り返しても遅延を積み重ねません。他の既存root qdiscを検出したら上書きせず失敗します。endpointを再作成したら`on`を再実行してください。sidecarが古いnamespaceを共有したままでは、設定が現行endpointへ適用されない場合があります。

## OFFと異常終了

通常の`docker compose stop`が送るSIGTERM／Ctrl-CはPythonのfinallyで専用qdiscを削除してから終了します。`off`も全endpointの専用qdiscを削除します。専用handle以外は削除しません。

**SIGKILL、Docker VM停止、host crashではcleanup handlerを実行することは不可能です。** そのため、sidecarだけを強制終了した後の「停止済み」をOFFの証拠にしません。`control.py off --java`はsidecar停止中でも対象endpointが生きていれば、同じnamespaceの一時cleanup sidecarで解除します。対象endpointも停止してnamespaceが消滅した場合、そのqdiscも消滅します。NET_ADMIN承認が必要な操作である点は同じです。

`off`は専用bridgeを維持します。元のnetworkへ戻すには、まず`off`で解除してから、netem overrideを外した通常Compose設定で再作成します。この再作成も通信断を伴います。`down -v`は不要です。

## TCP接続時間のON／OFF差分測定

`tcp_probe.py`はsidecarから同じnamespaceを使い、各地域のTServer 1へTCP接続します。Master／TServer／Go／Javaの全対象namespaceで測定し、DNS名が想定地域CIDRへ解決されることも確認します。ICMP／raw socketは使いません。接続時間はTCP handshakeのRTTに近い指標であり、SQL処理時間／帯域／アプリ全体のlatencyを表しません。

```sh
# 一度onでsidecarを作成した後、qdiscだけ外してbaselineを測る。
python3 docker/local/netem/control.py off --java --keep-probes
python3 docker/local/netem/control.py measure --java --samples 5 --out /tmp/netem-off.json

python3 docker/local/netem/control.py on --java --rtt-ms 100
python3 docker/local/netem/control.py measure --java --samples 5 --out /tmp/netem-on.json
python3 docker/local/netem/compare.py /tmp/netem-off.json /tmp/netem-on.json --rtt-ms 100

# 最後に完全解除。
python3 docker/local/netem/control.py off --java
```

期待値は同地域の増分約0ms、別地域の増分約100msです。比較toolはCPU負荷・タイマ粒度を考慮した広い許容幅を使います。実測未実行のため、動作済み／100msを再現済みとは扱いません。負荷時のtimeoutやTCP再送は別に調べる必要があります。

## 静的検証結果

- Python構文、純粋command-plan test、Compose configを確認。
- 15 sidecarすべて`NET_ADMIN`のみ、全endpointが単一専用network、同地域CIDRのfilterなし、片道RTT/2、ingressなしを検査。
- Java-only profileではNET_ADMINを持つserviceが0、全公開ポートは127.0.0.1のまま。
- `docker build/up/run/exec`、`ip/tc`、実ネットワーク変更はこの作成作業では未実行。

参考: [Compose service namespace/capabilities](https://docs.docker.com/reference/compose-file/services/)、[netem](https://man7.org/linux/man-pages/man8/tc-netem.8.html)、[tc](https://man7.org/linux/man-pages/man8/tc.8.html)。
