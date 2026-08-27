package com.jarurat.mailer.webmail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the HTML a stranger emailed us into HTML that is safe to put on screen.
 *
 * WHY THIS IS LAYERED, AND WHY NO SINGLE LAYER IS TRUSTED
 * -------------------------------------------------------
 * A message body is attacker controlled input that we are contractually obliged to
 * render. Every webmail CVE of the last decade is a sanitiser that was defeated by
 * one clever payload, so the design assumes this file WILL eventually be beaten and
 * makes that survivable. Four independent layers, each of which alone would stop the
 * classic attacks:
 *
 *   1. This sanitiser (server side, this file). An ALLOWLIST parser, not a regex
 *      blocklist. Anything not explicitly permitted is dropped, so the tags the brief
 *      names - script, iframe, object, embed, form, input, select, base, meta refresh -
 *      are gone by construction rather than by remembering to ban them. It is a real
 *      tokeniser, because a regex cannot tell <img alt="a>b" onerror=x> apart from a
 *      closed tag and that difference is exactly where sanitisers get broken.
 *
 *   2. The sandboxed iframe (browser, see mail.js). srcdoc with
 *      sandbox="allow-popups allow-popups-to-escape-sandbox". No allow-scripts means
 *      injected JavaScript cannot execute even if layer 1 let it through. No
 *      allow-same-origin means the frame has an opaque origin, so it cannot read our
 *      cookies, our DOM, or call our API even if script somehow ran. No allow-forms
 *      means a phishing form cannot post. allow-popups plus
 *      allow-popups-to-escape-sandbox exist only so that a legitimate link still opens
 *      in a normal tab instead of a crippled one.
 *
 *   3. A Content-Security-Policy meta injected into the srcdoc document by this class
 *      (see wrapDocument). default-src 'none' means the frame cannot fetch anything we
 *      did not name. img-src is the switch behind the "show images" button.
 *
 *   4. The application CSP already set in SecurityConfig, which a srcdoc frame
 *      inherits from its parent. Independent of everything above.
 *
 * REMOTE IMAGES are blocked by default because an <img> is a read receipt: it tells
 * the sender the message was opened, from which IP, at what time. Unblocking is a
 * fresh authenticated request, not a client side flag, so the browser cannot turn
 * tracking back on by itself.
 */
public final class MailHtmlSanitizer {

    private MailHtmlSanitizer() {
    }

    /** Sanitised markup plus how many remote images were withheld, for the banner. */
    public record Result(String html, int blockedImages) {
    }

    /** Bodies larger than this are truncated. A 5 MB body is an attack, not a newsletter. */
    private static final int MAX_INPUT = 2_000_000;
    private static final int MAX_CSS = 200_000;
    private static final int MAX_DEPTH = 64;

    /** A transparent 1x1 gif, so a withheld image is still a real box we can outline. */
    private static final String PIXEL =
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

    private static final Set<String> ALLOWED = Set.of(
            "a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote",
            "br", "caption", "center", "cite", "code", "col", "colgroup", "dd", "del", "dfn",
            "div", "dl", "dt", "em", "figcaption", "figure", "font", "footer", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "hr", "i", "img", "ins", "kbd", "li", "main", "mark",
            "nav", "ol", "p", "pre", "q", "s", "samp", "section", "small", "span", "strike",
            "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time",
            "tr", "tt", "u", "ul", "var", "wbr");

    /** Allowed elements that never have a closing tag. */
    private static final Set<String> VOID = Set.of("br", "col", "hr", "img", "wbr");

    /**
     * Content is raw text to the HTML parser, so it must be consumed wholesale rather
     * than parsed. Leaving it to the normal path would dump JavaScript source into the
     * reader as visible text, and worse, would let "</script><img onerror>" reopen markup.
     */
    private static final Set<String> RAW_TEXT = Set.of("script", "textarea", "title");

    /**
     * Dropped along with everything inside them. These carry no readable content, and
     * keeping their children would just resurrect the controls we are removing.
     */
    private static final Set<String> DROP_SUBTREE = Set.of(
            "iframe", "object", "form", "select", "option", "optgroup", "button", "applet",
            "svg", "math", "frameset", "frame", "noscript", "template", "audio", "video",
            "canvas", "map", "datalist", "portal");

