import de.hm.sysmlaiqa.api.*;
import de.hm.sysmlaiqa.ui.ExplanationDialog;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;

/** Erzeugt die Fensterbilder fürs Handbuch (headless unter Xvfb, mit CATIA-Stubs). */
public class Docs {
    static File out, data;
    static final int W = 802, H = 935;   // Inhalt; + 30 px Titelleiste = 965 wie bisher

    public static void main(String[] a) throws Exception {
        out = new File(a[0]); data = new File(a[1]); out.mkdirs();
        String only = a.length > 2 ? a[2] : "";
        for (String s : new String[]{"start06", "start05", "numbered", "chat", "stop", "suiteRunning", "suiteFinished", "element", "sysml2", "confirm"}) {
            if (!only.isEmpty() && !only.contains(s)) continue;
            Docs.class.getDeclaredMethod(s).invoke(null);
            System.out.println("ok " + s);
        }
        System.exit(0);
    }

    // ───────── Szenen ─────────

    static void start06() throws Exception {
        ExplanationDialog d = open(null);
        save(d, "start-06-plugin-window-model.png", null);
    }

    static void start05() throws Exception {
        ExplanationDialog d = open(null);
        exported(d, "whole model");
        save(d, "start-05-plugin-window.png", null);
    }

    static void numbered() throws Exception {
        ExplanationDialog d = open(focus());
        edt(() -> set(d, "scopeBox", null, "Selected element + surroundings"));
        exported(d, "surroundings of Operating Time [_r1] · relation depth 5 · neighbour depth 3");
        Object[][] m = {
                {1, f(d, "modelBox")}, {2, btn(d, "Refresh")}, {3, f(d, "thinkingBox")}, {4, f(d, "scopeBox")},
                {5, f(d, "useSelectionBtn")}, {6, f(d, "temperatureSpinner")}, {7, f(d, "formatBox")}, {8, f(d, "depthPanel")},
                {9, f(d, "maxTokensSpinner")}, {10, f(d, "cacheModelBox")}, {11, f(d, "groundingBox")}, {12, f(d, "freshChatBox")},
                {13, f(d, "fallbackBox")}, {14, f(d, "mentionFocusBox")}, {15, f(d, "requireIdsBox")}, {16, f(d, "suiteBtn")},
                {17, f(d, "repetitionsSpinner")}, {18, f(d, "exportStatusLabel")}, {19, f(d, "exportBtn")}, {20, btn(d, "Show request…")},
                {21, btn(d, "System prompt…")}, {22, btn(d, "API key…")}, {23, btn(d, "Open results")}, {24, f(d, "quickPanel")},
                {25, f(d, "scrollPane")}, {26, f(d, "inputArea")}, {27, f(d, "sendBtn")}, {28, btn(d, "New chat")},
                {29, divider(d)}, {30, f(d, "liveStatusLabel")}};
        ImageIO.write(render(d, g -> { for (Object[] x : m) marker(g, d, (Component) x[1], (Integer) x[0]); }), "png", new File(out, "window-numbered.png"));
        // Einstellungsbereich allein, nur mit den Nummern 1–23
        BufferedImage only = render(d, g -> { for (Object[] x : m) if ((Integer) x[0] <= 23) marker(g, d, (Component) x[1], (Integer) x[0]); });
        Component settings = ((JComponent) ((JScrollPane) f(d, "topScroll")).getViewport().getView()).getComponent(2);
        Point sp = rel(d, settings);
        ImageIO.write(only.getSubimage(0, 30 + sp.y - 4, W, settings.getHeight() + 6), "png", new File(out, "settings-numbered.png"));
        SwingUtilities.invokeAndWait(d::dispose);
    }

    static void chat() throws Exception {
        ExplanationDialog d = open(null);
        exported(d, "whole model");
        chatQA(d);
        save(d, "chat-answer.png", null);
    }

