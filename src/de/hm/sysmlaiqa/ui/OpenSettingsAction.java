package de.hm.sysmlaiqa.ui;

import com.nomagic.magicdraw.actions.MDAction;
import java.awt.event.ActionEvent;

public class OpenSettingsAction extends MDAction {
    public OpenSettingsAction() {
        super("OPEN_SYSMLAIQA_SETTINGS", de.hm.sysmlaiqa.core.SysmlAiQaPlugin.DISPLAY_NAME + " – API key...", null, null);

        setDescription("Enter the Gemini API key for " + de.hm.sysmlaiqa.core.SysmlAiQaPlugin.DISPLAY_NAME + ".");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        new SettingsDialog().setVisible(true);
    }
}