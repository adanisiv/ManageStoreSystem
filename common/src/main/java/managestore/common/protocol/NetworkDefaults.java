package managestore.common.protocol;

/** Shared between client and server so neither module needs a compile-time dependency on the other. */
public final class NetworkDefaults {

    public static final int DEFAULT_PORT = 5050;

    private NetworkDefaults() {
    }
}
