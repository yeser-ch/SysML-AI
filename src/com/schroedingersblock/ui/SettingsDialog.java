package com.schroedingersblock.ui;

import com.schroedingersblock.core.SchroedingersBlockPlugin;
import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

public class SettingsDialog extends JFrame {
    private JTextField apiKeyField;
    private JComboBox<String> defaultModeBox;

    public SettingsDialog() {
        setTitle("Schrödinger's Block - Settings");
        setSize(550, 200);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setAlwaysOnTop(true);
        setLayout(new BorderLayout(10, 10));

        buildUI();
        loadSettings();
    }

    private void buildUI() {
        // GridBagLayout für flexiblere Spaltenbreiten ---
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5); // Abstand zwischen den Elementen
        gbc.fill = GridBagConstraints.HORIZONTAL; // Elemente dehnen sich horizontal aus

        // Zeile 1: API Key
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 0.0; // Label wächst nicht
        formPanel.add(new JLabel("Gemini API Key:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0;
        gbc.weightx = 1.0; // Textfeld bekommt den restlichen Platz
        apiKeyField = new JTextField();
        apiKeyField.setToolTipText("Enter your personal Google Gemini API key here.");
        formPanel.add(apiKeyField, gbc);


        // Zeile 2: Default Detail Level
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.weightx = 0.0;
        formPanel.add(new JLabel("Default Detail Level:"), gbc);

        gbc.gridx = 1; gbc.gridy = 1;
        gbc.weightx = 1.0;
        defaultModeBox = new JComboBox<>(new String[]{"Basic", "Expert"});
        defaultModeBox.setToolTipText("Select the level of detail the AI should use by default when opening a new analysis.");
        formPanel.add(defaultModeBox, gbc);

        add(formPanel, BorderLayout.CENTER);

        // --- Buttons (Unverändert) ---
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
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        apiKeyField.setText(prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, ""));
        defaultModeBox.setSelectedItem(prefs.get(SchroedingersBlockPlugin.DEFAULT_MODE_PREF, "Basic"));
    }

    private void saveSettings() {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        prefs.put(SchroedingersBlockPlugin.API_KEY_PREF, apiKeyField.getText().trim());
        prefs.put(SchroedingersBlockPlugin.DEFAULT_MODE_PREF, (String) defaultModeBox.getSelectedItem());

        JOptionPane.showMessageDialog(this, "Settings saved successfully!", "Info", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }
}