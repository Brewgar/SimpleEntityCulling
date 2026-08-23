package com.example.simpleentityculling;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point. SimpleEntityCulling is a client-side rendering mod:
 * nothing here touches entity simulation, ticking, or server behavior.
 */
public class SimpleEntityCulling implements ModInitializer {
    public static final String MOD_ID = "simpleentityculling";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[SimpleEntityCulling] initialized (client-side rendering optimization only).");
    }
}
