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

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads OTLP-JSON export lines and creates {@link OtlpSpan} objects.
 *
 * <p>Converts attribute values into the corresponding Java values rather than
 * turning everything into text. For example, {@code {"boolValue":true}}
 * becomes a Boolean, and {@code {"intValue":"42"}} becomes a BigInteger.
 * Arrays and key-value lists become JSON arrays and objects, with each nested
 * value decoded in the same way. The provenance mappers can then read these
 * values without having to parse OTLP's encoding themselves.
 */
public final class OtlpDecoder {

    private static final Logger logger = Logger.getLogger(OtlpDecoder.class.getName());

    private OtlpDecoder() {
    }

    /**
     * Reads one OTLP-JSON line and calls {@code sink} for each decoded span.
     * The caller supplies {@code sink} to process the span, for example by
     * mapping it to provenance.
     *
     * <p>Each line contains an export batch, which may include spans from
     * several traces. Spans belonging to the same trace may arrive in different
     * batches, so one line does not necessarily contain a complete trace.
     *
     * <p>If decoding a span or processing it through {@code sink} throws an
     * exception, logs the error and continues with the next span. If the line
     * cannot be parsed as JSON, logs the error and skips the line. These
     * exceptions are handled here so the caller can keep reading later lines,
     * even if a writer stopped halfway through a line.
     */
    public static void decode(String line, Consumer<OtlpSpan> sink) {
        if (line.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject tracesData = new JSONObject(line);
            JSONArray resourceSpansArray = tracesData.optJSONArray("resourceSpans");
            if (resourceSpansArray == null) {
                return;
            }
            for (int r = 0; r < resourceSpansArray.length(); r++) {
                JSONObject resourceSpans = resourceSpansArray.optJSONObject(r);
                if (resourceSpans == null) {
                    continue;
                }
                JSONObject resource = resourceSpans.optJSONObject("resource");
                Map<String, Object> resourceValues = attributesToTypedMap(
                        resource == null ? null : resource.optJSONArray("attributes"));
                // Identifies the telemetry schema used for this batch's resource
                // attributes, such as service.name. The URL may specify a
                // version of the OpenTelemetry semantic conventions.
                String resourceSchemaUrl = resourceSpans.optString("schemaUrl", "");
                JSONArray scopeSpansArray = resourceSpans.optJSONArray("scopeSpans");
                if (scopeSpansArray == null) {
                    continue;
                }
                for (int s = 0; s < scopeSpansArray.length(); s++) {
                    JSONObject scopeSpans = scopeSpansArray.optJSONObject(s);
                    if (scopeSpans == null) {
                        continue;
                    }
                    JSONObject scope = scopeSpans.optJSONObject("scope");
                    String scopeName = scope == null ? "" : scope.optString("name", "");
                    String scopeVersion = scope == null ? "" : scope.optString("version", "");
                    String scopeSchemaUrl = scopeSpans.optString("schemaUrl", "");
                    JSONArray spans = scopeSpans.optJSONArray("spans");
                    if (spans == null) {
                        continue;
                    }
                    for (int i = 0; i < spans.length(); i++) {
                        JSONObject spanJson = spans.optJSONObject(i);
                        if (spanJson == null) {
                            continue;
                        }
                        try {
                            sink.accept(toOtelSpan(spanJson, resourceValues, scopeName,
                                    scopeVersion, scopeSchemaUrl, resourceSchemaUrl));
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Failed to map span", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Skipping unparseable line (" + line.length() + " chars)", e);
        }
    }

    private static OtlpSpan toOtelSpan(JSONObject spanJson, Map<String, Object> resourceValues,
            String scopeName, String scopeVersion, String scopeSchemaUrl,
            String resourceSchemaUrl) {
        OtlpSpan span = new OtlpSpan();
        span.traceId = spanJson.optString("traceId", "");
        span.spanId = spanJson.optString("spanId", "");
        span.parentSpanId = spanJson.optString("parentSpanId", "");
        span.name = spanJson.optString("name", "");
        span.startTimeUnixNano = Values.parseLong(spanJson.optString("startTimeUnixNano", null), 0);
        span.endTimeUnixNano = Values.parseLong(spanJson.optString("endTimeUnixNano", null), 0);
        span.spanKind = decodeSpanKind(spanJson.opt("kind"));
        JSONObject status = spanJson.optJSONObject("status");
        span.statusCode = status == null ? 0 : decodeStatusCode(status.opt("code"));
        span.typedAttributes = attributesToTypedMap(spanJson.optJSONArray("attributes"));
        span.attributes = Values.stringView(span.typedAttributes);
        span.resourceTypedAttributes = resourceValues;
        span.resourceAttributes = Values.stringView(resourceValues);
        span.scopeName = scopeName;
        span.scopeVersion = scopeVersion;
        span.scopeSchemaUrl = scopeSchemaUrl;
        span.resourceSchemaUrl = resourceSchemaUrl;
        span.events = spanJson.optJSONArray("events");
        return span;
    }

    /**
     * Converts the reported SpanKind to a name such as {@code CLIENT} or
     * {@code SERVER}. Accepts numeric codes and names; for example, {@code 3},
     * {@code SPAN_KIND_CLIENT}, and {@code CLIENT} all become {@code CLIENT}.
     * Missing, unspecified, or unrecognized values become an empty string.
     *
     * <p>SpanKind describes the span's role in an operation, such as making a
     * request ({@code CLIENT}) or handling one ({@code SERVER}). It is separate
     * from {@code activity.kind}, which describes what the activity does,
     * such as model inference ({@code INFERENCE}) or tool execution ({@code TOOL}).
     */
    private static String decodeSpanKind(Object kind) {
        if (kind == null || kind == JSONObject.NULL) {
            return "";
        }
        String value = String.valueOf(kind).trim();
        if (value.startsWith("SPAN_KIND_")) {
            value = value.substring("SPAN_KIND_".length());
        }
        switch (value) {
            case "0":
            case "UNSPECIFIED":
                return "";
            case "1":
            case "INTERNAL":
                return "INTERNAL";
            case "2":
            case "SERVER":
                return "SERVER";
            case "3":
            case "CLIENT":
                return "CLIENT";
            case "4":
            case "PRODUCER":
                return "PRODUCER";
            case "5":
            case "CONSUMER":
                return "CONSUMER";
            default:
                return "";
        }
    }

    /**
     * Converts the reported span status to its numeric code: 0 for UNSET,
     * 1 for OK, and 2 for ERROR. Accepts numbers and names; for example,
     * {@code 2}, {@code STATUS_CODE_ERROR}, and {@code ERROR} all return 2.
     * Missing or unrecognized values return 0.
     */
    private static int decodeStatusCode(Object code) {
        if (code == null || code == JSONObject.NULL) {
            return 0;
        }
        String value = String.valueOf(code).trim();
        if (value.startsWith("STATUS_CODE_")) {
            value = value.substring("STATUS_CODE_".length());
        }
        switch (value) {
            case "1":
            case "OK":
                return 1;
            case "2":
            case "ERROR":
                return 2;
            default:
                return 0;
        }
    }

    /**
     * Converts an OTLP attribute list into a map from attribute names to decoded
     * values. Numbers remain numbers, booleans remain booleans, and structured
     * values become JSON arrays or objects.
     *
     * <p>For example, if {@code gen_ai.input.messages} is reported as an array
     * of message objects, the map contains a JSON array that the mappers can
     * read message by message. It is not converted to a text representation of
     * the array. Values reported as strings remain strings.
     */
    public static Map<String, Object> attributesToTypedMap(JSONArray attributes) {
        Map<String, Object> map = new HashMap<>();
        if (attributes == null) {
            return map;
        }
        for (int i = 0; i < attributes.length(); i++) {
            JSONObject attribute = attributes.optJSONObject(i);
            if (attribute == null) {
                continue;
            }
            String key = attribute.optString("key", null);
            if (key == null) {
                continue;
            }
            Object value = anyValueToObject(attribute.optJSONObject("value"));
            map.put(key, value == null ? "" : value);
        }
        return map;
    }

    /**
     * Converts one OTLP value into the corresponding Java value. OTLP uses
     * {@code AnyValue} to hold different types of data; a field such as
     * {@code stringValue} or {@code arrayValue} identifies the type to decode.
     *
     * <pre>
     * stringValue -&gt; String
     * boolValue   -&gt; Boolean
     * intValue    -&gt; BigInteger
     * doubleValue -&gt; Double
     * bytesValue  -&gt; OtlpBytes (stores the Base64 text)
     * arrayValue  -&gt; JSONArray
     * kvlistValue -&gt; JSONObject
     * </pre>
     *
     * <p>For arrays and key-value lists, calls this method again to decode each
     * element or entry. OTLP's type fields and {@code values} containers are
     * removed as part of decoding. For example,
     * {@code {"arrayValue":{"values":[{"stringValue":"hello"}]}}}
     * becomes a JSONArray containing just {@code "hello"}.
     */
    public static Object anyValueToObject(JSONObject value) {
        if (value == null) {
            return null;
        }
        if (value.has("stringValue")) {
            return value.optString("stringValue", "");
        }
        if (value.has("boolValue")) {
            return value.optBoolean("boolValue");
        }
        if (value.has("intValue")) {
            // OTLP-JSON encodes 64-bit integers as strings because JavaScript
            // cannot represent every 64-bit integer exactly as a number.
            // Parse the value as BigInteger to preserve the exact integer.
            String integer = value.optString("intValue", "0");
            try {
                return new BigInteger(integer.trim());
            } catch (NumberFormatException e) {
                return integer;
            }
        }
        if (value.has("doubleValue")) {
            return value.optDouble("doubleValue");
        }
        if (value.has("bytesValue")) {
            return new OtlpBytes(value.optString("bytesValue", ""));
        }
        JSONObject arrayValue = value.optJSONObject("arrayValue");
        if (arrayValue != null) {
            JSONArray source = arrayValue.optJSONArray("values");
            JSONArray decoded = new JSONArray();
            if (source != null) {
                for (int i = 0; i < source.length(); i++) {
                    Object item = anyValueToObject(source.optJSONObject(i));
                    decoded.put(item == null ? JSONObject.NULL : item);
                }
            }
            return decoded;
        }
        JSONObject kvlistValue = value.optJSONObject("kvlistValue");
        if (kvlistValue != null) {
            JSONArray source = kvlistValue.optJSONArray("values");
            JSONObject decoded = new JSONObject();
            if (source != null) {
                for (int i = 0; i < source.length(); i++) {
                    JSONObject entry = source.optJSONObject(i);
                    if (entry == null) {
                        continue;
                    }
                    String key = entry.optString("key", null);
                    if (key != null) {
                        Object item = anyValueToObject(entry.optJSONObject("value"));
                        try {
                            decoded.put(key, item == null ? JSONObject.NULL : item);
                        } catch (Exception e) {
                            logger.log(Level.FINE, "Could not decode OTLP key-value entry", e);
                        }
                    }
                }
            }
            return decoded;
        }
        return null;
    }
}
