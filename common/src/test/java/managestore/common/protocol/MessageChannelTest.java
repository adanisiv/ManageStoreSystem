package managestore.common.protocol;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips a real Message over a real loopback TCP socket, proving the
 * wire protocol (JSON-per-line via MessageChannel) works end to end without
 * needing the full server running.
 */
class MessageChannelTest {

    @Test
    void loginRequestAndResponseRoundTripOverSocket() throws Exception {
        Gson gson = new Gson();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            int port = serverSocket.getLocalPort();

            Future<LoginRequest> serverSideReceived = executor.submit(() -> {
                try (Socket accepted = serverSocket.accept();
                     MessageChannel serverChannel = new MessageChannel(accepted, gson)) {
                    Message request = serverChannel.receive();
                    LoginRequest loginRequest = request.readPayload(gson, LoginRequest.class);

                    Employee employee = new Employee("E1", "Dana Cohen", "123456789",
                            "050-1111111", "ACC-1", "BRANCH-1", Role.CASHIER);
                    serverChannel.send(Message.of(gson, MessageType.LOGIN_RESPONSE, LoginResponse.success(employee)));
                    return loginRequest;
                }
            });

            Future<LoginResponse> clientSideReceived = executor.submit(() -> {
                try (Socket socket = new Socket();
                     MessageChannel clientChannel = openWhenReady(socket, port, gson)) {
                    clientChannel.send(Message.of(gson, MessageType.LOGIN_REQUEST,
                            new LoginRequest("dana", "secret123")));
                    Message response = clientChannel.receive();
                    return response.readPayload(gson, LoginResponse.class);
                }
            });

            LoginRequest receivedOnServer = serverSideReceived.get(5, TimeUnit.SECONDS);
            LoginResponse receivedOnClient = clientSideReceived.get(5, TimeUnit.SECONDS);

            assertEquals("dana", receivedOnServer.getUsername());
            assertTrue(receivedOnClient.isSuccess());
            assertEquals("Dana Cohen", receivedOnClient.getEmployee().getFullName());
        } finally {
            executor.shutdownNow();
        }
    }

    private static MessageChannel openWhenReady(Socket socket, int port, Gson gson) throws IOException {
        socket.connect(new InetSocketAddress("localhost", port), 2000);
        return new MessageChannel(socket, gson);
    }

    /**
     * Reproduces the exact deadlock a client hits when it tries to log in a second time
     * (a retry after a failed attempt, for example): one thread is parked inside
     * {@code receive()} -- {@code BufferedReader.readLine()} -- waiting for the next
     * message that never comes, while another thread calls {@code close()} at the same
     * time. {@code BufferedReader.close()} and {@code readLine()} synchronize on the same
     * internal lock, and {@code readLine()} holds it for the whole time it's blocked
     * reading, so closing the reader before the socket used to make {@code close()} wait
     * forever for a lock the blocked read would never release.
     */
    @Test
    @Timeout(5)
    void closingWhileAnotherThreadIsBlockedInReceiveDoesNotDeadlock() throws Exception {
        Gson gson = new Gson();

        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            int port = serverSocket.getLocalPort();

            // A server side that accepts the connection and then never sends anything --
            // exactly what a real client sees between messages, and exactly what makes
            // receive() block.
            Thread acceptThread = new Thread(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Thread.sleep(10_000);
                } catch (IOException | InterruptedException ignored) {
                    // test is tearing down
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            Socket clientSocket = new Socket();
            MessageChannel channel = openWhenReady(clientSocket, port, gson);

            Thread receiverThread = new Thread(() -> {
                try {
                    channel.receive();
                } catch (IOException expected) {
                    // Exactly what should happen once close() runs below.
                }
            }, "test-reader");
            receiverThread.start();

            // Give the receiver thread time to actually enter the blocking read, so this
            // test exercises the real race instead of closing before receive() even starts.
            Thread.sleep(300);

            // Before the fix, this line would hang forever (caught here by @Timeout(5)).
            channel.close();

            receiverThread.join(2000);
            assertFalse(receiverThread.isAlive(),
                    "the blocked reader thread should have been released by close()");
        }
    }
}
