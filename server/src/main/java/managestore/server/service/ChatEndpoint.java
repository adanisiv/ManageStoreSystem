package managestore.server.service;

import managestore.common.protocol.MessageType;

/**
 * Narrow "can push a message to this connected client" abstraction.
 * {@link ChatMediator} depends only on this interface, not on sockets or
 * {@code ClientHandler} directly, so the Mediator + queue logic can be unit
 * tested without opening a single socket. {@code ClientHandler} is the real
 * implementation used in production.
 */
public interface ChatEndpoint {

    void send(MessageType type, Object payload);
}
