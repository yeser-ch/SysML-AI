package de.hm.sysmlaiqa.ui;

import de.hm.sysmlaiqa.core.SysmlAiQaPlugin;
import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

/**
 * Nur noch für den API-Key. Alle anderen Einstellungen (Modell, Grounding, Stil, Temperatur …)
 * stehen jetzt direkt im "Ask AI about the model"-Fenster.
 */
public class SettingsDialog extends JDialog {
    private JPasswordField apiKeyField;

    public SettingsDialog() {
        super(ExplanationDialog.catiaMainFrame(), Dialog.ModalityType.MODELESS); // über CATIA, nicht über anderen Programmen
        setTitle(SysmlAiQaPlugin.DISPLAY_NAME + " – API key");
        setSize(520, 170);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        buildUI();
        loadSettings();
    }

    private void buildUI() {
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 5, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Gemini API key:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        apiKeyField = new JPasswordField();
        apiKeyField.setToolTipText("Your personal Google Gemini API key (saved locally on this computer).");
        formPanel.add(apiKeyField, gbc);

        JCheckBox show = new JCheckBox("Show");
        show.addActionListener(e -> apiKeyField.setEchoChar(show.isSelected() ? (char) 0 : '•'));
        gbc.gridx = 2; gbc.weightx = 0.0;
        formPanel.add(show, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
        JLabel hint = new JLabel("Model, grounding and all other options are set in the \"Ask AI about the model\" window.");
        hint.setForeground(Color.GRAY);
        formPanel.add(hint, gbc);
        add(formPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        saveBtn.addActionListener(e -> saveSettings());
        cancelBtn.addActionListener(e -> dispose());
        buttonPanel.add(cancelBtn);
        buttonPanel.add(saveBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void loadSettings() {
        Preferences prefs = Preferences.userNodeForPackage(SysmlAiQaPlugin.class);
        apiKeyField.setText(prefs.get(SysmlAiQaPlugin.API_KEY_PREF, ""));
    }

    private void saveSettings() {
        Preferences prefs = Preferences.userNodeForPackage(SysmlAiQaPlugin.class);
        prefs.put(SysmlAiQaPlugin.API_KEY_PREF, new String(apiKeyField.getPassword()).trim());
        JOptionPane.showMessageDialog(this, "API key saved.", "Info", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }
}
