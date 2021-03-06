package com.timgroup.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

public class ReconnectingSocketOutputStream extends OutputStream {

    public static final int DEFAULT_TRY_COUNT = 3 * 60;
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final String host;
    private final int port;
    private final int tryCount;
    private final Selector selector;
    private final ByteBuffer drainBuffer;
    private SocketChannel channel;
    private ByteBuffer firstWrite;

    public ReconnectingSocketOutputStream(String host, int port, int tryCount, boolean replayFirstWrite) throws IOException {
        if (tryCount < 1) throw new IllegalArgumentException("tryCount must be at least one");
        this.host = host;
        this.port = port;
        this.tryCount = tryCount;
        this.selector = Selector.open();
        this.drainBuffer = ByteBuffer.allocate(32); // this is small because for ZMTP, we do not expect much data to come our way
        if (!replayFirstWrite) {
            this.firstWrite = EMPTY_BUFFER;
        }
        connect();
    }

    public ReconnectingSocketOutputStream(String host, int port, int tryCount) throws IOException {
        this(host, port, tryCount, false);
    }

    public ReconnectingSocketOutputStream(String host, int port, boolean replayFirstWrite) throws IOException {
        this(host, port, DEFAULT_TRY_COUNT, replayFirstWrite);
    }

    public ReconnectingSocketOutputStream(String host, int port) throws IOException {
        this(host, port, false);
    }

    private void connect() throws IOException {
        if (channel != null) {
            throw new IllegalStateException("already connected to " + channel);
        }

        SocketChannel channel = SocketChannel.open(new InetSocketAddress(host, port));
        Socket socket = channel.socket();
        socket.setKeepAlive(true); // might help
        socket.setTcpNoDelay(true);
        channel.configureBlocking(false);

        this.channel = channel;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b});
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        write(ByteBuffer.wrap(b, off, len));
    }

    /**
     * Writes bytes from the specified byte buffer starting at its position and
     * stopping at its limit to this output stream. This is pretty much exactly
     * equivalent to calling {{@link #write(byte[], int, int)} with
     * buffer.array(), buffer.position(), and buffer.remaining().
     *
     * The buffer's mark is set to its initial position as a side effect.
     */
    public void write(ByteBuffer buffer) throws IOException {
        buffer.mark();
        recordFirstWrite(buffer);
        List<IOException> exceptions = null;
        for (int i = 0; i < tryCount; ++i) {
            try {
                ensureOpen();
                buffer.reset();
                writeFully(buffer);
                return;
            } catch (IOException e) {
                if (exceptions == null) {
                    exceptions = new LinkedList<IOException>();
                }
                exceptions.add(e);
                closeQuietly();
            }
        }
        assert exceptions != null && !exceptions.isEmpty();
        throw new IOException("write failed after " + tryCount + " tries; exceptions = " + exceptions, exceptions.get(0));
    }

    private void recordFirstWrite(ByteBuffer buffer) {
        if (firstWrite == null) {
            firstWrite = copy(buffer);
        }
    }

    private static ByteBuffer copy(ByteBuffer buffer) {
        ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
        copy.put(buffer.asReadOnlyBuffer());
        copy.flip();
        return copy;
    }

    private void ensureOpen() throws IOException {
        if (channel == null || !channel.isOpen()) {
            try {
                reconnect();
            } catch (ConnectException e) {
                sleep(1000);
                throw e;
            }
        }
    }

    private void sleep(int millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            InterruptedIOException e2 = new InterruptedIOException();
            e2.initCause(e);
            throw e2;
        }
    }

    private void writeFully(ByteBuffer buffer) throws IOException, EOFException {
        while (buffer.hasRemaining()) {
            checkForRead();
            int written = channel.write(buffer);
            if (written == 0) {
                blockForWrite();
            } else if (written < 0) {
                throw new EOFException();
            }
        }
    }

    private void checkForRead() throws IOException {
        SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
        selector.selectNow();
        if (key.isReadable()) {
            drain();
        }
    }

    private void blockForWrite() throws IOException {
        SelectionKey key = channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        while (!key.isWritable()) {
            int selected = selector.select();
            if (selected == 0) {
                throw new IOException("failed to select");
            }
            if (key.isReadable()) {
                drain();
            }
        }
    }

    private void drain() throws IOException, EOFException {
        int read;
        while ((read = channel.read(drainBuffer)) > 0);
        if (read < 0) throw new EOFException();
    }

    public void reconnect() throws IOException {
        closeQuietly();
        connect();
        replayFirstWrite();
    }

    private void replayFirstWrite() throws IOException {
        if (firstWrite != null && firstWrite.remaining() > 0) {
            write(firstWrite);
        }
    }

    public void closeQuietly() {
        try {
            close();
        } catch (IOException e) {}
    }

    @Override
    public void close() throws IOException {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } finally {
            channel = null;
        }
    }

}
