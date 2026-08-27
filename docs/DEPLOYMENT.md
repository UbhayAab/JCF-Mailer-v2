# Production topology and runbook

How the live system at **https://mailer.jarurat.care** is put together, and what to do
to it.

This repository is public. Nothing here is a credential. Host addresses, SSH keys,
admin passwords and the contents of `/etc/jcfmailer.env` are held outside the repo and
are handed over separately. Where a value is deliberately withheld it appears as
`<PLACEHOLDER>`.

---

## 1. The box

A single **AWS Graviton `t4g.small`** EC2 instance in `ap-south-1`: 2 vCPU, 1.8 GB RAM,
arm64, Ubuntu 24.04, with an Elastic IP. Everything runs on it: Postgres, the Spring
application, the Stalwart mail server and nginx.

It is small on purpose, and small enough that the constraint shows up in the design:
the application is built on a developer machine rather than on the server, because
compiling on the box needed a swapfile.

---

## 2. Port map

Three systems share one host and one domain. Confusing them is the most common
mistake made here.

| Port | Owner | Exposed | Purpose |
|---|---|---|---|
| 80 | nginx | public | HTTP, redirects to 443 |
| 443 | nginx | public | HTTPS, Let's Encrypt via certbot, reverse proxy to the app |
| 8081 | Spring app (`jcfmailer`) | **loopback only** | Campaign Studio. Never reached directly from the internet. |
| 25 | Stalwart | public | Inbound SMTP, MX for `jarurat.care` |
| 465 | Stalwart | public | SMTP submission, implicit TLS |
| 993 | Stalwart | public | IMAP over TLS |
| 995 | Stalwart | public | POP3 over TLS |
| 4190 | Stalwart | public | ManageSieve |
| 8443 | Stalwart | **loopback only** | Stalwart HTTPS: web admin, JMAP, admin API |
| 5432 | PostgreSQL | **loopback only** | Database `jarurat_mailer` |

```
             internet
                |
        80/443  |  25 465 993 995 4190
                v          v
            +-------+   +----------+
            | nginx |   | Stalwart |
            +-------+   +----------+
                |          ^   ^
   proxy_pass   |          |   | 8443, loopback only
        :8081   v          |   | (JMAP + admin API)
          +--------------------+
          |  Spring app        |
          |  systemd: jcfmailer|
          +--------------------+
                |          \
           5432 |           \  HTTPS
                v            v
          +----------+   +-----------+
          | Postgres |   | Amazon SES|
          +----------+   +-----------+
```

Notes that matter:

- **The app listens on 8081 in production, not 8080.** `server.port` defaults to `8080`
  in `application.properties` for local development; the production environment file
  overrides it. nginx proxies to `127.0.0.1:8081`.
- **nginx terminates TLS**, so the app runs with
  `server.forward-headers-strategy=framework` and builds absolute tracking and
  unsubscribe URLs from the forwarded headers rather than from its own socket.
- **Stalwart's 8443 is not exposed to the internet.** Reach the web admin over an SSH
  tunnel: `ssh -i <key> -L 8443:127.0.0.1:8443 <user>@<host>`, then open
  `https://127.0.0.1:8443` and accept the certificate warning. The certificate is
  issued for the public name, not for localhost.
- **The app talks to Stalwart over loopback only.** The self-signed certificate on that
  path carries exactly one SAN, `DNS:localhost`, and the client only relaxes trust for
  a loopback peer. Pointing `MAIL_JMAP_URL` at a non-loopback address will fail.
- **EC2 blocks outbound port 25.** A direct connection from the box to
  `gmail-smtp-in.l.google.com:25` is refused. Stalwart therefore relays outbound
  through SES on port 587 via an MtaRoute. If mail ever queues with connection
  timeouts, the route is the cause, not the firewall.

---

## 3. Where things live

| Thing | Location |
|---|---|
| Application jar | `/home/ubuntu/mailer-0.0.1-SNAPSHOT.jar` |
| systemd unit | `jcfmailer` (`systemctl status jcfmailer`) |
| Application logs | `journalctl -u jcfmailer` |
| Application secrets | `/etc/jcfmailer.env`, root, mode 0600, loaded by the unit as `EnvironmentFile`. See `.env.example` at the repo root for the full list of keys. |
| Deploy script | `/home/ubuntu/deploy.sh` (the copy of `deploy.sh` from this repo) |
| Previous jars | `/home/ubuntu/mailer-prev-<timestamp>.jar`, kept by the deploy script for rollback |
| Database | Postgres, database `jarurat_mailer`, reached with `sudo -u postgres psql -d jarurat_mailer` |
| Database backups | `/home/ubuntu/backup_pre_*_<timestamp>.sql.gz`, written by the deploy script |
| Stalwart config | `/etc/stalwart/config.json` |
| Stalwart data | `/var/lib/stalwart` (RocksDB) |
| Stalwart logs | `/var/log/stalwart/stalwart.YYYY-MM-DD` (date-suffixed, not `.log`) |
| nginx config | `/etc/nginx/sites-available/`, certificates managed by certbot |

