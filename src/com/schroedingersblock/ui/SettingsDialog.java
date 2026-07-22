package com.schroedingersblock.ui;

import com.schroedingersblock.core.SchroedingersBlockPlugin;
import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

public class SettingsDialog extends JFrame {
    private JTextField apiKeyField;
    private JComboBox<String> defaultModeBox;
    private JCheckBox groundingBox; // (Niko) Schalter für Google-Search-Grounding

    public SettingsDialog() {
        setTitle("Schrödinger's Block - Settings");
        setSize(550, 240); // (Niko) Platz für Grounding-Schalter
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

        // Zeile 3: Google Search Grounding (Niko)
        gbc.gridx = 0; gbc.gridy = 2; // (Niko)
        gbc.weightx = 0.0; // (Niko)
        formPanel.add(new JLabel("Google Search Grounding (Free: nur mit 2.5):"), gbc); // (Niko)

        gbc.gridx = 1; gbc.gridy = 2; // (Niko)
        gbc.weightx = 1.0; // (Niko)
        groundingBox = new JCheckBox(); // (Niko)
        groundingBox.setToolTipText("Auf dem Free Tier funktioniert Grounding nur mit gemini-2.5. Mit 3.5/3.1 liefert die API 429. Das Tool ist auf den Free Tier ausgelegt — Grounding mit 3.5/3.1 ist nicht vorgesehen."); // (Niko)
        formPanel.add(groundingBox, gbc); // (Niko)

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
        groundingBox.setSelected(prefs.getBoolean(SchroedingersBlockPlugin.GROUNDING_PREF, true)); // (Niko) Default an
    }

    private void saveSettings() {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        prefs.put(SchroedingersBlockPlugin.API_KEY_PREF, apiKeyField.getText().trim());
        prefs.put(SchroedingersBlockPlugin.DEFAULT_MODE_PREF, (String) defaultModeBox.getSelectedItem());
        prefs.putBoolean(SchroedingersBlockPlugin.GROUNDING_PREF, groundingBox.isSelected()); // (Niko)

        JOptionPane.showMessageDialog(this, "Settings saved successfully!", "Info", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }
}