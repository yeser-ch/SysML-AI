package com.schroedingersblock.ui;

import com.nomagic.magicdraw.actions.MDAction;
import java.awt.event.ActionEvent;

public class OpenSettingsAction extends MDAction {
    public OpenSettingsAction() {
        super("OPEN_SCHROEDINGER_SETTINGS", com.schroedingersblock.core.SchroedingersBlockPlugin.DISPLAY_NAME + " – API key...", null, null);

        setDescription("Enter the Gemini API key for " + com.schroedingersblock.core.SchroedingersBlockPlugin.DISPLAY_NAME + ".");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        new SettingsDialog().setVisible(true);
    }
}