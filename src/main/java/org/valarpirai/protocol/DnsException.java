package org.valarpirai.protocol;

import lombok.Getter;

/**
 * A DNS failure that maps to a response code the client can be told about.
 */
@Getter
public class DnsException extends RuntimeException {
    public static final int FORMERR = 1;
    public static final int SERVFAIL = 2;

    private final int rcode;

    /**
     * @param rcode DNS response code to send back (FORMERR or SERVFAIL)
     * @param message what went wrong
     */
    public DnsException(int rcode, String message) {
        super(message);
        this.rcode = rcode;
    }
}
