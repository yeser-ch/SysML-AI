package com.schroedingersblock.ui;

import com.nomagic.magicdraw.actions.MDAction;
import java.awt.event.ActionEvent;

public class OpenSettingsAction extends MDAction {
    public OpenSettingsAction() {
        super("OPEN_SCHROEDINGER_SETTINGS", "Schrödinger's Block Settings...", null, null);

        setDescription("Configure API keys and default analysis settings for Schrödinger's Block.");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        new SettingsDialog().setVisible(true);
    }
}