package com.jarurat.mailer.mail;

/**
 * Everything that can go wrong talking to Stalwart surfaces as this. Callers get
 * one type to catch and a kind they can map straight onto an HTTP status, rather
 * than having to tell an IOException apart from a JMAP method error apart from a
 * JSON parse failure.
 */
public class MailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Socket, TLS handshake or timeout. The mail server is unreachable or too slow. */
        TRANSPORT,
        /** Stalwart rejected the credentials, or we never held any. Ask the user to sign in to mail. */
        AUTH,
        /** A JMAP method answered with an error, or a set refused one object. See errorType. */
        METHOD,
        /** The mailbox, folder or message asked for does not exist. */
        NOT_FOUND,
        /** We sent something malformed, or Stalwart answered something we cannot parse. */
        PROTOCOL
    }

    private final Kind kind;

    /** The JMAP error type, e.g. "invalidArguments" or "notFound". Null for transport failures. */
    private final String errorType;

    public MailException(Kind kind, String message) {
        this(kind, null, message, null);
    }

    public MailException(Kind kind, String message, Throwable cause) {
        this(kind, null, message, cause);
    }

    public MailException(Kind kind, String errorType, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.errorType = errorType;
    }

    public Kind getKind() { return kind; }
    public String getErrorType() { return errorType; }

    /** True when re-authenticating could fix it, so the UI knows to prompt instead of showing an error. */
    public boolean isAuthFailure() { return kind == Kind.AUTH; }
}
