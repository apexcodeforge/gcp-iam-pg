# aws-iam-pg

Minimal Java app that connects to **Amazon RDS for PostgreSQL** using **AWS
IAM database authentication** — no DB password, no static credentials in the
JAR. Connection management is HikariCP; auth is the AWS Advanced JDBC
Wrapper's IAM plugin.

This is the AWS counterpart to the GCP project in the parent directory.

## Prerequisites

### 1. RDS instance

- Postgres engine (RDS or Aurora-Postgres).
- IAM database authentication enabled on the instance:
  - RDS console → instance → Modify → **Database authentication** →
    "Password and IAM database authentication" (or IAM-only).
  - Or CLI: `aws rds modify-db-instance --db-instance-identifier <id> --enable-iam-database-authentication --apply-immediately`.
- TLS reachable on port 5432 from wherever you're running the JAR (security
  group must allow ingress).

### 2. Postgres role with `rds_iam`

Connect to the DB once with the master password and create the IAM-auth user:

```sql
CREATE USER app_user;
GRANT rds_iam TO app_user;

GRANT CONNECT ON DATABASE appdb TO app_user;
GRANT USAGE   ON SCHEMA public  TO app_user;
-- plus whatever table/sequence grants the app needs
```

The `rds_iam` grant is what makes Postgres accept SigV4 tokens as the password
for that role. Without it you get a normal "password authentication failed."

### 3. IAM policy on the caller

Whatever principal runs the JAR (EC2 instance role, ECS task role, Lambda
role, or your local `~/.aws/credentials` user) needs:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "rds-db:connect",
    "Resource": "arn:aws:rds-db:<region>:<account-id>:dbuser:<db-resource-id>/app_user"
  }]
}
```

`<db-resource-id>` is the immutable `DbiResourceId` shown in the RDS console
(starts with `db-`), **not** the human-readable instance name. Find it with:

```bash
aws rds describe-db-instances --db-instance-identifier <id> \
  --query 'DBInstances[0].DbiResourceId' --output text
```

### 4. Credentials available to the JAR

The wrapper uses the standard AWS credentials provider chain:

1. Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, optional `AWS_SESSION_TOKEN`)
2. Java system properties
3. `~/.aws/credentials` profile (`AWS_PROFILE` selects which one)
4. EC2 / ECS / Lambda instance metadata

For local testing: `aws configure` or `aws sso login` is enough. On AWS
compute: attach the role and don't ship keys.

## Configure

Edit `env.bat` (Windows) with your values:

```bat
SET RDS_HOST=mydb.xxxxxxxxxxxx.us-east-1.rds.amazonaws.com
SET RDS_PORT=5432
SET DB_NAME=appdb
SET DB_IAM_USER=app_user
SET AWS_REGION=us-east-1
```

Or the equivalent `export` lines in bash.

Optional knobs:

- `AWS_PROFILE` — selects a named profile from `~/.aws/credentials` /
  `~/.aws/config`. Honored by the default credentials provider chain; the app
  also propagates it to the `aws.profile` system property at startup so the
  wrapper picks it up regardless of how the JVM was launched. Leave unset to
  fall through to env-var keys, instance metadata, etc.
- `DB_POOL_MAX` (default `10`)
- `DB_POOL_MIN_IDLE` (default `2`)

## Build

From the `aws/` directory:

```
mvn -q package
```

Produces `target/aws-iam-pg-1.0.0.jar` (shaded, runnable).

## Run

```bat
call env.bat
java -jar target\aws-iam-pg-1.0.0.jar
```

The app runs a sample query every 5 minutes for 2 hours and logs the backend
PID, connection-acquire time, and live Hikari pool counters. Watch for the
`pid` rotating every ~12 minutes — that's Hikari recycling connections under
the IAM token TTL.

## Token + pool interaction

RDS IAM auth tokens live **15 minutes** (compared to GCP's 1 hour). The
wrapper's IAM plugin signs a fresh token on each new physical connection;
once the TCP session is established, Postgres no longer cares about the
token, so a connection that's already open keeps working past the 15-minute
mark. The risk is only when **opening** a connection.

`ConnectionPool` sets `maxLifetime=12m` — comfortably under 15 — so Hikari
always retires a connection well before the next replacement could possibly
race a token-expiry edge case. If you bump that timer, keep it under 15
minutes.

## TLS

`sslmode=require` is set in `ConnectionPool.java`. RDS rejects IAM auth on
plaintext, so this is the floor. For production, switch to `verify-full`
and supply `sslrootcert` pointing at the [RDS global CA bundle](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)
to also authenticate the server.

## Troubleshooting

- **`PAM authentication failed` / `password authentication failed`**: the
  `rds_iam` grant is missing on the DB role, or the IAM policy doesn't allow
  `rds-db:connect` on that user, or the principal in the credential chain
  isn't who you think it is. Check `aws sts get-caller-identity`.
- **`SSL connection is required`**: `sslmode` was overridden or the JDBC URL
  was edited. The wrapper hands TLS off to the Postgres driver — keep
  `sslmode=require` (or stricter) in the data-source properties.
- **Connection hangs**: security group not allowing 5432 from your source IP,
  or the RDS instance is in a private subnet without a route.
- **Token region mismatch**: if `iamRegion` doesn't match the RDS endpoint's
  region, signing fails. The wrapper auto-detects from the hostname; setting
  it explicitly via `AWS_REGION` is just insurance.
