#!/usr/bin/env bash
#
# Deploys a locally built mailer jar. Run ON the server:
#   bash /home/ubuntu/deploy.sh
#
# Expects /home/ubuntu/mailer-new.jar to already be uploaded.
#
# The old "build on the server" step is gone: the jar is now built on the Windows
# box against a local JDK 21, so this box never has to run Maven again. That
# matters because it only has 908MB of RAM and needed a swapfile to compile.

set -euo pipefail

JAR=/home/ubuntu/mailer-0.0.1-SNAPSHOT.jar
NEW=/home/ubuntu/mailer-new.jar
STAMP=$(date +%F-%H%M)

echo "=== 1. checks ==="
if [ ! -f "$NEW" ]; then
    echo "FAILED: $NEW is not here. Upload the jar first." >&2
    exit 1
fi
echo "new jar: $(du -h "$NEW" | cut -f1)"

echo
echo "=== 2. database backup ==="
# Hibernate creates eight new tables on first boot with the new jar. That is
# additive and should not touch existing rows, but a dump costs seconds and
# removes the need to trust that sentence.
BACKUP="/home/ubuntu/backup_pre_journey_${STAMP}.sql.gz"
sudo -u postgres pg_dump jarurat_mailer | gzip > "$BACKUP"
echo "saved $BACKUP ($(du -h "$BACKUP" | cut -f1))"

echo
echo "=== 3. OTP pepper ==="
# Without this the OTP service falls back to a per-process key and every
# outstanding code dies on restart. Only appended when genuinely absent, so
# running this script twice never rotates a working pepper.
if sudo grep -q '^OTP_PEPPER=' /etc/jcfmailer.env 2>/dev/null; then
    echo "OTP_PEPPER already set, left alone"
else
    printf 'OTP_PEPPER=%s\n' "$(openssl rand -base64 32)" | sudo tee -a /etc/jcfmailer.env > /dev/null
    echo "OTP_PEPPER generated and appended to /etc/jcfmailer.env"
fi

echo
echo "=== 4. swap the jar ==="
sudo systemctl stop jcfmailer
if [ -f "$JAR" ]; then
    cp "$JAR" "/home/ubuntu/mailer-prev-${STAMP}.jar"
    echo "previous jar kept as mailer-prev-${STAMP}.jar"
fi
mv "$NEW" "$JAR"
sudo systemctl start jcfmailer

echo
echo "=== 5. did it come up? ==="
# Hibernate has eight tables to create on this first boot, so give it longer
# than a normal restart before deciding it has failed.
for i in $(seq 1 30); do
    if curl -fsS -o /dev/null http://127.0.0.1:8081/login 2>/dev/null; then
        echo "app answering after ${i}s"
        break
    fi
    sleep 1
done

echo "systemd: $(systemctl is-active jcfmailer)"
printf 'journey.js: '
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/js/journey.js

echo
echo "=== 6. new tables ==="
sudo -u postgres psql -d jarurat_mailer -tAc \
    "select tablename from pg_tables where schemaname='public' and (tablename like 'journey%' or tablename like 'otp%') order by tablename" \
    | sed 's/^/  /'

echo
echo "=== 7. recent log ==="
sudo journalctl -u jcfmailer -n 15 --no-pager | tail -15

echo
echo "Done. If journey.js printed 200, hard-refresh the console and Journeys"
echo "will be in the sidebar under Marketing."
echo "Rollback if needed:"
echo "  sudo systemctl stop jcfmailer"
echo "  cp /home/ubuntu/mailer-prev-${STAMP}.jar $JAR"
echo "  sudo systemctl start jcfmailer"
