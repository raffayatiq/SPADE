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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Holds the data decoded from one OpenTelemetry span, including its attributes,
 * events, and timestamps. This class does not interpret framework-specific
 * fields or decide how the span should be represented in provenance.
 */
public class OtlpSpan {
    public String traceId;
    public String spanId;
    public String parentSpanId;
    public String name;
    public long startTimeUnixNano;
    public long endTimeUnixNano;
    public int statusCode;   // 0 unset, 1 ok, 2 error
    /**
     * The reported span kind, decoded as CLIENT, INTERNAL, SERVER, PRODUCER,
     * or CONSUMER. An empty string means the kind was missing, unspecified,
     * or unrecognized.
     *
     * <p>Describes whether the span makes a request, handles one, or performs
     * another kind of work. For example, a model request may have
     * {@code spanKind=CLIENT}, while the provenance mapper classifies the same
     * span as {@code activity.kind=INFERENCE}. The two describe different
     * aspects of the span and are recorded separately.
     */
    public String spanKind;
    /**
     * Span attributes converted to strings for use in graph annotations.
     * Text is kept as reported; arrays and objects are written as JSON with
     * consistent formatting. The original decoded types are kept separately
     * in {@link #typedAttributes}.
     */
    public Map<String, String> attributes;
    /**
     * Span attributes stored as decoded values, such as numbers, booleans,
     * JSON arrays, and JSON objects. The mappers use these values to read
     * structured content, such as individual messages in a message array,
     * without parsing the string versions in {@link #attributes}.
     */
    public Map<String, Object> typedAttributes;
    public Map<String, String> resourceAttributes;
    public Map<String, Object> resourceTypedAttributes;
    // Name and version of the instrumentation that produced the span.
    // These help identify the telemetry source when debugging and may help
    // the compatibility layer select a framework-specific normalizer.
    public String scopeName;
    public String scopeVersion;
    // Schema URLs reported for the span data and resource attributes.
    // They identify the telemetry schemas used by the producer, which may
    // specify semantic-convention versions. Included in graph annotations
    // when reportInternals=true to help interpret telemetry from those versions.
    public String scopeSchemaUrl;
    public String resourceSchemaUrl;
    public JSONArray events;

    public String key() {
        return traceId + ":" + spanId;
    }

    /**
     * Returns the attributes of every event on this span whose name matches
     * {@code eventName}. Each matching event contributes one map, in the order
     * the events were reported. Values are decoded into their Java types,
     * including JSON arrays and objects for structured values.
     * Returns an empty list if no events match.
     */
    public List<Map<String, Object>> eventAttributes(String eventName) {
        List<Map<String, Object>> found = new ArrayList<>();
        if (events == null) {
            return found;
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && eventName.equals(event.optString("name", ""))) {
                found.add(attributesOf(event));
            }
        }
        return found;
    }

    /**
     * Reads one OTLP-JSON event's attribute list and returns a map from
     * attribute names to decoded values. Numbers, booleans, arrays, and
     * objects retain their types rather than being converted to strings.
     * Returns an empty map if the event has no attribute list.
     */
    public static Map<String, Object> attributesOf(JSONObject event) {
        return OtlpDecoder.attributesToTypedMap(event.optJSONArray("attributes"));
    }
}
