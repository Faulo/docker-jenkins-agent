package net.slothsoft.jenkins.agentlauncher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class AgentSupervisor implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private AgentSupervisor(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static AgentSupervisor acquire(Path baseDirectory) throws IOException {
        FileChannel channel = FileChannel.open(
            baseDirectory.resolve("agent.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        );
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException("another managed Jenkins agent process is already running");
            }
            return new AgentSupervisor(channel, lock);
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw new IllegalStateException("another managed Jenkins agent process is already running", exception);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    void verifyHeld() {
        if (!lock.isValid()) {
            throw new IllegalStateException("the managed Jenkins agent process lock was lost");
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
