package com.rikkahub.plugins.workspaceruntimes.sandbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.rikkahub.plugins.workspaceruntimes.conpty.ConPtySupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes host operating environment for available sandbox engines (Docker, Podman)
 * and interactive terminal emulation capabilities (ConPTY).
 */
public final class SandboxDetector {
    private static final Logger LOG = LoggerFactory.getLogger(SandboxDetector.class);

    private SandboxDetector() {}

    public static Optional<String> probeDocker() {
        return probeCommand("docker", "--version");
    }

    public static Optional<String> probePodman() {
        return probeCommand("podman", "--version");
    }

    public static SandboxCapabilities detect(SandboxConfig config) {
        boolean conPty = ConPtySupport.isSupported();
        String ptyMode = conPty ? "CONPTY" : "PIPE";

        Optional<String> dockerVer = probeDocker();
        Optional<String> podmanVer = probePodman();

        boolean dockerOk = dockerVer.isPresent();
        boolean podmanOk = podmanVer.isPresent();

        SandboxConfig.Driver driver = config.driver();
        String activeDriver = "LOCAL";
        String engine = "none";
        String version = "unavailable";

        if (driver == SandboxConfig.Driver.DOCKER && dockerOk) {
            activeDriver = "DOCKER";
            engine = "docker";
            version = dockerVer.get();
        } else if (driver == SandboxConfig.Driver.PODMAN && podmanOk) {
            activeDriver = "PODMAN";
            engine = "podman";
            version = podmanVer.get();
        } else if (driver == SandboxConfig.Driver.AUTO) {
            if (dockerOk) {
                activeDriver = "DOCKER";
                engine = "docker";
                version = dockerVer.get();
            } else if (podmanOk) {
                activeDriver = "PODMAN";
                engine = "podman";
                version = podmanVer.get();
            }
        }

        boolean containerAvailable = !"LOCAL".equals(activeDriver);
        boolean networkIsolated = containerAvailable && !config.networkEnabled();
        String image = containerAvailable ? config.containerImage() : "none";

        LOG.info("Detected sandbox environment: driver={}, engine={}, conPty={}, ptyMode={}",
                activeDriver, engine, conPty, ptyMode);

        return new SandboxCapabilities(
                activeDriver,
                containerAvailable,
                engine,
                version,
                image,
                networkIsolated,
                conPty,
                ptyMode
        );
    }

    private static Optional<String> probeCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        return Optional.of(line.trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // Command does not exist or execution timed out
        }
        return Optional.empty();
    }
}
