package mineblend;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MineBlendClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("mineblend");
    public static Config CONFIG = new Config();

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load();
        Selection.init();
        BridgeServer.start(CONFIG.port);
    }
}
