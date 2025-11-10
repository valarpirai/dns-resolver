package org.valarpirai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DNS Header structure (12 bytes)
 * <pre>
 *  0  1  2  3  4  5  6  7  8  9  0  1  2  3  4  5
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                      ID                       |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE   |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    QDCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    ANCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    NSCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    ARCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DnsHeader {
    private int id;              // 16-bit identifier
    private boolean qr;          // Query/Response flag (0=query, 1=response)
    private int opcode;          // Operation code
    private boolean aa;          // Authoritative Answer
    private boolean tc;          // Truncation
    private boolean rd;          // Recursion Desired
    private boolean ra;          // Recursion Available
    private int rcode;           // Response code
    private int qdcount;         // Question count
    private int ancount;         // Answer count
    private int nscount;         // Name Server count
    private int arcount;         // Additional Records count
}
