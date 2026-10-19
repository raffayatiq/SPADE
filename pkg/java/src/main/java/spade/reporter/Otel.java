/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2026 SRI International

 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see <http://www.gnu.org/licenses/>.
 --------------------------------------------------------------------------------
 */
package spade.reporter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import spade.core.AbstractReporter;
import spade.core.Settings;
import spade.reporter.otlp.OtlpDecoder;
import spade.reporter.otlp.OtlpSpan;
import spade.utility.HelperFunctions;

/**
 * Base class for reporters that read OpenTelemetry traces.
 *
 * <p>Loads the reporter configuration and manages a named pipe that receives
 * OTLP-JSON data from an OpenTelemetry Collector file exporter. It reads the
 * data one line at a time, decodes it using {@link OtlpDecoder}, and passes
 * each span to {@link #mapSpan}. Subclasses interpret the spans and map them
 * to provenance vertices and edges.
 *
 * @author Raffay Atiq
 */
public abstract class Otel extends AbstractReporter {

    private static final Logger logger = Logger.getLogger(Otel.class.getName());

    private String pipePath;
    private boolean createdPipe = false;
    private volatile boolean shutdown = false;

    /**
     * Applies the combined configuration settings before the reporter starts
     * reading spans. If this method throws an exception, the reporter will
     * not start.
     */
    protected abstract void applyConfig(Map<String, String> config);

    /** Maps a decoded OpenTelemetry span to provenance vertices and edges. */
    protected abstract void mapSpan(OtlpSpan span);

    @Override
    public boolean launch(String arguments) {
        pipePath = arguments;
        if (!loadConfig(arguments)) {
            return false;
        }
        try {
            File pipeFile = new File(pipePath);
            if (pipeFile.exists()) {
                // Reuse the existing path only if it is a named pipe.
                // A regular file may have been created by the collector
                // if it started before the reporter created the pipe.
                if (!Files.readAttributes(pipeFile.toPath(), BasicFileAttributes.class).isOther()) {
                    logger.log(Level.SEVERE, "Path exists but is not a named pipe: '" + pipePath + "'"
                            + " — remove it, start the reporter, then restart the collector");
                    return false;
                }
            } else {
                int exitCode = new ProcessBuilder("mkfifo", "-m", "0666", pipePath).start().waitFor();
                if (exitCode != 0) {
                    logger.log(Level.SEVERE, "mkfifo failed (exit " + exitCode + ") for path: '"
                            + pipePath + "' — directory may not be writable");
                    return false;
                }
                createdPipe = true;
            }
            new Thread(this::readLoop, this.getClass().getSimpleName() + "-Thread").start();
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not create pipe: '" + pipePath + "'", e);
            return false;
        }
    }

    /**
     * Loads configuration settings using SPADE's standard precedence rules.
     * Settings in the subclass's configuration file
     * ({@code cfg/spade.reporter.<SubclassName>.config}) override those in
     * {@code cfg/spade.reporter.Otel.config}. Any {@code key=value} settings
     * supplied in the reporter arguments override settings from either file.
     */
    private boolean loadConfig(String arguments) {
        try {
            Map<String, String> config = HelperFunctions.parseKeyValuePairsFrom(
                    arguments == null ? "" : arguments,
                    new String[]{
                            Settings.getDefaultConfigFilePath(getClass()),
                            Settings.getDefaultConfigFilePath(Otel.class)});
            applyConfig(config);
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load reporter configuration from "
                    + "cfg/spade.reporter.Otel.config", e);
            return false;
        }
    }

    @Override
    public boolean shutdown() {
        shutdown = true;
        if (createdPipe) {
            try {
                Files.deleteIfExists(new File(pipePath).toPath());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not remove pipe: '" + pipePath + "'", e);
            }
        }
        return true;
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new FileReader(pipePath))) {
            while (!shutdown) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null) {
                        handleLine(line);
                    }
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Pipe read failed", e);
        }
    }

    // Protected so a test subclass can pass an OTLP-JSON line directly to
    // this method, instead of sending it through a named pipe. The line is
    // decoded and its spans are processed just as they are during normal use.
    protected void handleLine(String line) {
        OtlpDecoder.decode(line, this::mapSpan);
    }
}
