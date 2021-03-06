package com.timgroup.io;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ReconnectingSocketOutputStreamTest {

    private static final Random RANDOM = new Random();

    @Rule
    public final TestRule timeout = Timeout.millis(10000);

    private final int port = 1024 + RANDOM.nextInt(65536 - 1024);
    private ServerSocket serverSocket;

    @Before
    public void closeServerSocket() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    @Test
    public void reportsFailureToConnect() throws Exception {
        try {
            new ReconnectingSocketOutputStream("localhost", port);
            fail("should have thrown ConnectException");
        } catch (ConnectException e) {
            // expected
        }
    }

    @Test
    public void writesBytesToASocket() throws Exception {
        serverSocket = new ServerSocket(port);

        Future<String> line = readLine(serverSocket);

        ReconnectingSocketOutputStream out = new ReconnectingSocketOutputStream("localhost", port);
        out.write("hello\n".getBytes());
        out.close();

        assertEquals("hello", line.get(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void reconnectsIfNecessaryOnWrite() throws Exception {
        serverSocket = new ServerSocket(port);

        Future<Socket> socket = connect(serverSocket);

        ReconnectingSocketOutputStream out = new ReconnectingSocketOutputStream("localhost", port);

        socket.get(100, TimeUnit.MILLISECONDS).close();
        Future<String> line = readLine(serverSocket);

        out.write("hello\n".getBytes());
        out.close();

        assertEquals("hello", line.get(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void blocksIfNecessaryOnWrite() throws Exception {
        serverSocket = new ServerSocket(port);
        byte[] stuffing = ByteArrayUtils.fill(1024, (byte) '\n');

        Future<String> line = eventuallyReadFirstNonBlankLine(serverSocket, 2500);

        ReconnectingSocketOutputStream out = new ReconnectingSocketOutputStream("localhost", port);

        for (int i = 0; i < 100000; ++i) {
            out.write(stuffing);
        }
        out.write("hello\n".getBytes());
        out.close();

        assertEquals("hello", line.get(2700, TimeUnit.MILLISECONDS));
    }

    @Test
    public void blocksIfNecessaryOnReconnect() throws Exception {
        serverSocket = new ServerSocket(port);

        Future<Socket> socket = connect(serverSocket);

        ReconnectingSocketOutputStream out = new ReconnectingSocketOutputStream("localhost", port);

        socket.get(100, TimeUnit.MILLISECONDS).close();
        serverSocket.close();
        Future<String> line = eventuallyBindAcceptAndReadLine(port, 500);

        out.write("hello\n".getBytes());
        out.close();

        assertEquals("hello", line.get(600, TimeUnit.MILLISECONDS));
    }

    @Test
    public void reconnectsEvenIfBlockedOnWrite() throws Exception {
        serverSocket = new ServerSocket(port);
        byte[] stuffing = ByteArrayUtils.fill(1024, (byte) '\n');

        Future<String> line = eventuallyAcceptCloseAcceptAgainAndFinallyReadFirstNonBlankLine(serverSocket, 2500);

        ReconnectingSocketOutputStream out = new ReconnectingSocketOutputStream("localhost", port);

        for (int i = 0; i < 10000; ++i) {
            out.write(stuffing);
        }
        out.write("hello\n".getBytes());
        out.close();

        assertEquals("hello", line.get(2700, TimeUnit.MILLISECONDS));
    }

    @Test
    public void canReplayTheFirstWriteOnReconnect() throws Exception {
        serverSocket = new ServerSocket(port);

        ReconnectingSocketOutputStream out = new ReconnectingSocketOutputStream("localhost", port, true);

        Future<String> firstLine = readLine(serverSocket);
        out.write("hello ".getBytes());
        out.write("world\n".getBytes());
        assertEquals("hello world", firstLine.get());

        Future<String> secondLine = readLine(serverSocket);
        out.write("kitty\n".getBytes());
        assertEquals("hello kitty", secondLine.get());

        out.close();
    }

    private Future<String> readLine(final ServerSocket serverSocket) {
        return Executors.newSingleThreadExecutor().submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Socket socket = serverSocket.accept();
                try {
                    return readLine(socket);
                } finally {
                    socket.close();
                }
            }
        });
    }

    private Future<Socket> connect(final ServerSocket serverSocket) {
        return Executors.newSingleThreadExecutor().submit(new Callable<Socket>() {
            @Override
            public Socket call() throws Exception {
                return serverSocket.accept();
            }
        });
    }

    private Future<String> eventuallyReadFirstNonBlankLine(final ServerSocket serverSocket, final int delay) {
        return Executors.newSingleThreadExecutor().submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Socket socket = serverSocket.accept();
                Thread.sleep(delay);
                try {
                    return readFirstNonBlankLine(socket);
                } finally {
                    socket.close();
                }
            }
        });
    }

    private Future<String> eventuallyBindAcceptAndReadLine(final int port, final int delay) {
        return Executors.newSingleThreadExecutor().submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Thread.sleep(delay);
                serverSocket = new ServerSocket(port);
                Socket socket = serverSocket.accept();
                try {
                    return readLine(socket);
                } finally {
                    socket.close();
                }
            }
        });
    }

    private Future<String> eventuallyAcceptCloseAcceptAgainAndFinallyReadFirstNonBlankLine(final ServerSocket serverSocket, final int delay) {
        return Executors.newSingleThreadExecutor().submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Thread.sleep(delay);
                Socket firstSocket = serverSocket.accept();
                try {
                    readLine(firstSocket); // just to demonstrate we're connected
                }
                finally {
                    firstSocket.close();
                }
                Socket secondSocket = serverSocket.accept();
                try {
                    return readFirstNonBlankLine(secondSocket);
                } finally {
                    secondSocket.close();
                }
            }
        });
    }

    private String readLine(Socket socket) throws IOException {
        return readerFrom(socket).readLine();
    }

    private String readFirstNonBlankLine(Socket socket) throws IOException {
        BufferedReader in = readerFrom(socket);
        String line;
        while ((line = in.readLine()) != null && line.equals(""));
        return line;
    }

    private BufferedReader readerFrom(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }
}