    static void chatQA(ExplanationDialog d) throws Exception {
        msg(d, "You", "Which component satisfies the requirement \"Operating Time\"? Name each element and the relationship type.");
        msg(d, "System", "Model uploaded to Google for gemini-3.8-flash (valid 60 min). Next questions send only the question – until you click <b>Resend model</b>.");
        msg(d, "AI <span style='color:gray;font-weight:normal'>(gemini-3.8-flash · 1.4 s · 2,412 → 96 tokens (2,290 from cache) · 312 thinking tokens · 2,820 total)</span>",
                "<b>Battery</b> satisfies the requirement <b>Operating Time</b> (id <code>_r1</code>).<ul><li>Relationship: «satisfy» from <i>Battery</i> (<code>_b2</code>) to <i>Operating Time</i></li>"
                        + "<li>The requirement <i>Stable Hover</i> (<code>_r2</code>) is derived from it («deriveReqt»).</li></ul>"
                        + "Note: the satisfy relationship only states that Battery is <i>intended</i> to fulfil the requirement. Whether 5000 mAh are enough for 25 min "
                        + "cannot be decided from the model, because the power consumption of the drone is missing.");
    }

    static void stop() throws Exception {
        ExplanationDialog d = open(null);
        exported(d, "whole model");
        chatQA(d);
        msg(d, "You", "Which requirements are derived from \"Operating Time\"?");
        msg(d, "System", "Waiting for gemini-3.8-flash … (click <b>Stop</b> to cancel)");
        edt(() -> { setF(d, "busy", true); setF(d, "requestRunning", true); call(d, "updateControls"); });
        status(d, "Sending question to gemini-3.8-flash (0.3 KB) ...");
        BufferedImage img = render(d, g -> box(g, d, f(d, "sendBtn"), f(d, "liveStatusLabel")));
        Point sc = rel(d, f(d, "scrollPane"));
        int y0 = 30 + sc.y + ((JComponent) f(d, "scrollPane")).getHeight() - 92;
        ImageIO.write(img.getSubimage(0, y0, W, img.getHeight() - y0), "png", new File(out, "stop-button.png"));
    }

    static void suiteRunning() throws Exception {
        ExplanationDialog d = open(null);
        exported(d, "whole model");
        msg(d, "System", "<b>Test run 20261115-171204 started</b>: 3 cases × 1 model(s) × 1 repetition(s) = 3 requests.<br>Results: C:\\Users\\student\\Documents\\SysML-AI-QA\\runs\\run_20261115-171204.csv");
        msg(d, "Run", "1/3 · A-01 [A] · gemini-3.8-flash · <span style='color:#1E7B34'>ok</span> 1.2 s · model uploaded<br><span style='color:gray'>**Battery** (`_b2`) satisfies the requirement **Operating Time** (`_r1`) via «satisfy» …</span>");
        edt(() -> { setF(d, "busy", true); setF(d, "suiteRunning", true); call(d, "updateControls"); });
        status(d, "gemini-3.8-flash: per-minute rate limit, waiting 21 s (attempt 1/5)");
        save(d, "test-suite-running.png", g -> box(g, d, f(d, "suiteBtn"), f(d, "sendBtn"), f(d, "liveStatusLabel")));
    }

    static void suiteFinished() throws Exception {
        ExplanationDialog d = open(null);
        exported(d, "whole model");
        msg(d, "System", "<b>Test run 20261115-171204 started</b>: 3 cases × 1 model(s) × 1 repetition(s) = 3 requests.<br>Results: C:\\Users\\student\\Documents\\SysML-AI-QA\\runs\\run_20261115-171204.csv");
        msg(d, "Run", "1/3 · A-01 [A] · gemini-3.8-flash · <span style='color:#1E7B34'>ok</span> 1.2 s · model uploaded<br><span style='color:gray'>**Battery** (`_b2`) satisfies the requirement **Operating Time** (`_r1`) via «satisfy» …</span>");
        msg(d, "Run", "2/3 · B-01 [B] · gemini-3.8-flash · <span style='color:#1E7B34'>ok</span> 1.3 s<br><span style='color:gray'>The **Battery** (`_b2`) is a part of the **Drone** (`_b1`), which also contains the motors …</span>");
        msg(d, "Run", "3/3 · D-01 [D] · gemini-3.8-flash · <span style='color:#1E7B34'>ok</span> 1.1 s<br><span style='color:gray'>This **cannot be decided** from the model: the battery capacity is given, but the power consumption …</span>");
        msg(d, "System", "<b>Test run 20261115-171204 finished</b>: 3/3 requests, 3 successful, 0 answer(s) with unknown IDs.<br>Open the results file to rate the answers (columns <i>rating</i> and <i>rating_notes</i>).");
        save(d, "test-suite-finished.png", null);
    }

