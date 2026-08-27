package com.jarurat.mailer.mail;

import com.jarurat.mailer.services.SesSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The mailbox operations the webmail needs, expressed in our own types.
 *
 * Almost nothing here fans out onto threads, and that is deliberate rather than
 * lazy: JMAP lets one request carry several method calls and lets a later call
 * feed off an earlier one's result, so listing a folder is Email/query plus
 * Email/get in a single round trip. Batching beats parallelism when the cost is
 * the round trip. Virtual threads are used where there genuinely are independent
 * round trips, which is only the cross-account fan-out at the bottom of this file.
 */
@Service
public class MailService {

    private static final List<String> MAIL_CAPS = List.of(JmapClient.CORE, JmapClient.MAIL);
    private static final List<String> SEND_CAPS = List.of(JmapClient.CORE, JmapClient.MAIL, JmapClient.SUBMISSION);
    private static final List<String> IDENTITY_CAPS = List.of(JmapClient.CORE, JmapClient.SUBMISSION);

    private static final List<String> SUMMARY_PROPS = List.of(
            "id", "threadId", "mailboxIds", "keywords", "size", "receivedAt",
            "subject", "from", "to", "cc", "preview", "hasAttachment");

    private static final List<String> BODY_PROPS = List.of(
            "id", "threadId", "mailboxIds", "keywords", "subject", "from", "to", "cc", "replyTo",
            "sentAt", "receivedAt", "textBody", "htmlBody", "bodyValues", "attachments",
            "messageId", "inReplyTo", "references");

    /** Keywords the UI refers to by bare name. Anything else is a user label and is left alone. */
    private static final Set<String> SYSTEM_KEYWORDS =
            Set.of("seen", "flagged", "answered", "draft", "forwarded");

    /**
     * Every folder on this server reports sortOrder 0 (measured), so ordering by
     * it gives whatever RocksDB felt like. Order by role instead, the way every
     * mail client does, and fall back to name for user-created folders.
     */
    private static final List<String> ROLE_ORDER = List.of("inbox", "drafts", "sent", "archive", "junk", "trash");

    private static final Comparator<MailFolder> FOLDER_ORDER = Comparator
            .comparingInt((MailFolder f) -> {
                int i = f.role() == null ? -1 : ROLE_ORDER.indexOf(f.role().toLowerCase(Locale.ROOT));
                return i < 0 ? ROLE_ORDER.size() : i;
            })
            .thenComparing((MailFolder f) -> f.name() == null ? "" : f.name(), String.CASE_INSENSITIVE_ORDER);

    private final JmapClient client;
    private final SesSender ses;
    private final int maxPageSize;
    private final int maxBodyBytes;

    public MailService(JmapClient client,
                       SesSender ses,
                       @Value("${jarurat.mail.max-page-size:100}") int maxPageSize,
                       @Value("${jarurat.mail.max-body-bytes:1000000}") int maxBodyBytes) {
        this.client = client;
        this.ses = ses;
        this.maxPageSize = Math.max(1, maxPageSize);
        this.maxBodyBytes = Math.max(4096, maxBodyBytes);
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    public List<MailFolder> listFolders(String user) {
        ObjectNode args = client.accountArgs(user);
        args.putNull("ids");
        ArrayNode props = args.putArray("properties");
        for (String p : List.of("id", "name", "role", "parentId", "sortOrder", "totalEmails", "unreadEmails")) {
            props.add(p);
        }

        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Mailbox/get", args, "f0"))),
                "Mailbox/get", "f0");

        List<MailFolder> folders = new ArrayList<>();
        for (JsonNode m : result.path("list")) {
            folders.add(new MailFolder(
                    JmapClient.text(m, "id"),
                    JmapClient.text(m, "name"),
                    JmapClient.text(m, "role"),
                    JmapClient.text(m, "parentId"),
                    m.path("sortOrder").asInt(0),
                    m.path("totalEmails").asInt(0),
                    m.path("unreadEmails").asInt(0)));
        }
        folders.sort(FOLDER_ORDER);
        return List.copyOf(folders);
    }

