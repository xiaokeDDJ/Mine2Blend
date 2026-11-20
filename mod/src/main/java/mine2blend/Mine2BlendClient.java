package mine2blend;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mine2BlendClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("mine2blend");
    public static Config CONFIG = new Config();

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load();
        Selection.init();
        BridgeServer.start(CONFIG.port);
    }
}
