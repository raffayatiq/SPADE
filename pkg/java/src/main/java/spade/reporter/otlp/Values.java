/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2026 SRI International

 This program is free software: you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation, either version 3 of the License, or (at your option) any later
 version.
 --------------------------------------------------------------------------------
 */
package spade.reporter.otlp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Formats decoded OTLP values as strings for graph annotations and payload
 * hashing.
 *
 * <p>Text is kept unchanged. Arrays and objects are written as JSON with
 * consistent formatting, so equivalent structured payloads produce the same
 * string and hash. For example, changing the order of an object's keys does
 * not change its formatted value.
 *
 * <p>These string representations are separate from the decoded values.
 * Mappers can still read numbers, booleans, arrays, and objects directly
 * when interpreting the telemetry.
 */
public final class Values {

    private static final Logger logger = Logger.getLogger(Values.class.getName());

    private Values() {
    }

    /**
     * Returns a new map with the same keys and each value converted to a string
     * for use in graph annotations. Text stays unchanged; arrays and objects
     * use consistent JSON formatting. The original map is not modified.
     * Returns an empty map if the supplied map is null.
     */
    public static Map<String, String> stringView(Map<String, Object> values) {
        Map<String, String> view = new HashMap<>();
        if (values == null) {
            return view;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            view.put(entry.getKey(), valueToString(entry.getValue()));
        }
        return view;
    }

    /**
     * Converts a decoded value to a string for use in graph annotations.
     *
     * <p>Strings are returned unchanged. Arrays and objects are written as
     * JSON with sorted object keys and no extra formatting whitespace. Numbers
     * use a consistent decimal form; for example, numeric values 1.0 and 1E0
     * both become "1". A string containing "1.0" remains "1.0".
     *
     * <p>Binary values return their stored Base64 text. Null values return an
     * empty string.
     */
    public static String valueToString(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof OtlpBytes) {
            return ((OtlpBytes) value).base64;
        }
        if (value instanceof Number) {
            return canonicalNumber((Number) value);
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return canonical(value);
    }

    /**
     * Adds a key-value pair to a JSON object. If the JSON library rejects the
     * entry, logs the failure at FINE level and lets the caller continue
     * processing the span.
     *
     * <p>The JSON library bundled with SPADE requires callers to handle
     * exceptions from {@code JSONObject.put}. This helper handles them in one
     * place so each caller does not need its own try-catch block.
     */
    public static void putJson(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not encode value for key '" + key + "'", e);
        }
    }

    /**
     * Writes a decoded value as JSON using consistent formatting: object keys
     * are sorted, extra formatting whitespace is omitted, and equivalent
     * numbers use the same decimal form. Array order and text within strings
     * are preserved.
     *
     * <p>For example, objects parsed from {@code {"b":2,"a":1.0}} and
     * {@code {"a":1,"b":2}} both produce {@code {"a":1,"b":2}}. This lets
     * the reporter identify matching payloads by their content rather than
     * differences in JSON formatting.
     */
    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        writeCanonical(value, out);
        return out.toString();
    }

    private static void writeCanonical(Object value, StringBuilder out) {
        if (value == null || value == JSONObject.NULL) {
            out.append("null");
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            for (Iterator<?> it = object.keys(); it.hasNext();) {
                keys.add(String.valueOf(it.next()));
            }
            Collections.sort(keys);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(JSONObject.quote(keys.get(i))).append(':');
                writeCanonical(object.opt(keys.get(i)), out);
            }
            out.append('}');
        } else if (value instanceof Map) {
            JSONObject object = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                putJson(object, String.valueOf(entry.getKey()), entry.getValue());
            }
            writeCanonical(object, out);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            out.append('[');
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCanonical(array.opt(i), out);
            }
            out.append(']');
        } else if (value instanceof List) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) value) {
                array.put(item);
            }
            writeCanonical(array, out);
        } else if (value instanceof String) {
            out.append(JSONObject.quote((String) value));
        } else if (value instanceof OtlpBytes) {
            out.append(JSONObject.quote(((OtlpBytes) value).base64));
        } else if (value instanceof Number) {
            out.append(canonicalNumber((Number) value));
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else {
            out.append(JSONObject.quote(String.valueOf(value)));
        }
    }

    /**
     * Formats a number consistently, removing unnecessary trailing zeros and
     * expanding exponent notation. For example, 10, 10.0, and 1E1 all become
     * "10".
     *
     * <p>A model might propose tool arguments as {@code {"percent":10}}, while
     * the framework reports {@code {"percent":10.0}} when executing the tool.
     * Formatting both numbers as "10" lets the reporter recognize the arguments
     * as the same payload instead of creating separate Entities just because
     * one number was written with a decimal point.
     *
     * <p>Uses BigDecimal to format the number without first converting it to
     * a double, which could round a large integer. Its {@code toPlainString}
     * method writes the result without an exponent. Values such as NaN and
     * infinity, which BigDecimal cannot represent, keep their original string
     * form.
     */
    public static String canonicalNumber(Number value) {
        try {
            BigDecimal decimal = new BigDecimal(value.toString());
            BigDecimal stripped = decimal.stripTrailingZeros();
            // stripTrailingZeros leaves 0E-10 for zero; plain "0" is intended.
            if (stripped.compareTo(BigDecimal.ZERO) == 0) {
                return "0";
            }
            return stripped.toPlainString();
        } catch (NumberFormatException e) {
            return String.valueOf(value);
        }
    }

    /**
     * Attempts to parse the input as a JSON object, array, or quoted string.
     * Returns null for null or blank input, parsing failures, or extra content
     * after the parsed value. Surrounding whitespace is allowed.
     *
     * <p>JSONTokener reads one value without checking what follows it. For
     * example, it can read the object in {@code {"count":1} extra text} and
     * leave the remaining text unread. This method checks that nothing except
     * whitespace follows the value, so callers do not mistake part of a text
     * payload for its complete contents.
     *
     * <p>This also handles source code beginning with a Python triple-quoted
     * docstring: the parser may read the first two quotes as an empty JSON
     * string. The remaining-content check rejects that result, allowing the
     * caller to keep the original source code as text.
     */
    public static Object parseJson(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[' && first != '"') {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(trimmed);
            Object parsed = tokener.nextValue();
            if (tokener.nextClean() != 0) {
                return null;
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] decodeBase64(String value) {
        try {
            return java.util.Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            try {
                return java.util.Base64.getMimeDecoder().decode(value.trim());
            } catch (IllegalArgumentException nested) {
                return null;
            }
        }
    }

    public static long parseLong(String value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
