#!/usr/bin/env python3
"""Zoho -> Stalwart mailbox migration. No dependencies beyond the standard library.

imapsync would be the usual tool, but it is no longer packaged for Ubuntu 24.04 and
pulling it in means ten Perl packages on a 1.8GB box. Python's imaplib is already
here and does the job.

Design notes, because the failure modes here are expensive:

  Resumable and idempotent. Before copying a folder it reads the Message-IDs already
  on the destination and skips those. Interrupt it and run it again; it picks up.

  Memory safe. Messages are fetched and appended one at a time. A 7GB mailbox is
  never held in memory, which matters because this box has 1.8GB and is also running
  Postgres, Stalwart and the app.

  Non destructive. Nothing is ever deleted or altered on Zoho. Worst case is a
  partial copy, which the next run completes.

  Preserves what matters. Flags (read, flagged, answered) and the original received
  date carry over, so the destination mailbox sorts and reads like the original.

Usage, run ON the mail server:

    python3 migrate-zoho.py --check              log in to both sides, copy nothing
    python3 migrate-zoho.py --go                 migrate every mailbox in the creds file
    python3 migrate-zoho.py --go hr@jarurat.care just one
    python3 migrate-zoho.py --go --include-junk  also bring Spam and Trash

Credentials: migrate-zoho.creds beside this script, mode 600, TAB separated:

    zoho_address <TAB> zoho_APP_password <TAB> local_login <TAB> local_password

The Zoho side needs an APP-SPECIFIC password (Zoho Mail > Settings > Security >
App Passwords). With two-factor on, the normal account password fails IMAP.
"""
import email
import email.utils
import imaplib
import os
import re
import ssl
import sys
import time

ZOHO_HOST, ZOHO_PORT = "imap.zoho.in", 993          # .in: this is a Zoho India tenant
LOCAL_HOST, LOCAL_PORT = "127.0.0.1", 993

HERE = os.path.dirname(os.path.abspath(__file__))
CREDS = os.path.join(HERE, "migrate-zoho.creds")

# Folders that are usually noise rather than history. Overridable with --include-junk.
JUNK = {"spam", "junk", "trash", "deleted items"}

# imaplib's default is 10000 bytes, which truncates any real message.
imaplib._MAXLINE = 10_000_000


def connect_source(addr, password):
    m = imaplib.IMAP4_SSL(ZOHO_HOST, ZOHO_PORT, ssl_context=ssl.create_default_context())
    m.login(addr, password)
    return m


def connect_dest(login, password):
    # The local certificate is issued for the public hostname, not for 127.0.0.1,
    # so verification is disabled for this loopback connection only.
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    m = imaplib.IMAP4_SSL(LOCAL_HOST, LOCAL_PORT, ssl_context=ctx)
    m.login(login, password)
    return m


def list_folders(conn):
    """Folder names with their delimiter, decoded from IMAP's modified UTF-7."""
    typ, data = conn.list()
    if typ != "OK":
        return []
    out = []
    for raw in data:
        if not raw:
            continue
        line = raw.decode(errors="replace") if isinstance(raw, bytes) else raw
        m = re.match(r'\((?P<flags>[^)]*)\)\s+"(?P<delim>[^"]*)"\s+(?P<name>.*)', line)
        if not m:
            continue
        name = m.group("name").strip().strip('"')
        flags = m.group("flags")
        if "\\Noselect" in flags:
            continue
        out.append((name, m.group("delim")))
    return out


def message_ids_in(conn, folder):
    """Message-IDs already present, so a re-run does not duplicate them."""
    try:
        typ, _ = conn.select(f'"{folder}"', readonly=True)
        if typ != "OK":
            return set()
        typ, data = conn.uid("SEARCH", None, "ALL")
        if typ != "OK" or not data or not data[0]:
            return set()
        uids = data[0].split()
        seen = set()
        # Headers only, in blocks, so this stays cheap even on a big folder.
        for i in range(0, len(uids), 500):
            chunk = b",".join(uids[i:i + 500]).decode()
            typ, resp = conn.uid("FETCH", chunk, "(BODY.PEEK[HEADER.FIELDS (MESSAGE-ID)])")
            if typ != "OK":
                continue
            for part in resp:
                if isinstance(part, tuple) and len(part) > 1:
                    text = part[1].decode(errors="replace")
                    found = re.search(r"Message-ID:\s*(<[^>]+>)", text, re.I)
                    if found:
                        seen.add(found.group(1).strip())
        return seen
    except Exception:
        return set()


def ensure_folder(dest, folder):
    try:
        dest.create(f'"{folder}"')
    except Exception:
        pass
    dest.subscribe(f'"{folder}"')


