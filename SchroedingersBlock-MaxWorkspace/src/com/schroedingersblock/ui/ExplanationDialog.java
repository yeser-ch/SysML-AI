/**
 * Die grafische Benutzeroberfläche/Chat-Dialog für die Interaktion mit dem Plugin.
*/

package com.schroedingersblock.ui;

import com.schroedingersblock.api.ApiKeyManager;
import com.schroedingersblock.api.DummyExplanationService;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import javax.swing.*;
import java.awt.*;

public class ExplanationDialog extends JDialog {
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private JRadioButton basicBtn;
    private JRadioButton expertBtn;
    private String mode;
    private Element targetElement;

    private DummyExplanationService apiService = new DummyExplanationService();

    public ExplanationDialog(String mode, Element targetElement) {
        this.mode = mode;
        this.targetElement = targetElement;

        setTitle("Schrödinger's Block - SysML AI Analysis");
        setSize(580, 650);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        ensureApiKey();
        buildUI();

        String elementName = (targetElement != null) ? targetElement.getHumanName() : "Diagram Background / Unknown";
        addMessage("System", "<i>[DEBUG] Selected Target: " + elementName + "</i>");

        addMessage("System", "Generating initial SysML explanation...");
        fetchData(selectedMode(), true);
    }

    // ─── API key check ────────────────────────────────────────────────────────

    private void ensureApiKey() {
        if (ApiKeyManager.hasKey()) return;

        JPasswordField pwField = new JPasswordField(40);
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(new JLabel("Enter your Gemini API key:"), BorderLayout.NORTH);
        panel.add(pwField, BorderLayout.CENTER);
        panel.add(new JLabel("<html><small>Stored in " + ApiKeyManager.getKeyFilePath() + "</small></html>"), BorderLayout.SOUTH);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "Gemini API Key Required", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String key = new String(pwField.getPassword()).trim();
            if (!key.isEmpty()) {
                ApiKeyManager.saveKey(key);
            }
        }
    }

    // ─── UI construction ──────────────────────────────────────────────────────

    private void buildUI() {
        // Header: title + mode selector
        JPanel headerPanel = new JPanel(new BorderLayout(10, 5));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JLabel titleLabel = new JLabel("SysML Analysis");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 15));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        // Mode radio buttons
        basicBtn  = new JRadioButton("Basic");
        expertBtn = new JRadioButton("Expert");
        basicBtn.setSelected(true);

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(basicBtn);
        modeGroup.add(expertBtn);

        JButton reanalyzeBtn = new JButton("Re-analyze");
        reanalyzeBtn.addActionListener(e -> {
            clearChat();
            addMessage("System", "Re-analyzing with " + selectedMode() + " mode...");
            fetchData(selectedMode(), true);
        });

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        modePanel.add(new JLabel("Mode:"));
        modePanel.add(basicBtn);
        modePanel.add(expertBtn);
        modePanel.add(reanalyzeBtn);
        headerPanel.add(modePanel, BorderLayout.EAST);

        add(headerPanel, BorderLayout.NORTH);

        // Chat area
        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        scrollPane = new JScrollPane(chatPanel);
        add(scrollPane, BorderLayout.CENTER);

        // Follow-up input
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        inputField = new JTextField();
        JButton sendBtn = new JButton("Send");

        bottomPanel.add(new JLabel("Follow-up: "), BorderLayout.WEST);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendBtn, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);

        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
    }

    private String selectedMode() {
        return expertBtn != null && expertBtn.isSelected() ? "EXPERT" : "BASIC";
    }

    // ─── Chat actions ─────────────────────────────────────────────────────────

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            addMessage("User", text);
            inputField.setText("");
            addMessage("AI", "Processing...");
            fetchData(text, false);
        }
    }

    private void clearChat() {
        chatPanel.removeAll();
        chatPanel.revalidate();
        chatPanel.repaint();
        apiService = new DummyExplanationService(); // reset conversation history
    }

    // ─── Async data fetch ─────────────────────────────────────────────────────

    private void fetchData(String query, boolean isInitial) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                if (isInitial) {
                    return apiService.fetchExplanation(targetElement, query);
                } else {
                    return apiService.fetchFollowUp(query);
                }
            }

            @Override
            protected void done() {
                try {
                    removeLastMessage();
                    addMessage("AI", get());
                } catch (Exception e) {
                    addMessage("System", "Error: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    // ─── Chat rendering ───────────────────────────────────────────────────────

    private void addMessage(String sender, String message) {
        JPanel row = new JPanel(new BorderLayout());
        String html = "<html><div style='width:380px'><b>" + sender + ":</b><br>"
                + message.replace("\n", "<br>") + "</div></html>";
        JLabel label = new JLabel(html);
        label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        row.add(label, BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate();

        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void removeLastMessage() {
        int count = chatPanel.getComponentCount();
        if (count > 0) {
            chatPanel.remove(count - 1);
            chatPanel.revalidate();
            chatPanel.repaint();
        }
    }
}
