# gcp-iam-pg

Minimal Java app that connects to Cloud SQL for PostgreSQL using **GCP IAM
authentication** — no DB password, no service account key file. Ships with two
connection strategies so they can be compared directly:

- `ConnectionPool` — HikariCP pool (recommended for app servers)
- `ConnectionPool2` — plain `DriverManager`, no pooling (reference / scripts)

## Prerequisites

1. **Cloud SQL Postgres instance** with IAM auth enabled
   (`cloudsql.iam_authentication = on`).
2. **Service account** (e.g. `app-sa@PROJECT.iam.gserviceaccount.com`) with:
   - `roles/cloudsql.client`
   - `roles/cloudsql.instanceUser`
3. **Cloud SQL IAM DB user** bound to that SA:
   ```bash
   gcloud sql users create app-sa@PROJECT.iam.gserviceaccount.com \
     --instance=INSTANCE --type=cloud_iam_service_account
   ```
4. **Postgres privileges** granted to the IAM user (inside the target DB):
   ```sql
   GRANT CONNECT ON DATABASE appdb TO "app-sa@PROJECT.iam";
   GRANT USAGE  ON SCHEMA public   TO "app-sa@PROJECT.iam";
   ```
5. **GCE VM** with the SA attached and scope
   `https://www.googleapis.com/auth/cloud-platform`.

> The Postgres username is the SA email with `.gserviceaccount.com` stripped
> (63-char limit). The IAM identity keeps the full email.

## Run

```bash
export CLOUD_SQL_INSTANCE="PROJECT:REGION:INSTANCE"
export DB_NAME="appdb"
export DB_IAM_USER="app-sa@PROJECT.iam"

mvn -q package
java -jar target/gcp-iam-pg-1.0.0.jar
```

The app runs a sample query every 5 minutes for 2 hours. Each line reports the
Postgres backend PID, connection acquire time, and live Hikari pool counters —
enough to watch connections being reused and recycled.

## How IAM token refresh works under Hikari

The interaction between Hikari's pool mechanics and the Cloud SQL socket
factory's token handling is where this setup either works or breaks.

### What "token" means here

The Cloud SQL socket factory (`postgres-socket-factory`) does two things per
*new* physical connection:

1. Calls GCP IAM via ADC → gets an **OAuth2 access token** (~1h lifetime).
2. Feeds that token to the Postgres driver **as the password** during the
   SCRAM/PAM auth handshake.

Once Postgres accepts the handshake and the TCP session is open, **Postgres no
longer cares about the token**. The session stays valid until the TCP socket
closes — even if the original token expires an hour later while queries are
still running on it.

So the token only matters when a **new** connection is being opened.

### The two timelines

```
Token lifetime:       |------- ~60 min -------|
Hikari maxLifetime:   |---- 30 min ----|
```

`ConnectionPool` sets `maxLifetime=30m` — deliberately shorter than the token
TTL.

### Step-by-step over a 2-hour run

**T=0 (pool warmup)**
- Hikari opens `minIdle` connections.
- Socket factory mints token A, uses it to authenticate each.
- Connections go idle, each tagged with open-time = 0.

**T=0 → T=30m (steady state)**
- `getConnection()` hands out an idle connection.
- No token work happens. Same TCP session, same backend PID.
- Queries run, connection returned to the pool, reused next call.

**T=30m (maxLifetime hit)**
- Hikari sees connection age > 30m. It does *not* hand it out again.
- Marks it for retirement, opens a replacement in the background.
- Socket factory mints **token B** for the replacement.
- Caller gets the fresh connection. `acquire` time is higher this once.

**T=30m → T=60m**
- New connection reused. PID differs from the first batch — this is how
  rotation becomes visible in the logs.

**T=60m (token A would have expired)**
- Nothing happens. Connections using token A were already closed at T=30m.
  Token A expiring in the abstract is a non-event.

**T=60m+**
- Same cycle every 30m. Replacements always get a fresh token because minting
  happens at connect time.

### Socket factory token cache

The socket factory also caches the current token and proactively refreshes it
~4 minutes before expiry, so `getConnection()` never blocks on an IAM round
trip. The cache is shared across the JVM — if 10 Hikari replacements fire in
the same minute, they share one token mint, not ten.

### Why `maxLifetime=30m` is the linchpin

If `maxLifetime` were removed (or set to `0` = infinite), a connection opened
at T=0 could still sit in the pool at T=65m. Handing it out and running a
query would still succeed — the Postgres session is valid regardless of token
age. But if Hikari needed to *replace* a crashed connection at the same moment
the cached token had just expired, you could see transient
`PAM authentication failed` errors. `maxLifetime < token TTL` eliminates that
class of bug entirely.

### What a healthy run looks like

- Groups of ~6 iterations sharing a `pid`, then the pid changes, then another
  ~6 iterations share the next pid, repeating.
- `acquire` is sub-millisecond on reuse; occasionally spikes into the
  hundreds of milliseconds when Hikari opens a replacement.
- Zero auth failures over the full 2 hours.