def copy_folder(src, dest, folder, include_junk):
    if not include_junk and folder.split("/")[-1].lower() in JUNK:
        print(f"    {folder:<28} skipped (junk folder)")
        return 0, 0

    typ, data = src.select(f'"{folder}"', readonly=True)
    if typ != "OK":
        print(f"    {folder:<28} cannot open on Zoho, skipped")
        return 0, 0

    typ, data = src.uid("SEARCH", None, "ALL")
    uids = data[0].split() if typ == "OK" and data and data[0] else []
    if not uids:
        print(f"    {folder:<28} empty")
        return 0, 0

    ensure_folder(dest, folder)
    already = message_ids_in(dest, folder)

    copied = skipped = failed = 0
    started = time.time()

    for n, uid in enumerate(uids, 1):
        try:
            typ, resp = src.uid("FETCH", uid.decode(),
                                "(FLAGS INTERNALDATE BODY.PEEK[])")
            if typ != "OK" or not resp or not isinstance(resp[0], tuple):
                failed += 1
                continue

            meta = resp[0][0].decode(errors="replace")
            raw = resp[0][1]

            msg = email.message_from_bytes(raw)
            mid = (msg.get("Message-ID") or "").strip()
            if mid and mid in already:
                skipped += 1
                continue

            flags = ""
            fm = re.search(r"FLAGS \(([^)]*)\)", meta)
            if fm:
                # \Recent cannot be set by APPEND and makes the server reject it.
                flags = " ".join(f for f in fm.group(1).split() if f.lower() != "\\recent")

            when = None
            dm = re.search(r'INTERNALDATE "([^"]+)"', meta)
            if dm:
                try:
                    when = imaplib.Time2Internaldate(
                        email.utils.mktime_tz(email.utils.parsedate_tz(dm.group(1))))
                except Exception:
                    when = None

            dest.append(f'"{folder}"', flags or None, when, raw)
            copied += 1
            if mid:
                already.add(mid)

        except Exception as e:
            failed += 1
            if failed <= 3:
                print(f"      uid {uid.decode()}: {type(e).__name__} {e}")

        if n % 200 == 0:
            rate = n / max(1, time.time() - started)
            print(f"      {folder}: {n}/{len(uids)}  copied={copied} "
                  f"skipped={skipped} failed={failed}  {rate:.0f}/s")

    print(f"    {folder:<28} {len(uids):>6} msgs  copied={copied} "
          f"skipped={skipped} failed={failed}")
    return copied, failed


def do_mailbox(zaddr, zpass, login, lpass, check_only, include_junk):
    print(f"\n=== {zaddr}  ->  {login} ===")
    try:
        src = connect_source(zaddr, zpass)
    except Exception as e:
        print(f"  Zoho login FAILED: {type(e).__name__} {e}")
        print("  If this is an authentication error, the password must be an "
              "APP-SPECIFIC password, not the account password.")
        return
    try:
        dest = connect_dest(login, lpass)
    except Exception as e:
        print(f"  Stalwart login FAILED: {type(e).__name__} {e}")
        print("  Does the mailbox exist yet? Create it in the admin UI first.")
        src.logout()
        return

    folders = list_folders(src)
    print(f"  Zoho folders: {len(folders)}")

    if check_only:
        for name, _ in folders:
            typ, data = src.select(f'"{name}"', readonly=True)
            count = data[0].decode() if typ == "OK" and data else "?"
            print(f"    {name:<30} {count} messages")
        src.logout()
        dest.logout()
        return

    total_copied = total_failed = 0
    for name, _ in folders:
        c, f = copy_folder(src, dest, name, include_junk)
        total_copied += c
        total_failed += f

    print(f"  TOTAL copied={total_copied} failed={total_failed}")
    if total_failed:
        print("  Re-run to retry the failures; anything already copied is skipped.")

    src.logout()
    dest.logout()


def main():
    args = sys.argv[1:]
    if not args or args[0] not in ("--check", "--go"):
        print(__doc__)
        return 1

    check_only = args[0] == "--check"
    include_junk = "--include-junk" in args
    only = next((a for a in args[1:] if "@" in a), None)

    if not os.path.exists(CREDS):
        print(f"No credentials file at {CREDS}\n")
        print("Create it TAB separated, one line per mailbox, then chmod 600:\n")
        print("  zoho_address<TAB>zoho_app_password<TAB>local_login<TAB>local_password\n")
        print("Mailboxes to migrate, largest first:")
        print("  priyanka.joshi@jarurat.care   7.13 GB")
        print("  partnership@jarurat.care      3.95 GB")
        print("  hr@jarurat.care               3.34 GB")
        print("  care@jarurat.care             114 KB")
        print("\npriyansha@jarurat.care is deliberately excluded.")
        return 1

    os.chmod(CREDS, 0o600)

    for line in open(CREDS, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 4:
            print(f"  malformed line, need 4 TAB separated fields: {line[:40]}...")
            continue
        zaddr, zpass, login, lpass = parts[0], parts[1], parts[2], parts[3]
        if only and zaddr != only:
            continue
        do_mailbox(zaddr, zpass, login, lpass, check_only, include_junk)

    return 0


if __name__ == "__main__":
    sys.exit(main())
