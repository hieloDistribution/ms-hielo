package com.sales.order.auth.support;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

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