    /** Null when the account has no folder with that role, which should never happen but does not deserve a crash. */
    public MailFolder folderByRole(String user, String role) {
        for (MailFolder f : listFolders(user)) {
            if (f.isRole(role)) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public MessagePage listMessages(String user, String folderId, int offset, int limit) {
        if (folderId == null || folderId.isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL, "No folder given");
        }
        ObjectNode filter = client.newObject();
        filter.put("inMailbox", folderId);
        return page(user, filter, offset, limit);
    }

    /**
     * Stalwart's "text" filter covers subject, addresses and body at once, which
     * is what a single search box means to a user. A blank query deliberately
     * matches everything rather than erroring, so clearing the box returns to the
     * full mailbox.
     */
    public MessagePage search(String user, String query, int offset, int limit) {
        ObjectNode filter = client.newObject();
        if (query != null && !query.isBlank()) filter.put("text", query.trim());
        return page(user, filter, offset, limit);
    }

    private MessagePage page(String user, ObjectNode filter, int offset, int limit) {
        int from = Math.max(0, offset);
        int size = Math.min(Math.max(1, limit), maxPageSize);

        ObjectNode query = client.accountArgs(user);
        query.set("filter", filter);
        query.putArray("sort").addObject().put("property", "receivedAt").put("isAscending", false);
        query.put("position", from);
        query.put("limit", size);
        query.put("calculateTotal", true);

        // Back reference: Email/get reads the ids Email/query just produced, so the
        // whole page costs one round trip instead of two.
        ObjectNode get = client.accountArgs(user);
        ObjectNode ref = get.putObject("#ids");
        ref.put("resultOf", "q0");
        ref.put("name", "Email/query");
        ref.put("path", "/ids");
        ArrayNode props = get.putArray("properties");
        for (String p : SUMMARY_PROPS) props.add(p);

        JsonNode responses = client.call(user, MAIL_CAPS, client.newArray()
                .add(client.invocation("Email/query", query, "q0"))
                .add(client.invocation("Email/get", get, "g0")));

        JsonNode queried = client.response(responses, "Email/query", "q0");
        JsonNode fetched = client.response(responses, "Email/get", "g0");

        // Email/get makes no promise about ordering, so re-impose the query's order.
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode e : fetched.path("list")) byId.put(JmapClient.text(e, "id"), e);

        List<MessageSummary> rows = new ArrayList<>();
        for (JsonNode id : queried.path("ids")) {
            JsonNode e = byId.get(JmapClient.string(id));
            if (e != null) rows.add(toSummary(e));
        }
        return new MessagePage(List.copyOf(rows), from, size, queried.path("total").asInt(rows.size()));
    }

    public MessageBody getMessage(String user, String emailId) {
        ObjectNode args = client.accountArgs(user);
        args.putArray("ids").add(emailId);
        ArrayNode props = args.putArray("properties");
        for (String p : BODY_PROPS) props.add(p);
        args.put("fetchTextBodyValues", true);
        args.put("fetchHTMLBodyValues", true);
        // A runaway body must not be able to exhaust heap on a t4g.small.
        args.put("maxBodyValueBytes", maxBodyBytes);

        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Email/get", args, "b0"))),
                "Email/get", "b0");

        JsonNode e = result.path("list").path(0);
        if (e.isMissingNode() || e.isNull()) {
            throw new MailException(MailException.Kind.NOT_FOUND,
                    "No message " + emailId + " in " + user + "'s mailbox");
        }

        JsonNode values = e.path("bodyValues");
        JsonNode keywords = e.path("keywords");

