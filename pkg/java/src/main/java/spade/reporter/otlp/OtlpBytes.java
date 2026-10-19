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

/**
 * Represents a value that OTLP-JSON marked as binary data rather than text.
 * For example, {@code {"bytesValue":"QQ=="}} contains the byte for the letter
 * A, encoded as Base64. In contrast, {@code {"stringValue":"QQ=="}} contains
 * the literal text "QQ==".
 *
 * <p>The decoder wraps the first value in OtlpBytes and leaves the second as
 * a String, so the reporter can tell them apart. This class stores "QQ=="
 * as received; it does not decode it to A. Calling {@code toString()} returns
 * the stored Base64 text.
 */
public final class OtlpBytes {

    final String base64;

    OtlpBytes(String base64) {
        this.base64 = base64 == null ? "" : base64;
    }

    @Override
    public String toString() {
        return base64;
    }
}