    static void element() throws Exception {
        ExplanationDialog d = open(focus());
        edt(() -> set(d, "scopeBox", null, "Selected element + surroundings"));
        exported(d, "surroundings of Operating Time [_r1] · relation depth 5 · neighbour depth 3");
        save(d, "window-element.png", null);
        // Schnellfragen-Ausschnitt
        BufferedImage img = ImageIO.read(new File(out, "window-element.png"));
        Point q = rel(d, f(d, "quickPanel"));
        Component qp = f(d, "quickPanel");
        ImageIO.write(img.getSubimage(Math.max(0, q.x - 4), 30 + q.y - 2, Math.min(qp.getWidth() + 8, W - Math.max(0, q.x - 4)), qp.getHeight() + 4), "png", new File(out, "quick-element.png"));
    }

    static void sysml2() throws Exception {
        ExplanationDialog d = open(null);
        edt(() -> {
            setF(d, "loading", true);
            ((JComboBox<?>) f(d, "formatBox")).setSelectedItem("SysML v2 model (CATIA export)");
            setF(d, "lastFormat", "SysML v2 model (CATIA export)");
            setF(d, "loading", false);
            call(d, "updateControls");
        });
        ModelExport ex = ModelExport.fromSysmlFiles(List.of(new File(data, "DroneSystem.sysml")), new File(System.getProperty("java.io.tmpdir")));
        msg(d, "System", "Reading 1 SysML v2 file(s) exported by CATIA …");
        edt(() -> {
            setF(d, "exportedKey", "native");
            setF(d, "modelIndex", ex.index);
            call(d, "refreshQuickQuestions");
            call(d, "applyExport", new Class[]{ModelExport.class, boolean.class}, ex, true);
        });
        exact(d, ex, 412);
        layoutDivider(d);
        save(d, "window-sysml2.png", g -> box(g, d, f(d, "formatBox"), btn(d, "Files…"), btn(d, "Export in CATIA…"), f(d, "exportStatusLabel")));
    }

    static void confirm() throws Exception {
        JOptionPane pane = new JOptionPane("The model has not been exported yet.\nExport now (Whole model, SysML v1 (JSON export)) and then choose the test cases?",
                JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        JDialog dlg = pane.createDialog("Run test suite");
        dlg.setModal(false);
        dlg.pack();
        dlg.setVisible(true);
        Thread.sleep(600);
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            JRootPane rp = dlg.getRootPane();
            BufferedImage b = new BufferedImage(rp.getWidth(), rp.getHeight() + 30, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            titleBar(g, rp.getWidth(), "Run test suite", false);
            g.translate(0, 30);
            rp.printAll(g);
            g.dispose();
            frameBorder(b);
            img[0] = b;
        });
        ImageIO.write(img[0], "png", new File(out, "test-suite-export-confirm.png"));
        dlg.dispose();
    }

    // ───────── Aufbau ─────────