    /**
     * Void elements that are simply deleted. base rewrites every relative URL in the
     * document, meta does refresh redirects, link pulls remote stylesheets, and input
     * paints a login box that is not ours.
     */
    private static final Set<String> DROP_TAG = Set.of(
            "base", "meta", "link", "input", "embed", "param", "source", "track", "keygen",
            "isindex", "basefont");

    private static final Set<String> GLOBAL_ATTRS = Set.of(
            "title", "dir", "lang", "align", "valign", "class", "style");

    private static final Map<String, Set<String>> EXTRA_ATTRS = Map.ofEntries(
            Map.entry("a", Set.of("href")),
            Map.entry("img", Set.of("src", "alt", "width", "height", "border", "hspace", "vspace")),
            Map.entry("table", Set.of("width", "height", "border", "cellpadding", "cellspacing", "bgcolor", "background")),
            Map.entry("td", Set.of("width", "height", "colspan", "rowspan", "bgcolor", "background", "nowrap")),
            Map.entry("th", Set.of("width", "height", "colspan", "rowspan", "bgcolor", "background", "nowrap")),
            Map.entry("tr", Set.of("height", "bgcolor", "background")),
            Map.entry("tbody", Set.of("bgcolor")),
            Map.entry("thead", Set.of("bgcolor")),
            Map.entry("tfoot", Set.of("bgcolor")),
            Map.entry("col", Set.of("span", "width")),
            Map.entry("colgroup", Set.of("span", "width")),
            Map.entry("font", Set.of("color", "face", "size")),
            Map.entry("ol", Set.of("type", "start")),
            Map.entry("ul", Set.of("type")),
            Map.entry("li", Set.of("type", "value")),
            Map.entry("hr", Set.of("size", "width", "noshade")));

    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto", "tel");