The application reads the Stalwart log directory to recover the receiving server's
verbatim SMTP reply for each delivery attempt. The app user needs read access to it,
granted as a filesystem ACL rather than a group. Set `STALWART_LOG_ENABLED=false` on
any box that is not running Stalwart.

---

## 4. Deploying a change

Build locally against JDK 21, ship the jar, run the script on the server.

```bash
export JAVA_HOME=/path/to/jdk-21
./mvnw -B package -DskipTests
scp -i <key> target/mailer-0.0.1-SNAPSHOT.jar <user>@<host>:/home/ubuntu/mailer-new.jar
ssh -i <key> <user>@<host> "bash /home/ubuntu/deploy.sh"
```

`deploy.sh` then, in order:

1. Checks the new jar actually arrived.
2. Dumps and gzips the Postgres database.
3. Generates `OTP_PEPPER` into `/etc/jcfmailer.env` if, and only if, it is absent, so
   running the script twice never rotates a working pepper.
4. Stops `jcfmailer`, keeps the current jar as `mailer-prev-<timestamp>.jar`, swaps in
   the new one, starts the unit.
5. Polls `http://127.0.0.1:8081/login` for up to 30 seconds and reports whether the app
   answered.
6. Prints the tail of the journal.

Whole cycle is roughly 40 seconds.

### Rollback

```bash
sudo systemctl stop jcfmailer
cp /home/ubuntu/mailer-prev-<timestamp>.jar /home/ubuntu/mailer-0.0.1-SNAPSHOT.jar
sudo systemctl start jcfmailer
```

Schema changes are additive: Hibernate runs with `ddl-auto=update` and creates new
tables and columns but never drops them. A rollback of the jar therefore leaves the
extra tables in place, harmlessly. There is no down migration.

### PowerShell mangles quotes passed to ssh

Anything with nested quotes, `$(...)` or parentheses fails with `unexpected EOF`. Write
the remote work into a `.sh` file, `scp` it, then `ssh ... "bash /path.sh"`. This will
waste an hour if you do not know it.

### The SSH key's Windows ACL

`Permissions for <key> are too open` means `BUILTIN\Users` inherited access. Fix in
this order, so the file is never left unusable:

```powershell
icacls <key> /grant:r "$($env:USERNAME):(F)"
icacls <key> /inheritance:r
```

---

## 5. DNS

Managed in **Squarespace** (Domains > jarurat.care > DNS), even though the nameservers
answer from `googledomains.com`: Squarespace bought Google Domains and still runs that
infrastructure. There is no GCP project to go looking for.

**The Host field takes the label only.** Type `_dmarc`, not `_dmarc.jarurat.care`.
Getting this wrong once created a record living at `_dmarc.jarurat.care.jarurat.care`
that silently did nothing.

The live record set is listed in [`MAIL-PLATFORM.md`](MAIL-PLATFORM.md#6-dns-and-the-one-that-is-easy-to-get-wrong).
In outline: `mailer` is an A record to the Elastic IP, the apex MX points at
`mailer.jarurat.care`, SPF authorises both the box and `amazonses.com`, DMARC is at
`p=none`, and SES DKIM is three CNAMEs under `_domainkey`.

Read the DKIM tokens from the source rather than from any document:

```bash
aws sesv2 get-email-identity --email-identity jarurat.care --region ap-south-1
```

### Known gap: reverse DNS

The PTR for the Elastic IP is still the AWS default
`ec2-<dashed-ip>.ap-south-1.compute.amazonaws.com`, not `mailer.jarurat.care`. Mail
sent **directly from the box** will be junked or rejected by the large providers until
a reverse DNS request is raised with AWS for that Elastic IP. Mail sent through SES is
unaffected, and that is currently everything Campaign Studio sends.

---

## 6. What is not in this repository

A developer can clone, build and run the application from this repository alone. To
operate the production system, these are handed over separately and out of band:

| Item | Notes |
|---|---|
| SSH private key and host address | For the EC2 box |
| `/etc/jcfmailer.env` contents | The real values for every key in `.env.example` |
| AWS account access | SES console, the Elastic IP, the instance role |
| Stalwart admin credentials | For the web admin on 8443 |
| Squarespace account | For DNS changes |
| `migrate-zoho.creds` | Only needed if the Zoho mailbox migration is re-run |