    static ExplanationDialog open(Element focus) throws Exception {
        ExplanationDialog[] d = new ExplanationDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            d[0] = new ExplanationDialog(focus);
            ((JComboBox<?>) f(d[0], "modelBox")).setSelectedItem("gemini-3.8-flash");
            ((JComboBox<?>) f(d[0], "thinkingBox")).setSelectedItem("Model default");
            ((JSpinner) f(d[0], "maxTokensSpinner")).setValue(8192);
            d[0].setSize(W, H);
            d[0].setVisible(true);
        });
        Thread.sleep(700);
        layoutDivider(d[0]);
        return d[0];
    }

    static void layoutDivider(ExplanationDialog d) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            d.validate();
        });
        Thread.sleep(300);
    }

    static void exported(ExplanationDialog d, String scopeDetail) throws Exception {
        msg(d, "System", scopeDetail.startsWith("whole") ? "Exporting the whole model …"
                : "Exporting the surroundings of the selected element (relations 5, neighbours 3) …");
        ModelExport ex = ModelExport.fromFile(new File(data, "sample.json"));
        ex.scopeDetail = scopeDetail;
        edt(() -> {
            setF(d, "baseExport", ex);
            setF(d, "lastScope", ((JComboBox<?>) f(d, "scopeBox")).getSelectedItem());
            setF(d, "exportedKey", call(d, "exportKey"));
            setF(d, "lastFormat", ((JComboBox<?>) f(d, "formatBox")).getSelectedItem());
            setF(d, "modelIndex", ModelIndex.parse(ex.json));
            call(d, "refreshQuickQuestions");
            call(d, "applyExport", new Class[]{ModelExport.class, boolean.class}, ex, true);
        });
        exact(d, ex, 1874);
        layoutDivider(d);
    }

    static void exact(ExplanationDialog d, ModelExport ex, int tokens) throws Exception {
        Thread.sleep(400);
        edt(() -> {
            setF(d, "tokenCountGeneration", 999);   // laufende Zählung verwerfen
            ex.exactTokens = tokens;
            ex.exactTokensModel = "gemini-3.8-flash";
            call(d, "updateExportStatus");
            ((JLabel) f(d, "exportStatusLabel")).setForeground(Color.DARK_GRAY);
            call(d, "updateControls");
        });
    }

    static Element focus() {
        return new NamedElement() {
            public String getName() { return "Operating Time"; }
            public String getQualifiedName() { return "Drone Model::Operating Time"; }
            public String getID() { return "_r1"; }
            public String getHumanType() { return "Requirement"; }
            public String getHumanName() { return "Requirement Operating Time"; }
            public Element getOwner() { return null; }
            public java.util.Collection<Element> getOwnedElement() { return new ArrayList<>(); }
        };
    }

    static void msg(ExplanationDialog d, String who, String html) throws Exception {
        edt(() -> call(d, "addMessage", new Class[]{String.class, String.class}, who, html));
    }

    static void status(ExplanationDialog d, String s) throws Exception {
        call(d, "showLiveStatus", new Class[]{String.class}, s);
        Thread.sleep(300);
        edt(() -> {
            JLabel l = (JLabel) f(d, "liveStatusLabel");
            l.setText(l.getText().replaceFirst("^\\d\\d:\\d\\d:\\d\\d", "17:12:31"));
        });
    }

    // ───────── Bild ─────────

    interface Painter { void paint(Graphics2D g) throws Exception; }

    static void save(ExplanationDialog d, String name, Painter p) throws Exception {
        ImageIO.write(render(d, p), "png", new File(out, name));
        SwingUtilities.invokeAndWait(d::dispose);
    }

    static BufferedImage render(ExplanationDialog d, Painter p) throws Exception {
        Thread.sleep(500);
        scrollDown(d);
        BufferedImage[] r = new BufferedImage[1];
        Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                JRootPane rp = d.getRootPane();
                BufferedImage b = new BufferedImage(W, H + 30, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = b.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                titleBar(g, W, "SysML AI Q&A", true);
                g.translate(0, 30);
                rp.printAll(g);
                if (p != null) p.paint(g);
                g.dispose();
                frameBorder(b);
                r[0] = b;
            } catch (Exception e) { err[0] = e; }
        });
        if (err[0] != null) throw err[0];
        return r[0];
    }

    static void scrollDown(ExplanationDialog d) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JScrollBar sb = ((JScrollPane) f(d, "scrollPane")).getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
        Thread.sleep(300);
    }

    /** Titelleiste im Stil von Windows 11: Titel links, Minimieren, Maximieren, Schließen rechts. */
    static void titleBar(Graphics2D g, int w, String title, boolean allButtons) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, 30);
        g.setColor(new Color(0x20, 0x20, 0x20));
        g.setFont(new Font("Dialog", Font.PLAIN, 13));
        g.drawString(title, 12, 20);
        g.setStroke(new BasicStroke(1f));
        int cx = w - 23;   // Schließen
        g.drawLine(cx - 5, 10, cx + 5, 20);
        g.drawLine(cx + 5, 10, cx - 5, 20);
        if (allButtons) {
            int mx = w - 69;   // Maximieren
            g.drawRect(mx - 5, 10, 10, 10);
            int nx = w - 115;  // Minimieren
            g.drawLine(nx - 5, 15, nx + 5, 15);
        }
        g.setColor(new Color(0xE5, 0xE5, 0xE5));
        g.drawLine(0, 29, w, 29);
    }

    static void frameBorder(BufferedImage b) {
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(0xB0, 0xB0, 0xB0));
        g.drawRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
        g.dispose();
    }

    static void marker(Graphics2D g, ExplanationDialog d, Component c, int n) {
        Point p = rel(d, c);
        int r = 10, x = Math.max(r + 1, p.x + 2), y = Math.max(r + 1, p.y + 2);
        if (n == 25) { y += 16; }
        if (n == 29) { x = 48; y = p.y + c.getHeight() / 2; }
        if (c instanceof JLabel && n == 30) { x = p.x + c.getWidth() - 14; y = p.y + c.getHeight() / 2; }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xC6, 0x28, 0x28));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Dialog", Font.BOLD, 11));
        String s = String.valueOf(n);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, x - fm.stringWidth(s) / 2, y + fm.getAscent() / 2 - 1);
    }

    static void box(Graphics2D g, ExplanationDialog d, Component... cs) {
        g.setColor(new Color(0xC6, 0x28, 0x28));
        g.setStroke(new BasicStroke(2.5f));
        for (Component c : cs) {
            Point p = rel(d, c);
            int w = c.getWidth(), h = c.getHeight();
            if (c instanceof JLabel && ((JLabel) c).getHorizontalAlignment() == SwingConstants.RIGHT) {
                FontMetrics fm = c.getFontMetrics(c.getFont());
                int tw = fm.stringWidth(((JLabel) c).getText());
                p = new Point(p.x + w - tw, p.y); w = tw;
            }
            g.drawRect(p.x - 3, p.y - 3, w + 5, h + 5);
        }
    }

    /** Position relativ zum Inhalt des Fensters (RootPane). */
    static Point rel(ExplanationDialog d, Component c) {
        return SwingUtilities.convertPoint(c.getParent(), c.getLocation(), d.getRootPane());
    }

    static Component divider(ExplanationDialog d) {
        JSplitPane sp = (JSplitPane) f(d, "splitPane");
        Component div = ((javax.swing.plaf.basic.BasicSplitPaneUI) sp.getUI()).getDivider();
        return div;
    }

    static Component btn(Container c, String text) {
        Component r = find(c, text);
        if (r == null) throw new IllegalStateException("button " + text);
        return r;
    }

    static Component find(Container c, String text) {
        for (Component k : c.getComponents()) {
            if (k instanceof AbstractButton && text.equals(((AbstractButton) k).getText()) && k.isShowing()) return k;
            if (k instanceof Container) { Component r = find((Container) k, text); if (r != null) return r; }
        }
        return null;
    }

    // ───────── Reflection ─────────

    interface Act { void run() throws Exception; }

    static void edt(Act a) throws Exception {
        Exception[] e = new Exception[1];
        SwingUtilities.invokeAndWait(() -> { try { a.run(); } catch (Exception x) { e[0] = x; } });
        if (e[0] != null) throw e[0];
        Thread.sleep(150);
    }

    static Component f(Object o, String name) { return (Component) getF(o, name); }

    static Object getF(Object o, String name) {
        try { Field fl = ExplanationDialog.class.getDeclaredField(name); fl.setAccessible(true); return fl.get(o); }
        catch (Exception e) { throw new RuntimeException(name, e); }
    }

    static void setF(Object o, String name, Object v) {
        try { Field fl = ExplanationDialog.class.getDeclaredField(name); fl.setAccessible(true); fl.set(o, v); }
        catch (Exception e) { throw new RuntimeException(name, e); }
    }

    static void set(ExplanationDialog d, String field, Object unused, Object item) { ((JComboBox<?>) f(d, field)).setSelectedItem(item); }

    static Object call(Object o, String name) { return call(o, name, new Class[0]); }

    static Object call(Object o, String name, Class<?>[] types, Object... args) {
        try { Method m = ExplanationDialog.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(o, args); }
        catch (InvocationTargetException e) { throw new RuntimeException(name, e.getCause()); }
        catch (Exception e) { throw new RuntimeException(name, e); }
    }
}
