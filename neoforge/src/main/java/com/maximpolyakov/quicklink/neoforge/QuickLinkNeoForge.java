package com.maximpolyakov.quicklink.neoforge;

import com.maximpolyakov.quicklink.QuickLink;
import net.neoforged.fml.common.Mod;

@Mod(QuickLink.MOD_ID)
public final class QuickLinkNeoForge {
    public QuickLinkNeoForge() {
        // Run our common setup.
        QuickLink.init();
    }
}