    /** Checked after CSS comments and escapes are removed, so obfuscation does not help. */
    private static final String[] CSS_POISON = {
            "expression(", "javascript:", "vbscript:", "-moz-binding", "behavior:", "@import",
            "@charset", "@namespace"};

    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern CSS_URL = Pattern.compile("url\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINKIFY = Pattern.compile("https?://[^\\s<>\"')\\]]+");

    // ------------------------------------------------------------------ public API

    /**
     * The whole job in one call: pick the best body the message offers, clean it, and
     * wrap it in a standalone document ready to hand to an iframe's srcdoc property.
     */
    public static Result toReaderDocument(String html, String text, boolean allowRemoteImages, String theme) {
        Result body;
        if (html != null && !html.isBlank()) {
            body = sanitize(html, allowRemoteImages);
        } else {
            body = new Result(plainTextToHtml(text), 0);
        }
        return new Result(wrapDocument(body.html(), allowRemoteImages, theme), body.blockedImages());
    }

    /** Sanitises a fragment. The result is a fragment, not a document. */
    public static Result sanitize(String rawHtml, boolean allowRemoteImages) {
        if (rawHtml == null || rawHtml.isBlank()) return new Result("", 0);

        String src = rawHtml.length() > MAX_INPUT ? rawHtml.substring(0, MAX_INPUT) : rawHtml;
        StringBuilder out = new StringBuilder(Math.min(src.length() + 512, MAX_INPUT));
        Deque<String> open = new ArrayDeque<>();
        int[] blocked = {0};

        int i = 0;
        int n = src.length();
        while (i < n) {
            if (src.charAt(i) != '<') {
                int lt = src.indexOf('<', i);
                int end = lt < 0 ? n : lt;
                escapeText(src, i, end, out);
                i = end;
                continue;
            }

            // Comments go entirely. Conditional comments are markup in disguise and
            // Outlook mail is full of them.
            if (src.startsWith("<!--", i)) {
                int e = src.indexOf("-->", i + 4);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (src.startsWith("<!", i) || src.startsWith("<?", i)) {
                int e = src.indexOf('>', i);
                i = e < 0 ? n : e + 1;
                continue;
            }

            Tag tag = parseTag(src, i);
            if (tag == null) {
                // A bare "<" in prose. Escape it and move on.
                out.append("&lt;");
                i++;
                continue;
            }
            i = tag.end;
            String name = tag.name;

            if (tag.closing) {
                if (VOID.contains(name) || !ALLOWED.contains(name) || !open.contains(name)) continue;
                String popped;
                do {
                    popped = open.pop();
                    out.append("</").append(popped).append('>');
                } while (!popped.equals(name));
                continue;
            }

            if ("style".equals(name)) {
                int[] span = rawTextSpan(src, i, "style");
                String css = sanitizeStylesheet(src.substring(i, span[0]), allowRemoteImages);
                if (!css.isEmpty()) out.append("<style>").append(css).append("</style>");
                i = span[1];
                continue;
            }
            if (RAW_TEXT.contains(name)) {
                i = rawTextSpan(src, i, name)[1];
                continue;
            }
            if (DROP_SUBTREE.contains(name)) {
                if (!tag.selfClosing) i = skipSubtree(src, i, name);
                continue;
            }
            if (DROP_TAG.contains(name)) continue;

            // Unknown but harmless wrappers (body, o:p, custom elements) are unwrapped:
            // the tag goes, the text inside stays.
            if (!ALLOWED.contains(name)) continue;

            if (open.size() >= MAX_DEPTH) continue;

            out.append('<').append(name);
            writeAttributes(name, tag.attrs, allowRemoteImages, out, blocked);
            out.append('>');
            if (!VOID.contains(name)) {
                if (tag.selfClosing) out.append("</").append(name).append('>');
                else open.push(name);
            }
        }

        while (!open.isEmpty()) out.append("</").append(open.pop()).append('>');
        return new Result(out.toString(), blocked[0]);
    }

    /** Plain text bodies still need escaping, and still deserve clickable links. */
    public static String plainTextToHtml(String text) {
        if (text == null || text.isBlank()) return "<p class=\"jc-empty\">This message has no readable body.</p>";
        String src = text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text;

        StringBuilder out = new StringBuilder(src.length() + 256);
        out.append("<pre class=\"jc-plain\">");
        Matcher m = LINKIFY.matcher(src);
        int at = 0;
        while (m.find()) {
            escapeText(src, at, m.start(), out);
            String url = trimTrailingPunctuation(m.group());
            String safe = cleanUrl(url);
            if (safe == null) {
                escapeAttr(url, out);
            } else {
                out.append("<a href=\"");
                escapeAttr(safe, out);
                out.append("\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">");
                escapeText(url, 0, url.length(), out);
                out.append("</a>");
            }
            at = m.start() + url.length();
        }
        escapeText(src, at, src.length(), out);
        out.append("</pre>");
        return out.toString();
    }

    // ------------------------------------------------------------------ tokeniser

    private record Tag(String name, boolean closing, boolean selfClosing,
                       Map<String, String> attrs, int end) {
    }

    /** Returns null when the "<" does not actually start a tag. */
    private static Tag parseTag(String s, int start) {
        int n = s.length();
        int j = start + 1;
        boolean closing = false;
        if (j < n && s.charAt(j) == '/') {
            closing = true;
            j++;
        }
        int nameStart = j;
        if (j >= n || !Character.isLetter(s.charAt(j))) return null;
        while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == ':' || s.charAt(j) == '-')) j++;
        String name = s.substring(nameStart, j).toLowerCase(Locale.ROOT);

        Map<String, String> attrs = new LinkedHashMap<>();
        boolean selfClosing = false;
        while (j < n) {
            char c = s.charAt(j);
            if (Character.isWhitespace(c)) {
                j++;
                continue;
            }
            if (c == '/') {
                selfClosing = true;
                j++;
                continue;
            }
            if (c == '>') {
                j++;
                return new Tag(name, closing, selfClosing, attrs, j);
            }
            selfClosing = false;

            int an = j;
            while (j < n) {
                char a = s.charAt(j);
                if (Character.isWhitespace(a) || a == '=' || a == '>' || a == '/') break;
                j++;
            }
            if (j == an) {
                j++;   // a stray "=" or quote: skip it rather than spin
                continue;
            }
            String attrName = s.substring(an, j).toLowerCase(Locale.ROOT);

            while (j < n && Character.isWhitespace(s.charAt(j))) j++;
            String value = "";
            if (j < n && s.charAt(j) == '=') {
                j++;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                if (j < n && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
                    char quote = s.charAt(j++);
                    int vs = j;
                    // A quoted value swallows ">" exactly as the browser's parser does.
                    // Getting this wrong is how regex sanitisers get bypassed.
                    while (j < n && s.charAt(j) != quote) j++;
                    value = s.substring(vs, Math.min(j, n));
                    if (j < n) j++;
                } else {
                    int vs = j;
                    while (j < n && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '>') j++;
                    value = s.substring(vs, j);
                }
            }
            // Decoded once, here, because the browser decodes attribute values too.
            // Doing it at the single point of entry is what stops "java&#115;cript:"
            // from looking harmless to every check downstream.
            attrs.putIfAbsent(attrName, decodeEntities(value));
        }
        // Ran off the end without a ">". Treat what we parsed as the tag.
        return new Tag(name, closing, selfClosing, attrs, n);
    }

    /** {content end, index after the closing tag} for a raw text element. */
    private static int[] rawTextSpan(String s, int from, String tag) {
        String needle = "</" + tag;
        int i = from;
        while (i < s.length()) {
            int k = indexOfIgnoreCase(s, needle, i);
            if (k < 0) return new int[]{s.length(), s.length()};
            int after = k + needle.length();
            if (after >= s.length()) return new int[]{k, s.length()};
            char c = s.charAt(after);
            if (c == '>' || c == '/' || Character.isWhitespace(c)) {
                int gt = s.indexOf('>', after);
                return new int[]{k, gt < 0 ? s.length() : gt + 1};
            }
            i = k + 1;
        }
        return new int[]{s.length(), s.length()};
    }

    /**
     * Skips a whole element including nested copies of itself. A "&gt;" inside a quoted
     * attribute of some inner tag can make this stop early, which is harmless: whatever
     * is left over goes back through the main loop and is sanitised there anyway.
     */
    private static int skipSubtree(String s, int from, String tag) {
        int depth = 1;
        int i = from;
        int n = s.length();
        while (i < n && depth > 0) {
            int lt = s.indexOf('<', i);
            if (lt < 0) return n;
            boolean closing = lt + 1 < n && s.charAt(lt + 1) == '/';
            int nameStart = closing ? lt + 2 : lt + 1;
            int j = nameStart;
            while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == ':' || s.charAt(j) == '-')) j++;
            if (!s.regionMatches(true, nameStart, tag, 0, tag.length()) || j - nameStart != tag.length()) {
                i = lt + 1;
                continue;
            }
            int gt = s.indexOf('>', j);
            int end = gt < 0 ? n : gt + 1;
            if (closing) {
                depth--;
                i = end;
            } else {
                if (gt > 0 && s.charAt(gt - 1) == '/') i = end;
                else {
                    depth++;
                    i = end;
                }
            }
        }
        return i;
    }

    // ------------------------------------------------------------------ attributes

    private static void writeAttributes(String tag, Map<String, String> attrs,
                                        boolean allowRemoteImages, StringBuilder out, int[] blocked) {
        Set<String> extra = EXTRA_ATTRS.getOrDefault(tag, Set.of());
        boolean anchorHasHref = false;
        boolean imageWithheld = false;

        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String key = e.getKey();
            String raw = e.getValue();

            // Belt and braces. The allowlist below already excludes these, but an event
            // handler slipping through is the one mistake worth failing twice on.
            if (key.startsWith("on") || key.equals("srcdoc") || key.equals("formaction")
                    || key.equals("xlink:href") || key.equals("http-equiv") || key.equals("action")) {
                continue;
            }
            if (!GLOBAL_ATTRS.contains(key) && !extra.contains(key)) continue;

            switch (key) {
                case "style" -> {
                    String css = sanitizeDeclarations(raw, allowRemoteImages);
                    if (!css.isEmpty()) {
                        out.append(" style=\"");
                        escapeAttr(css, out);
                        out.append('"');
                    }
                }
                case "href" -> {
                    String url = cleanUrl(raw);
                    if (url != null) {
                        out.append(" href=\"");
                        escapeAttr(url, out);
                        out.append('"');
                        anchorHasHref = true;
                    }
                }
                case "src" -> {
                    String url = cleanUrl(raw);
                    if (url == null) {
                        // cid: and relative sources cannot be served, so they render as
                        // a withheld image rather than a broken one.
                        imageWithheld = true;
                    } else if (isInlineData(url)) {
                        out.append(" src=\"");
                        escapeAttr(url, out);
                        out.append('"');
                    } else if (allowRemoteImages) {
                        out.append(" src=\"");
                        escapeAttr(url, out);
                        out.append('"');
                    } else {
                        blocked[0]++;
                        imageWithheld = true;
                    }
                }
                case "background" -> {
                    String url = cleanUrl(raw);
                    if (url != null && !isInlineData(url)) {
                        if (allowRemoteImages) {
                            out.append(" background=\"");
                            escapeAttr(url, out);
                            out.append('"');
                        } else {
                            blocked[0]++;
                        }
                    }
                }
                default -> {
                    out.append(' ').append(key).append("=\"");
                    escapeAttr(raw, out);
                    out.append('"');
                }
            }
        }

        if ("a".equals(tag) && anchorHasHref) {
            // noopener because the popup escapes the sandbox; noreferrer so the target
            // never learns which mailbox opened it.
            out.append(" target=\"_blank\" rel=\"noopener noreferrer nofollow\"");
        }
        if ("img".equals(tag) && imageWithheld) {
            out.append(" src=\"").append(PIXEL).append("\" class=\"jc-blocked-img\"");
        }
    }

    /**
     * Raster image data URLs only.
     *
     * SVG is deliberately excluded even though it is an image type. A sanitiser
     * cannot see inside a base64 payload, so data:image/svg+xml is a sealed
     * envelope that can carry script, foreignObject or feImage past every check
     * above it. The sandboxed frame would stop the script running today, but this
     * is the layer that must not depend on that, and an allowlist of formats that
     * cannot contain markup costs nothing.
     */
    private static boolean isInlineData(String url) {
        if (!url.regionMatches(true, 0, "data:image/", 0, 11)) return false;
        String rest = url.substring(11).toLowerCase(Locale.ROOT);
        for (String type : RASTER_DATA_TYPES) {
            if (rest.startsWith(type)) return true;
        }
        return false;
    }

    /** Formats with no markup layer, so no script can hide inside one. */
    private static final List<String> RASTER_DATA_TYPES =
            List.of("png", "jpeg", "jpg", "gif", "webp", "bmp", "avif");

    /**
     * Returns a URL that is safe to put in an attribute, or null to drop it. The value
     * is expected to be entity decoded already (parseTag does it). Whitespace and
     * control characters are ignored while identifying the scheme, because the browser
     * ignores them too and "java\tscript:" is a real payload.
     */
    static String cleanUrl(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;

        StringBuilder probe = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c > 0x20 && c != 0x7f) probe.append(Character.toLowerCase(c));
        }
        String p = probe.toString();

        int colon = p.indexOf(':');
        if (colon <= 0) return null;                       // relative URLs: see below
        int slash = p.indexOf('/');
        int hash = p.indexOf('#');
        int query = p.indexOf('?');
        boolean schemeFirst = (slash < 0 || colon < slash) && (hash < 0 || colon < hash)
                && (query < 0 || colon < query);
        // Not a scheme means a relative or fragment URL. Those resolve against OUR
        // origin inside the frame, so a link could impersonate a console page. Dropped.
        if (!schemeFirst) return null;

        String scheme = p.substring(0, colon);
        if (SAFE_SCHEMES.contains(scheme)) return v;
        // Same raster-only rule as isInlineData: svg+xml is an image type that can
        // carry script, and nothing here can inspect a base64 payload.
        if (scheme.equals("data") && isInlineData(p)) return v;
        return null;
    }

    private static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            if (j < n && s.charAt(j) == '#') {
                j++;
                int radix = 10;
                if (j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
                    radix = 16;
                    j++;
                }
                int ds = j;
                while (j < n && j - ds < 8 && Character.digit(s.charAt(j), radix) >= 0) j++;
                if (j > ds) {
                    try {
                        int cp = Integer.parseInt(s.substring(ds, j), radix);
                        if (cp > 0 && cp <= 0x10FFFF) out.appendCodePoint(cp);
                    } catch (NumberFormatException ignored) {
                        // malformed entity, drop it
                    }
                    if (j < n && s.charAt(j) == ';') j++;
                    i = j;
                    continue;
                }
            } else {
                int ns = j;
                while (j < n && j - ns < 12 && Character.isLetter(s.charAt(j))) j++;
                String named = NAMED.get(s.substring(ns, j).toLowerCase(Locale.ROOT));
                if (named != null) {
                    out.append(named);
                    if (j < n && s.charAt(j) == ';') j++;
                    i = j;
                    continue;
                }
            }
            out.append('&');
            i++;
        }
        return out.toString();
    }

    /** Only the entities that matter for scheme smuggling, plus the obvious ones. */
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("colon", ":"), Map.entry("tab", "\t"), Map.entry("newline", "\n"),
            Map.entry("sol", "/"), Map.entry("lpar", "("), Map.entry("rpar", ")"),
            Map.entry("semi", ";"));

    // ------------------------------------------------------------------ css

    /** Sanitises the contents of a style attribute, declaration by declaration. */
    static String sanitizeDeclarations(String raw, boolean allowRemoteImages) {
        if (raw == null || raw.isBlank()) return "";
        String css = prepareCss(raw);
        if (css.isEmpty()) return "";

        StringBuilder out = new StringBuilder(css.length());
        for (String decl : splitDeclarations(css)) {
            String d = decl.trim();
            if (d.isEmpty()) continue;
            int colon = d.indexOf(':');
            if (colon <= 0) continue;
            String prop = d.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!prop.matches("[a-z-]{2,40}")) continue;
            if (isPoisonedCss(d)) continue;
            String cleaned = filterCssUrls(d, allowRemoteImages);
            if (cleaned == null) continue;
            if (out.length() > 0) out.append(';');
            out.append(cleaned);
        }
        return out.toString();
    }

    /**
     * Sanitises a whole stylesheet by REBUILDING it from the rules it recognises,
     * rather than by filtering the text and passing the rest through. That distinction
     * matters: a style element whose content is not CSS at all, which is what an
     * attacker sends when they are trying to smuggle markup through the raw text
     * parsing rules, produces no rules and therefore produces no output. Nothing the
     * sender wrote reaches the page unless it parsed as "selector { declarations }".
     */
    static String sanitizeStylesheet(String raw, boolean allowRemoteImages) {
        if (raw == null || raw.isBlank()) return "";
        String css = prepareCss(raw.length() > MAX_CSS ? raw.substring(0, MAX_CSS) : raw);
        if (css.isEmpty() || isPoisonedCss(css)) return "";
        return rebuildRules(css, allowRemoteImages, 0);
    }

    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9 \t\r\n.#>+~*:,()\\[\\]='\"_-]{1,400}");
    private static final Pattern SAFE_AT_RULE = Pattern.compile("@(media|supports)[A-Za-z0-9 \t\r\n:()\\-,.&|]*",
            Pattern.CASE_INSENSITIVE);

    private static String rebuildRules(String css, boolean allowRemoteImages, int depth) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        int n = css.length();
        while (i < n) {
            int open = css.indexOf('{', i);
            if (open < 0) break;
            String prelude = css.substring(i, open).trim();
            int close = matchingBrace(css, open);
            if (close < 0) break;
            String block = css.substring(open + 1, close);
            i = close + 1;

            if (prelude.startsWith("@")) {
                // @media and @supports carry real layout intent. @import and @font-face
                // fetch from a third party, which is exactly what we are preventing.
                if (depth > 0 || !SAFE_AT_RULE.matcher(prelude).matches()) continue;
                String inner = rebuildRules(block, allowRemoteImages, depth + 1);
                if (!inner.isEmpty()) out.append(prelude).append('{').append(inner).append('}');
                continue;
            }
            if (prelude.isEmpty() || !SELECTOR.matcher(prelude).matches()) continue;
            String decls = sanitizeDeclarations(block, allowRemoteImages);
            if (decls.isEmpty()) continue;
            out.append(prelude).append('{').append(decls).append('}');
        }
        return out.toString();
    }

    private static int matchingBrace(String css, int open) {
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            char c = css.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /**
     * CSS comments can be spliced into the middle of a keyword to hide it, backslashes
     * hide keywords as CSS escapes, and a literal less-than is the only way out of a
     * style element. All three are removed before anything else looks at the text.
     * Spaces are kept, because font-family values need them, and isPoisonedCss ignores
     * whitespace when it hunts for the dangerous keywords anyway.
     */
    private static String prepareCss(String raw) {
        String css = CSS_COMMENT.matcher(raw).replaceAll("");
        return css.replace("\\", "").replace("<", "").trim();
    }

    /** Whitespace is ignored here, so "java script:" is caught the same as "javascript:". */
    private static boolean isPoisonedCss(String decl) {
        String low = decl.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String bad : CSS_POISON) {
            if (low.contains(bad)) return true;
        }
        return false;
    }

    /**
     * Splits on ";" but not inside url(...) or a quoted string, so a base64 data URI,
     * which is full of semicolons, survives as a single declaration.
     */
    private static List<String> splitDeclarations(String css) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < css.length(); i++) {
            char c = css.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == ';' && depth == 0) {
                parts.add(css.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(css.substring(start));
        return parts;
    }

    /** Rewrites url(...) to "none" unless it is an image we are willing to fetch. */
    private static String filterCssUrls(String css, boolean allowRemoteImages) {
        if (css.toLowerCase(Locale.ROOT).indexOf("url") < 0) return css;
        Matcher m = CSS_URL.matcher(css);
        StringBuilder out = new StringBuilder(css.length());
        while (m.find()) {
            String inner = m.group(1).trim().replaceAll("^['\"]|['\"]$", "");
            String url = cleanUrl(inner);
            boolean keep = url != null && (isInlineData(url)
                    || (allowRemoteImages && (url.regionMatches(true, 0, "http:", 0, 5)
                    || url.regionMatches(true, 0, "https:", 0, 6))));
            String replacement = keep ? "url(\"" + url.replace("\"", "%22") + "\")" : "none";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------ document

    /**
     * Wraps a sanitised fragment in a standalone document for the iframe's srcdoc.
     * The CSP here is layer 3: even a defect in the tokeniser above cannot make the
     * frame fetch a tracking pixel while images are off, because default-src is 'none'.
     */
    static String wrapDocument(String fragment, boolean allowRemoteImages, String theme) {
        String imgSrc = allowRemoteImages ? "data: https: http:" : "data:";
        boolean dark = "dark".equalsIgnoreCase(theme);
        boolean auto = theme == null || theme.isBlank() || "auto".equalsIgnoreCase(theme);

        String base = dark ? darkVars() : lightVars();
        String autoBlock = auto
                ? "@media (prefers-color-scheme:dark){:root{" + darkVars() + "}}"
                : "";

        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "img-src " + imgSrc + "; style-src 'unsafe-inline'; font-src data:; "
                + "script-src 'none'; object-src 'none'; frame-src 'none'; "
                + "form-action 'none'; base-uri 'none'\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                + "<style>:root{" + base + "}" + autoBlock
                + "html,body{margin:0;padding:0}"
                + "body{background:var(--b);color:var(--t);"
                + "font:14.5px/1.68 system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif;"
                + "padding:2px 2px 24px;word-wrap:break-word;overflow-wrap:break-word}"
                + "a{color:var(--l)}"
                + "img{max-width:100%;height:auto}"
                + "table{max-width:100%}"
                + ".jc-plain{white-space:pre-wrap;font:14px/1.65 ui-monospace,\"Cascadia Mono\",Consolas,monospace;margin:0}"
                + ".jc-empty{color:var(--m);font-style:italic}"
                + ".jc-blocked-img{min-width:26px;min-height:26px;border:1px dashed var(--d);"
                + "border-radius:4px;background:var(--s)}"
                + "</style></head><body>" + fragment + "</body></html>";
    }

    // Colour literals rather than the console's CSS custom properties, because a
    // sandboxed frame is a separate document and inherits nothing from its parent.
    private static String lightVars() {
        return "--b:#ffffff;--t:#14212a;--l:#00697f;--m:#7b8d99;--d:#dde5e8;--s:#eef2f3;";
    }

    private static String darkVars() {
        return "--b:#121c22;--t:#e4ecf0;--l:#5fc0da;--m:#72858f;--d:#22323b;--s:#18242b;";
    }

    // ------------------------------------------------------------------ escaping

    private static void escapeText(String s, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            switch (c) {
                // An "&" that already opens an entity is left as written, otherwise
                // every "&nbsp;" and "R&amp;D" in real mail renders as literal source.
                case '&' -> out.append(startsEntity(s, i, to) ? "&" : "&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
    }

    private static boolean startsEntity(String s, int at, int to) {
        int j = at + 1;
        if (j < to && s.charAt(j) == '#') {
            j++;
            int radix = 10;
            if (j < to && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
                radix = 16;
                j++;
            }
            int ds = j;
            while (j < to && j - ds < 8 && Character.digit(s.charAt(j), radix) >= 0) j++;
            return j > ds && j < to && s.charAt(j) == ';';
        }
        int ns = j;
        while (j < to && j - ns < 32 && Character.isLetterOrDigit(s.charAt(j))) j++;
        return j > ns && j < to && s.charAt(j) == ';';
    }

    /** Every attribute this class emits is double quoted, so these five are enough. */
    private static void escapeAttr(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c >= 0x20 || c == '\t') out.append(c);
                }
            }
        }
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int limit = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?".indexOf(url.charAt(end - 1)) >= 0) end--;
        return url.substring(0, end);
    }
}
