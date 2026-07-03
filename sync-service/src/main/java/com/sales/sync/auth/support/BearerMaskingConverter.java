package com.sales.sync.auth.support;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback conversion word that replaces any {@code Authorization: Bearer <token>}
 * substring in a log message with {@code ***}.
 */
public class BearerMaskingConverter extends ClassicConverter {

    private static final Pattern BEARER =
            Pattern.compile("(?i)(authorization[\\s:]+bearer\\s+)[^\\s]+");

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getMessage();
        if (message == null) return "";
        return BEARER.matcher(message).replaceAll("$1***");
    }
}
