package com.jarurat.mailer.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a tracking hit came from a person or from a machine.
 *
 * The pixel endpoint cannot tell the difference on its own: Apple Mail Privacy
 * Protection pre-fetches every image on delivery, Gmail proxies and caches images
 * through ggpht.com, and corporate scanners fetch both images and links before the
 * recipient has even been shown the message. Counting all of those as opens is what
 * produces the 60% open rates nobody believes.
 *
 * Every verdict carries the reason that produced it, and the raw signals are kept on
 * the event row, so tightening these rules later means re-running the classifier over
 * history rather than discarding it.
 */
@Component
public class OpenClassifier {

    /**
     * Scanner, crawler and script tokens. These are matched as lowercase substrings
     * of the User-Agent. Kept deliberately broad: a false BOT understates the open
     * rate, which is the safe direction to be wrong in.
     */
    private static final List<String> SCANNER_TOKENS = List.of(
            // Security gateways that detonate mail before delivery
            "proofpoint", "urldefense", "safelinks", "barracuda", "mimecast", "messagelabs",
            "symantec", "forcepoint", "ironport", "trendmicro", "trend micro", "sophos",
            "fireeye", "cyren", "zscaler", "netskope", "fortinet", "fortimail", "sonicwall",
            "clearswift", "retarus", "hornetsecurity", "vadesecure", "spamtitan",
            "microsoft-defender", "defender for office", "atpimageproxy",
            // Generic HTTP clients, which no mail client ever is
            "curl/", "wget", "python-requests", "python-urllib", "aiohttp", "httpx",
            "go-http-client", "java/", "okhttp", "apache-httpclient", "libwww-perl",
            "guzzlehttp", "axios", "node-fetch", "restsharp", "powershell",
            // Headless browsers and scrapers
            "headlesschrome", "phantomjs", "puppeteer", "playwright", "selenium",
            "scrapy", "httrack",
            // Link unfurlers, which fetch on paste and are not the recipient reading
            "slackbot", "facebookexternalhit", "twitterbot", "linkedinbot", "whatsapp",
            "telegrambot", "discordbot", "skypeuripreview", "redditbot", "embedly",
            // Search and monitoring crawlers
            "googlebot", "bingbot", "yandexbot", "duckduckbot", "baiduspider",
            "ahrefsbot", "semrushbot", "mj12bot", "dotbot", "petalbot",
            "uptimerobot", "pingdom", "zabbix", "nagios", "statuscake", "site24x7",
            // Catch-alls, last so a more specific token wins the reason string
            "bot/", "bot;", " bot", "crawler", "spider", "scanner", "monitoring",
            "validator", "preview", "prefetch", "fetcher"
    );

    /** Mailbox providers that fetch remote images through their own cache. */
    private static final List<ProxyVendor> PROXY_VENDORS = List.of(
            new ProxyVendor("googleimageproxy", "Gmail"),
            new ProxyVendor("ggpht.com", "Gmail"),
            new ProxyVendor("yahoomailproxy", "Yahoo Mail"),
            new ProxyVendor("yahoo! mail proxy", "Yahoo Mail"),
            new ProxyVendor("proxy.mail.ru", "Mail.ru"),
            new ProxyVendor("mailproxy", "Mail proxy")
    );

    /**
     * 17.0.0.0/8 is Apple's own long-standing allocation and is safe to hardcode.
     * Apple's Mail Privacy Protection egress also runs through leased proxy nodes whose
     * ranges Apple publishes at mask-api.icloud.com/egress-ip-ranges.csv and rotates, so
     * those are NOT baked in here. Feed them in with analytics.appleNetworks when a
     * refreshed list is available. The User-Agent carries most of the weight regardless.
     */
    private static final List<String> BUILT_IN_APPLE_NETWORKS = List.of("17.0.0.0/8");

