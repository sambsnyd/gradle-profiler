package org.gradle.profiler.client.protocol;

import org.gradle.profiler.client.protocol.messages.*;
import org.gradle.profiler.client.protocol.messages.StudioRequest.StudioRequestType;
import org.gradle.profiler.client.protocol.messages.StudioSyncRequestCompleted.StudioSyncRequestResult;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Serializer {
    private static final Object NULL = new Object();
    private final String peerName;
    private final Connection connection;

    public Serializer(String peerName, Connection connection) {
        this.peerName = peerName;
        this.connection = connection;
    }

    public void send(SyncStarted message) {
        try {
            connection.writeByte((byte) 1);
            connection.writeInt(message.getId());
            connection.flush();
        } catch (IOException e) {
            throw couldNotWrite(e);
        }
    }

    public void send(SyncCompleted message) {
        try {
            connection.writeByte((byte) 2);
            connection.writeInt(message.getId());
            connection.writeLong(message.getDurationMillis());
            connection.flush();
        } catch (IOException e) {
            throw couldNotWrite(e);
        }
    }

    public void send(SyncParameters message) {
        try {
            connection.writeByte((byte) 3);
            connection.writeStrings(message.getGradleArgs());
            connection.writeStrings(message.getJvmArgs());
            connection.flush();
        } catch (IOException e) {
            throw couldNotWrite(e);
        }
    }

    public void send(ConnectionParameters message) {
        try {
            connection.writeByte((byte) 4);
            connection.writeString(message.getGradleInstallation().getPath());
            connection.flush();
        } catch (IOException e) {
            throw couldNotWrite(e);
        }
    }

    public void send(StudioRequest message) {
        try {
            connection.writeByte((byte) 5);
            connection.writeInt(message.getId());
            connection.writeString(message.getType().toString());
            connection.flush();
        } catch (IOException e) {
            throw couldNotWrite(e);
        }
    }

    public void send(StudioSyncRequestCompleted message) {
        try {
            connection.writeByte((byte) 6);
            connection.writeInt(message.getId());
            connection.writeLong(message.getDurationMillis());
            connection.writeString(message.getResult().toString());
            connection.flush();
        } catch (IOException e) {
            throw couldNotWrite(e);
        }
    }

    private RuntimeException couldNotWrite(IOException e) {
        return new RuntimeException(String.format("Could not write to %s.", peerName), e);
    }

    public <T extends Message> T receive(Class<T> type, Duration timeout) {
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                Object result;
                try {
                    result = receive();
                } catch (RuntimeException e) {
                    result = e;
                }
                queue.put(result);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        reader.start();
        Object result;
        try {
            result = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) {
                reader.interrupt();
                throw new IllegalStateException(String.format("Timeout waiting to receive message from %s.", peerName));
            }
            reader.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (result instanceof RuntimeException) {
            throw (RuntimeException) result;
        }
        if (result == NULL) {
            throw new IllegalStateException(String.format("Connection to %s has closed.", peerName));
        }
        return type.cast(result);
    }

    private Object receive() {
        try {
            byte tag;
            try {
                tag = connection.readByte();
            } catch (EOFException e) {
                // Disconnected
                return NULL;
            }
            switch (tag) {
                case 1:
                    int startId = connection.readInt();
                    return new SyncStarted(startId);
                case 2:
                    int completeId = connection.readInt();
                    long durationMillis = connection.readLong();
                    return new SyncCompleted(completeId, durationMillis);
                case 3:
                    List<String> gradleArgs = connection.readStrings();
                    List<String> jvmArgs = connection.readStrings();
                    return new SyncParameters(gradleArgs, jvmArgs);
                case 4:
                    String gradleHome = connection.readString();
                    return new ConnectionParameters(new File(gradleHome));
                case 5:
                    int syncId = connection.readInt();
                    StudioRequestType requestType = StudioRequestType.valueOf(connection.readString());
                    return new StudioRequest(syncId, requestType);
                case 6:
                    int syncRequestCompletedId = connection.readInt();
                    long syncRequestCompletedDurationMillis = connection.readLong();
                    StudioSyncRequestResult result = StudioSyncRequestResult.valueOf(connection.readString());
                    return new StudioSyncRequestCompleted(syncRequestCompletedId, syncRequestCompletedDurationMillis, result);
                default:
                    throw new RuntimeException(String.format("Received unexpected message from %s.", peerName));
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format("Could not read from %s.", peerName), e);
        }
    }
}
