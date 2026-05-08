/**
 * Die grafische Benutzeroberfläche/Chat-Dialog für die Interaktion mit dem Plugin.
*/

package com.schroedingersblock.ui;

import com.schroedingersblock.api.DummyExplanationService;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import javax.swing.*;
import java.awt.*;

 //Pop-up-Fenster (JDialog)
public class ExplanationDialog extends JDialog {
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private String mode;
    private Element targetElement;

    // Hier wird die LLM Logik angebunden
    private DummyExplanationService apiService = new DummyExplanationService();

    public ExplanationDialog(String mode, Element targetElement) {
        this.mode = mode;
        this.targetElement = targetElement;

        setTitle("Schrödinger's Block - SYSML AI Analysis");
        setSize(550, 600);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        buildUI();

        // --- DEBUG NACHRICHT ---
        // Wenn ein Element da ist, holen wir seinen Namen. Wenn nicht, haben wir ins Leere geklickt.
        String elementName = (targetElement != null) ? targetElement.getHumanName() : "Diagram Background / Unknown";
        addMessage("System", "<i>[DEBUG] Selected Target: " + elementName + "</i>");
        // -----------------------

        // Start-Anfrage beim Öffnen des Fensters
        addMessage("System", "Generating initial SysML explanation...");
        fetchData(mode, true);
    }

    private void buildUI() {
        JLabel header = new JLabel(" SysML Analysis: " + mode);
        header.setFont(new Font("Arial", Font.BOLD, 16));
        add(header, BorderLayout.NORTH);

        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        scrollPane = new JScrollPane(chatPanel);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        inputField = new JTextField();
        JButton sendBtn = new JButton("Send");

        bottomPanel.add(new JLabel("Follow-up: "), BorderLayout.WEST);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendBtn, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);

        // wenn "Send" geklickt oder Enter gedrückt wird:
        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
    }
     //"Send" geklickt:
    private void sendMessage() {
        String text = inputField.getText().trim(); //Text wird ausgelesen und .trim() schneidet Leerzeichen am Anfang und Ende ab
        if (!text.isEmpty()) {
            addMessage("User", text); // Nachricht als "User" in den Chat
            inputField.setText(""); // Textfeld leeren
            addMessage("AI", "Processing..."); //Platzhalter-Nachricht
            fetchData(text, false); //Datenabfrage bei KI HIER ERSETZTEN
        }
    }
    //Datenabfragen Multithreating (mit Hintergrund-Pfaden)
    private void fetchData(String query, boolean isInitial) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() { // als Hintergrund-Pfad
                try { Thread.sleep(4000); } catch (Exception e) {} // DUMMYLadezeit simulieren SPÄTER ENTFERNEN

                // Datenübergabe an die API LLM
                if (isInitial) {
                    return apiService.fetchExplanation(targetElement,mode);
                } else {
                    return apiService.fetchFollowUp(query); //Folgenachricht verarbeiten
                }
            }

            @Override
            protected void done() {
                try {
                    removeLastMessage(); // Entfernt den Ladeindikator "Processing..."
                    addMessage("AI", get()); //echte KI-Antwort einfügen
                } catch (Exception e) { //bei keiner erfolgreichen Verbindung/Antwort des LLM
                    addMessage("System", "Error: Connection failed.");
                }
            }
        };
        worker.execute();
    }
    //Nachricht im Fenster hinzufügen:
    private void addMessage(String sender, String message) {
        JPanel row = new JPanel(new BorderLayout());

        // HTML formatiert den Text um --> Zeilenumbruch
        String formattedText = "<html><div style='width: 350px;'><b>" + sender + ":</b><br>" + message.replace("\n", "<br>") + "</div></html>";
        JLabel label = new JLabel(formattedText);
        label.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)); //Rand/Rahmen

        row.add(label, BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate(); //fenstergröße anpassen/aktualisieren

        // Scrollt die Ansicht im fenster automatisch nach unten
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void removeLastMessage() {
        int count = chatPanel.getComponentCount();
        if (count > 0) {
            chatPanel.remove(count - 1); //letzte Nachricht wird gelöscht (bei uns die Prozessing-nachricht)
            chatPanel.revalidate();
            chatPanel.repaint();
        }
    }
}