        return new MessageBody(
                JmapClient.text(e, "id"),
                JmapClient.text(e, "threadId"),
                JmapClient.text(e, "subject"),
                addresses(e.path("from")), addresses(e.path("to")),
                addresses(e.path("cc")), addresses(e.path("replyTo")),
                instant(e, "sentAt"), instant(e, "receivedAt"),
                bodyOfType(e.path("htmlBody"), values, "text/html"),
                bodyOfType(e.path("textBody"), values, "text/plain"),
                attachments(e.path("attachments")),
                mailboxIds(e.path("mailboxIds")),
                keywords.path("$seen").asBoolean(false),
                keywords.path("$flagged").asBoolean(false),
                keywords.path("$draft").asBoolean(false),
                firstString(e.path("messageId")),
                strings(e.path("inReplyTo")),
                strings(e.path("references")));
    }

    // ------------------------------------------------------------------
    // Changing state
    // ------------------------------------------------------------------

    public void setKeyword(String user, String emailId, String keyword, boolean value) {
        String flag = normaliseKeyword(keyword);
        ObjectNode args = client.accountArgs(user);
        ObjectNode patch = args.putObject("update").putObject(emailId);
        // JMAP clears a keyword by patching it to null. Patching it to false sets a
        // keyword whose value is false, which is not the same thing.
        if (value) patch.put("keywords/" + flag, true); else patch.putNull("keywords/" + flag);
        applySet(user, args, "k0", emailId);
    }

    public void move(String user, String emailId, String targetFolderId) {
        if (targetFolderId == null || targetFolderId.isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL, "No target folder given");
        }
        ObjectNode args = client.accountArgs(user);
        // Replace mailboxIds wholesale rather than patching, so a message that was
        // filed in two folders ends up in exactly the one the user picked.
        args.putObject("update").putObject(emailId).putObject("mailboxIds").put(targetFolderId, true);
        applySet(user, args, "m0", emailId);
    }

    /**
     * Delete means "move to Trash" the first time and "really destroy" once the
     * message is already in Trash. That is what every mail client does, and it is
     * the difference between a recoverable mistake and a permanent one.
     */
    public void delete(String user, String emailId) {
        MailFolder trash = folderByRole(user, "trash");
        if (trash == null) {
            purge(user, emailId);
            return;
        }
        if (folderIdsOf(user, emailId).contains(trash.id())) {
            purge(user, emailId);
        } else {
            move(user, emailId, trash.id());
        }
    }

    /** Unrecoverable. Only for an explicit "delete forever". */
    public void purge(String user, String emailId) {
        ObjectNode args = client.accountArgs(user);
        args.putArray("destroy").add(emailId);
        applySet(user, args, "d0", emailId);
    }

    private void applySet(String user, ObjectNode args, String callId, String id) {
        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Email/set", args, callId))),
                "Email/set", callId);

        // A JMAP set answers HTTP 200 and can still have refused the one object we
        // asked about, so the per-object rejection maps have to be checked.
        JsonNode refused = result.path("notUpdated").path(id);
        if (refused.isMissingNode() || refused.isNull()) refused = result.path("notDestroyed").path(id);
        if (refused.isMissingNode() || refused.isNull()) return;

        String type = JmapClient.text(refused, "type");
        throw new MailException(
                "notFound".equals(type) ? MailException.Kind.NOT_FOUND : MailException.Kind.METHOD,
                type, "Email/set refused " + id + ": " + (type == null ? "unknown reason" : type), null);
    }

    private List<String> folderIdsOf(String user, String emailId) {
        ObjectNode args = client.accountArgs(user);
        args.putArray("ids").add(emailId);
        args.putArray("properties").add("id").add("mailboxIds");

        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Email/get", args, "w0"))),
                "Email/get", "w0");

        JsonNode e = result.path("list").path(0);
        if (e.isMissingNode() || e.isNull()) {
            throw new MailException(MailException.Kind.NOT_FOUND,
                    "No message " + emailId + " in " + user + "'s mailbox");
        }
        return mailboxIds(e.path("mailboxIds"));
    }

    // ------------------------------------------------------------------
    // Identities and sending
    // ------------------------------------------------------------------

    /** What the compose screen puts in its From dropdown. Stalwart decides this, not our user table. */
    public List<Identity> listIdentities(String user) {
        ObjectNode args = client.accountArgs(user);
        args.putNull("ids");

        JsonNode result = client.response(
                client.call(user, IDENTITY_CAPS, client.newArray().add(client.invocation("Identity/get", args, "i0"))),
                "Identity/get", "i0");

        List<Identity> out = new ArrayList<>();
        for (JsonNode i : result.path("list")) {
            out.add(new Identity(
                    JmapClient.text(i, "id"), JmapClient.text(i, "name"), JmapClient.text(i, "email"),
                    JmapClient.text(i, "replyTo"),
                    JmapClient.text(i, "textSignature"), JmapClient.text(i, "htmlSignature")));
        }
        return List.copyOf(out);
    }

    /**
     * Sends and files the result in Sent, in one request.
     *
     * The message is created in Drafts and submitted in the same call, with
     * onSuccessUpdateEmail moving it to Sent and clearing $draft only if the
     * submission was actually accepted. Doing it in two requests would leave a
     * ghost draft behind every time submission failed.
     *
     * Returns the JMAP id of the sent message.
     */
    public String send(String user, List<String> to, List<String> cc, String subject,
                       String htmlBody, String textBody) {
        List<String> recipients = cleanAddresses(to);
        List<String> copies = cleanAddresses(cc);
        if (recipients.isEmpty() && copies.isEmpty()) {
            throw new MailException(MailException.Kind.PROTOCOL, "A message needs at least one recipient");
        }

        Identity identity = defaultIdentity(user);
        Map<String, MailFolder> roles = rolesOf(user);
        MailFolder drafts = roles.get("drafts");
        MailFolder sent = roles.get("sent");
        if (drafts == null) {
            throw new MailException(MailException.Kind.NOT_FOUND, "No Drafts folder in " + user + "'s mailbox");
        }

        boolean hasHtml = htmlBody != null && !htmlBody.isBlank();
        // Never send HTML alone. A text alternative is what non-HTML clients and
        // most spam filters read, and it costs nothing to derive one.
        String text = textBody != null && !textBody.isBlank()
                ? textBody
                : (hasHtml ? ses.toPlainText(htmlBody) : "");

        ObjectNode draft = client.newObject();
        draft.putObject("mailboxIds").put(drafts.id(), true);
        ObjectNode keywords = draft.putObject("keywords");
        keywords.put("$draft", true);
        keywords.put("$seen", true);
        draft.putArray("from").addObject()
                .put("name", identity.name() == null ? "" : identity.name())
                .put("email", identity.email());
        addressArray(draft.putArray("to"), recipients);
        if (!copies.isEmpty()) addressArray(draft.putArray("cc"), copies);
        if (identity.replyTo() != null && !identity.replyTo().isBlank()) {
            addressArray(draft.putArray("replyTo"), List.of(identity.replyTo()));
        }
        draft.put("subject", subject == null ? "" : subject);

        ObjectNode values = client.newObject();
        if (hasHtml) {
            ObjectNode structure = draft.putObject("bodyStructure");
            structure.put("type", "multipart/alternative");
            ArrayNode parts = structure.putArray("subParts");
            parts.addObject().put("partId", "t").put("type", "text/plain");
            parts.addObject().put("partId", "h").put("type", "text/html");
            values.putObject("t").put("value", text);
            values.putObject("h").put("value", htmlBody);
        } else {
            draft.putObject("bodyStructure").put("partId", "t").put("type", "text/plain");
            values.putObject("t").put("value", text);
        }
        draft.set("bodyValues", values);

        ObjectNode create = client.accountArgs(user);
        create.putObject("create").set("draft", draft);

        ObjectNode submit = client.accountArgs(user);
        ObjectNode submission = submit.putObject("create").putObject("sub");
        submission.put("emailId", "#draft");
        submission.put("identityId", identity.id());
        ObjectNode envelope = submission.putObject("envelope");
        envelope.putObject("mailFrom").put("email", identity.email());
        ArrayNode rcpt = envelope.putArray("rcptTo");
        for (String address : recipients) rcpt.addObject().put("email", address);
        for (String address : copies) rcpt.addObject().put("email", address);

        if (sent != null) {
            ObjectNode onSuccess = submit.putObject("onSuccessUpdateEmail").putObject("#sub");
            onSuccess.putNull("mailboxIds/" + drafts.id());
            onSuccess.put("mailboxIds/" + sent.id(), true);
            onSuccess.putNull("keywords/$draft");
        }

        JsonNode responses = client.call(user, SEND_CAPS, client.newArray()
                .add(client.invocation("Email/set", create, "c0"))
                .add(client.invocation("EmailSubmission/set", submit, "s0")));

        JsonNode created = client.response(responses, "Email/set", "c0");
        JsonNode refusedDraft = created.path("notCreated").path("draft");
        if (!refusedDraft.isMissingNode() && !refusedDraft.isNull()) {
            throw new MailException(MailException.Kind.METHOD, JmapClient.text(refusedDraft, "type"),
                    "Mail server refused the message: " + JmapClient.text(refusedDraft, "type"), null);
        }

        JsonNode submitted = client.response(responses, "EmailSubmission/set", "s0");
        JsonNode refusedSend = submitted.path("notCreated").path("sub");
        if (!refusedSend.isMissingNode() && !refusedSend.isNull()) {
            throw new MailException(MailException.Kind.METHOD, JmapClient.text(refusedSend, "type"),
                    "Mail server accepted the message but refused to send it: "
                            + JmapClient.text(refusedSend, "type"), null);
        }

        return JmapClient.text(created.path("created").path("draft"), "id");
    }

    private Identity defaultIdentity(String user) {
        List<Identity> identities = listIdentities(user);
        if (identities.isEmpty()) {
            throw new MailException(MailException.Kind.NOT_FOUND,
                    "Stalwart lists no send identity for " + user);
        }
        for (Identity i : identities) {
            if (i.email() != null && i.email().equalsIgnoreCase(user)) return i;
        }
        return identities.get(0);
    }

    private Map<String, MailFolder> rolesOf(String user) {
        Map<String, MailFolder> byRole = new LinkedHashMap<>();
        for (MailFolder f : listFolders(user)) {
            if (f.role() != null && !f.role().isBlank()) {
                byRole.put(f.role().toLowerCase(Locale.ROOT), f);
            }
        }
        return byRole;
    }

    // ------------------------------------------------------------------
    // Several mailboxes at once
    // ------------------------------------------------------------------

    /**
     * The mailboxes overview wants unread counts for every shared address at once.
     * These really are independent round trips, so fan them out and let them all
     * wait together instead of adding five request times up. One mailbox being
     * unreachable returns an empty list for that address rather than blanking the
     * page, which is the whole reason this does not use a plain stream.
     *
     * Only covers addresses whose credential this process is currently holding.
     */
    public Map<String, List<MailFolder>> listFoldersForAll(Collection<String> users) {
        if (users == null || users.isEmpty()) return Map.of();
        Map<String, List<MailFolder>> out = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String user : users) {
                pool.execute(() -> {
                    try {
                        out.put(user, listFolders(user));
                    } catch (RuntimeException e) {
                        out.put(user, List.of());
                    }
                });
            }
        }
        return Map.copyOf(out);
    }

    // ------------------------------------------------------------------
    // JSON to records
    // ------------------------------------------------------------------

    private static MessageSummary toSummary(JsonNode e) {
        JsonNode keywords = e.path("keywords");
        return new MessageSummary(
                JmapClient.text(e, "id"),
                JmapClient.text(e, "threadId"),
                JmapClient.text(e, "subject"),
                addresses(e.path("from")), addresses(e.path("to")), addresses(e.path("cc")),
                JmapClient.text(e, "preview"),
                instant(e, "receivedAt"),
                e.path("size").asLong(0L),
                keywords.path("$seen").asBoolean(false),
                keywords.path("$flagged").asBoolean(false),
                keywords.path("$answered").asBoolean(false),
                keywords.path("$draft").asBoolean(false),
                e.path("hasAttachment").asBoolean(false),
                mailboxIds(e.path("mailboxIds")));
    }

    private static List<MailAddress> addresses(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<MailAddress> out = new ArrayList<>();
        for (JsonNode a : node) {
            out.add(new MailAddress(JmapClient.text(a, "name"), JmapClient.text(a, "email")));
        }
        return List.copyOf(out);
    }

    private static List<Attachment> attachments(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Attachment> out = new ArrayList<>();
        for (JsonNode a : node) {
            out.add(new Attachment(
                    JmapClient.text(a, "partId"),
                    JmapClient.text(a, "blobId"),
                    JmapClient.text(a, "name"),
                    JmapClient.text(a, "type"),
                    a.path("size").asLong(0L),
                    JmapClient.text(a, "disposition"),
                    JmapClient.text(a, "cid")));
        }
        return List.copyOf(out);
    }

    /** mailboxIds arrives as {"a": true}. Only the true entries mean anything. */
    private static List<String> mailboxIds(JsonNode node) {
        if (!node.isObject()) return List.of();
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (entry.getValue().asBoolean(false)) ids.add(entry.getKey());
        }
        return List.copyOf(ids);
    }

    /**
     * Joins body parts to their values.
     *
     * The type check is load bearing. When a message has no HTML alternative,
     * Stalwart returns the same text/plain part under htmlBody as well, so a
     * client that trusts htmlBody blindly renders the plain text into an HTML
     * pane and shows the reader escaped markup or, worse, executes it.
     */
    private static String bodyOfType(JsonNode parts, JsonNode values, String wantedType) {
        if (!parts.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String type = JmapClient.text(part, "type");
            if (type == null || !type.equalsIgnoreCase(wantedType)) continue;
            String partId = JmapClient.text(part, "partId");
            if (partId == null) continue;
            String value = JmapClient.text(values.path(partId), "value");
            if (value != null) sb.append(value);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String s = JmapClient.string(n);
            if (s != null) out.add(s);
        }
        return List.copyOf(out);
    }

    /** messageId comes back as an array of bare ids without angle brackets. */
    private static String firstString(JsonNode node) {
        List<String> all = strings(node);
        return all.isEmpty() ? JmapClient.string(node) : all.get(0);
    }

    /** JMAP timestamps are RFC 3339 in UTC, e.g. 2026-08-15T15:53:59Z. */
    private static Instant instant(JsonNode node, String field) {
        String raw = JmapClient.text(node, field);
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Input hygiene
    // ------------------------------------------------------------------

    private static List<String> cleanAddresses(List<String> raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String candidate : raw) {
            if (candidate == null) continue;
            String address = candidate.trim();
            if (address.isEmpty()) continue;
            // Same shape check the campaign side uses, so one bad row cannot get a
            // whole submission rejected by Stalwart with an opaque envelope error.
            if (!SesSender.EMAIL_OK.matcher(address).matches()) {
                throw new MailException(MailException.Kind.PROTOCOL, "Not an email address: " + address);
            }
            if (!out.contains(address)) out.add(address);
        }
        return out;
    }

    private static void addressArray(ArrayNode target, List<String> addresses) {
        for (String address : addresses) target.addObject().put("email", address);
    }

    /**
     * The UI says "seen", JMAP says "$seen". User labels have no dollar and must
     * keep their own name, so only the five system keywords get the prefix.
     */
    private static String normaliseKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL, "No keyword given");
        }
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        // The keyword goes into a JSON Pointer patch path, where these two are structural.
        if (k.indexOf('/') >= 0 || k.indexOf('~') >= 0) {
            throw new MailException(MailException.Kind.PROTOCOL, "A keyword may not contain / or ~: " + keyword);
        }
        if (k.startsWith("$")) return k;
        return SYSTEM_KEYWORDS.contains(k) ? "$" + k : k;
    }
}
