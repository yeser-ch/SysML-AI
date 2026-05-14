package com.nomagic.magicdraw.plugins;

public abstract class Plugin {
    public abstract void init();
    public abstract boolean close();
    public abstract boolean isSupported();
}
