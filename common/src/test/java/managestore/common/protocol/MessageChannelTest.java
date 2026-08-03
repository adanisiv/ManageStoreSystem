package managestore.common.protocol;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
