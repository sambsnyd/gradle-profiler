package org.gradle.profiler.client.protocol;

import org.gradle.profiler.client.protocol.messages.Message;

import java.io.File;

public class ConnectionParameters extends Message {
    private final File gradleInstallation;

    public ConnectionParameters(File gradleInstallation) {
        this.gradleInstallation = gradleInstallation;
    }

    public File getGradleInstallation() {
        return gradleInstallation;
    }
}
