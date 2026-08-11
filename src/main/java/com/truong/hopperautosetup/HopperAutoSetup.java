package com.truong.hopperautosetup;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class HopperAutoSetup extends MeteorAddon {
    public static final Color COLOR = new Color(145, 61, 226);

    @Override
    public void onInitialize() {
        Modules.get().add(new HopperAutoSetupModule());
    }

    @Override
    public String getPackage() {
        return "com.truong.hopperautosetup";
    }

    @Override
    public String getWebsite() {
        return "https://github.com/MeteorDevelopment/meteor-addon-template";
    }
}