    /** Only these shapes are handed to InetAddress, so a hostile value can never trigger DNS. */
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:]{2,45}(%[0-9A-Za-z._-]{1,16})?");

    private final List<Cidr> appleNetworks;
    private final int prefetchWindowSeconds;

    public OpenClassifier(@Value("${analytics.prefetchWindowSeconds:3}") int prefetchWindowSeconds,
                          @Value("${analytics.appleNetworks:}") String extraAppleNetworks) {
        this.prefetchWindowSeconds = Math.max(0, prefetchWindowSeconds);

        List<Cidr> networks = new ArrayList<>();
        for (String cidr : BUILT_IN_APPLE_NETWORKS) addCidr(networks, cidr);
        if (extraAppleNetworks != null) {
            for (String cidr : extraAppleNetworks.split(",")) addCidr(networks, cidr);
        }
        this.appleNetworks = List.copyOf(networks);
    }

    public int getPrefetchWindowSeconds() { return prefetchWindowSeconds; }

    public int getAppleNetworkCount() { return appleNetworks.size(); }

    /** What the classifier decided, and enough context to render it in the console. */
    public record Verdict(OpenClassification classification, String client, String deviceType, String reason) {}

    // ------------------------------------------------------------------
    // Opens
    // ------------------------------------------------------------------

    /**
     * @param secondsSinceSent null when the send time is unknown, which disables the
     *                         timing signal rather than guessing.
     */
    public Verdict classifyOpen(String userAgent, String ip, Long secondsSinceSent) {
        String ua = userAgent == null ? "" : userAgent.trim().toLowerCase(Locale.ROOT);
        String client = detectClient(ua);
        String device = detectDevice(ua);
        boolean prefetchWindow = withinPrefetchWindow(secondsSinceSent);

        if (ua.isEmpty()) {
            return new Verdict(OpenClassification.BOT, client, device, "no user agent sent");
        }

        String scanner = matchScanner(ua);
        if (scanner != null) {
            return new Verdict(OpenClassification.BOT, client, device, "scanner user agent: " + scanner);
        }

        // Apple Mail with MPP switched off sends almost the same bare WebKit string as
        // the MPP proxy itself, so this is a judgement call, not a certainty. MPP is on
        // by default and the overwhelming majority of Apple Mail traffic is proxied, so
        // the bare string is treated as MPP and the corroborating signal is recorded.
        if (looksLikeAppleMpp(ua)) {
            boolean appleNet = isAppleNetwork(ip);
            String why = appleNet ? "apple mpp user agent from an Apple network"
                    : prefetchWindow ? "apple mpp user agent inside the prefetch window"
                    : "apple mpp user agent, no corroborating ip or timing signal";
            return new Verdict(OpenClassification.APPLE_MPP, "Apple Mail", device, why);
        }

        ProxyVendor vendor = matchProxy(ua);
        if (vendor != null) {
            // Gmail's proxy is not a prefetcher in the normal case: it fetches when the
            // message is actually rendered and then caches. Bucketing every Gmail open as
            // machine traffic would gut the open rate of any real list. Only a fetch that
            // lands within seconds of the send is genuinely a prefetch.
            if (prefetchWindow) {
                return new Verdict(OpenClassification.PROXY, vendor.client(), device,
                        vendor.client() + " proxy fetched inside the prefetch window");
            }
            return new Verdict(OpenClassification.HUMAN, vendor.client(), device,
                    vendor.client() + " proxy fetch after the prefetch window");
        }

        if (prefetchWindow) {
            return new Verdict(OpenClassification.PROXY, client, device,
                    "fetched " + secondsSinceSent + "s after send, faster than a person can open mail");
        }

        if (isAppleNetwork(ip)) {
            return new Verdict(OpenClassification.APPLE_MPP, client, device,
                    "fetched from an Apple network range");
        }

        return new Verdict(OpenClassification.HUMAN, client, device, "no machine signal matched");
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    /**
     * Clicks need their own rules. Apple MPP and image proxies never follow a link, but
     * link-rewriting gateways such as Proofpoint URL Defense and Microsoft SafeLinks do,
     * and they do it before the recipient sees the message.
     */
    public Verdict classifyClick(String userAgent, String ip, Long secondsSinceSent) {
        String ua = userAgent == null ? "" : userAgent.trim().toLowerCase(Locale.ROOT);
        String client = detectClient(ua);
        String device = detectDevice(ua);

        if (ua.isEmpty()) {
            return new Verdict(OpenClassification.BOT, client, device, "no user agent sent");
        }

        String scanner = matchScanner(ua);
        if (scanner != null) {
            return new Verdict(OpenClassification.BOT, client, device, "scanner user agent: " + scanner);
        }

        if (withinPrefetchWindow(secondsSinceSent)) {
            return new Verdict(OpenClassification.BOT, client, device,
                    "link followed " + secondsSinceSent + "s after send, that is a gateway not a reader");
        }

        if (isAppleNetwork(ip) && looksLikeAppleMpp(ua)) {
            return new Verdict(OpenClassification.BOT, client, device,
                    "bare Apple proxy agent following a link");
        }

        return new Verdict(OpenClassification.HUMAN, client, device, "no machine signal matched");
    }

    // ------------------------------------------------------------------
    // Signals
    // ------------------------------------------------------------------

    private boolean withinPrefetchWindow(Long secondsSinceSent) {
        return secondsSinceSent != null && secondsSinceSent >= 0 && secondsSinceSent <= prefetchWindowSeconds;
    }

    private static String matchScanner(String ua) {
        for (String token : SCANNER_TOKENS) {
            if (ua.contains(token)) return token.trim();
        }
        return null;
    }

    private static ProxyVendor matchProxy(String ua) {
        for (ProxyVendor vendor : PROXY_VENDORS) {
            if (ua.contains(vendor.token())) return vendor;
        }
        return null;
    }

    /**
     * The MPP proxy stops its User-Agent at "(KHTML, like Gecko)". A real Safari or
     * Chrome build always appends Version/ and Safari/, so the absence of the tail is
     * the tell.
     */
    private static boolean looksLikeAppleMpp(String ua) {
        if (ua.equals("mozilla/5.0")) return true;
        if (!ua.contains("applewebkit")) return false;
        return !ua.contains("version/") && !ua.contains("safari/")
                && !ua.contains("chrome") && !ua.contains("crios") && !ua.contains("firefox");
    }

    public boolean isAppleNetwork(String ip) {
        byte[] address = parseLiteral(ip);
        if (address == null) return false;
        for (Cidr network : appleNetworks) {
            if (network.contains(address)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Client and device naming, used for the "where it landed" breakdown
    // ------------------------------------------------------------------

    public static String detectClient(String lowerUa) {
        if (lowerUa == null || lowerUa.isEmpty()) return "Unknown";
        if (lowerUa.contains("googleimageproxy") || lowerUa.contains("ggpht.com")) return "Gmail";
        if (lowerUa.contains("yahoomailproxy") || lowerUa.contains("yahoo! mail")) return "Yahoo Mail";
        if (lowerUa.contains("thunderbird")) return "Thunderbird";
        if (lowerUa.contains("outlook") || lowerUa.contains("msoffice") || lowerUa.contains("microsoft office")) return "Outlook";
        if (lowerUa.contains("superhuman")) return "Superhuman";
        if (lowerUa.contains("edg/") || lowerUa.contains("edge/")) return "Edge";
        if (lowerUa.contains("crios")) return "Chrome";
        if (lowerUa.contains("firefox") || lowerUa.contains("fxios")) return "Firefox";
        if (lowerUa.contains("chrome")) return "Chrome";
        // Ordered after Chrome because every Chrome UA also carries the Safari token
        if (lowerUa.contains("applewebkit")) return "Apple Mail";
        return "Other";
    }

    public static String detectDevice(String lowerUa) {
        if (lowerUa == null || lowerUa.isEmpty()) return "Unknown";
        if (lowerUa.contains("ipad") || lowerUa.contains("tablet")) return "Tablet";
        if (lowerUa.contains("iphone") || lowerUa.contains("ipod") || lowerUa.contains("android")
                || lowerUa.contains("mobile")) return "Mobile";
        if (lowerUa.contains("macintosh") || lowerUa.contains("windows")
                || lowerUa.contains("x11") || lowerUa.contains("linux")) return "Desktop";
        return "Unknown";
    }

    // ------------------------------------------------------------------
    // Address handling
    // ------------------------------------------------------------------

    /** nginx sits in front, so an X-Forwarded-For chain may arrive as one string. */
    public static String firstAddress(String ip) {
        if (ip == null) return null;
        String trimmed = ip.trim();
        int comma = trimmed.indexOf(',');
        if (comma >= 0) trimmed = trimmed.substring(0, comma).trim();
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close > 1) trimmed = trimmed.substring(1, close);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Never let a non-literal reach InetAddress: getByName would resolve it, and a
     * hostile X-Forwarded-For value would turn the pixel endpoint into a DNS amplifier.
     */
    private static byte[] parseLiteral(String ip) {
        String candidate = firstAddress(ip);
        if (candidate == null) return null;
        // The colon test is load bearing: "abcdef" matches the IPv6 character class but
        // is a perfectly resolvable hostname, and getByName would go to DNS for it.
        boolean v4 = IPV4_LITERAL.matcher(candidate).matches();
        boolean v6 = candidate.indexOf(':') >= 0 && IPV6_LITERAL.matcher(candidate).matches();
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(candidate).getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static void addCidr(List<Cidr> into, String raw) {
        if (raw == null || raw.isBlank()) return;
        String[] parts = raw.trim().split("/");
        if (parts.length != 2) return;
        byte[] network = parseLiteral(parts[0]);
        if (network == null) return;
        try {
            int bits = Integer.parseInt(parts[1].trim());
            if (bits < 0 || bits > network.length * 8) return;
            into.add(new Cidr(network, bits));
        } catch (NumberFormatException ignored) {
            // A malformed range in configuration must not stop the app booting
        }
    }

    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address == null || address.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) return true;
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private record ProxyVendor(String token, String client) {}
}
