package org.valarpirai;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Single-line log formatter for cleaner output
 */
public class SingleLineFormatter extends Formatter {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();

        // Timestamp
        sb.append(LocalDateTime.now().format(TIME_FORMATTER));
        sb.append(" ");

        // Level
        sb.append(String.format("%-7s", record.getLevel().getName()));
        sb.append(" ");

        // Message
        sb.append(formatMessage(record));

        // Exception if present
        if (record.getThrown() != null) {
            sb.append(" | ");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            record.getThrown().printStackTrace(pw);
            sb.append(sw.toString());
        }

        sb.append(System.lineSeparator());
        return sb.toString();
    }
}
