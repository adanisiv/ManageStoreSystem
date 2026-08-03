package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.StoreChain;
import managestore.server.service.AuthService;

/** Shared state every {@link ClientHandler} thread needs a reference to. */
public class ServerContext {

    private final StoreChain storeChain;
    private final AuthService authService;
    private final Gson gson;

    public ServerContext(StoreChain storeChain, AuthService authService, Gson gson) {
        this.storeChain = storeChain;
        this.authService = authService;
        this.gson = gson;
    }

    public StoreChain getStoreChain() {
        return storeChain;
    }

    public AuthService getAuthService() {
        return authService;
    }

    public Gson getGson() {
        return gson;
    }
}
