package de.hm.sysmlaiqa.ui;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Auswahl von Paketen oder Elementen für den Export (FR1.2).
 * Zeigt den Modellbaum (ohne SysML/UML-Bibliotheken); Mehrfachauswahl mit Strg/Umschalt.
 * Jedes ausgewählte Paket/Element wird mit allem, was es enthält, exportiert.
 */
public class ElementPickerDialog extends JDialog {

    private final JTree tree;
    private List<Element> result;

    private ElementPickerDialog(Window owner, Project project, Collection<Element> preselected) {
        super(owner, "Choose packages or elements to send", ModalityType.APPLICATION_MODAL);
        setSize(520, 620);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(6, 6));

        Element rootEl = project == null ? null : project.getPrimaryModel();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootEl);
        addChildren(root);
        DefaultTreeModel model = new DefaultTreeModel(root);
        tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
                Object u = ((DefaultMutableTreeNode) value).getUserObject();
                if (u instanceof Element) setText(label((Element) u));
                return this;
            }
        });
        // Kinder erst beim Aufklappen laden (große Modelle)
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.getPath().getLastPathComponent();
                for (int i = 0; i < n.getChildCount(); i++) {
                    DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(i);
                    if (c.getChildCount() == 0) addChildren(c);
                }
                model.nodeStructureChanged(n);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) { }
        });
        for (int i = 0; i < root.getChildCount(); i++) addChildren((DefaultMutableTreeNode) root.getChildAt(i));
        model.reload();
        preselect(root, preselected);

        JLabel hint = new JLabel("<html>Select one or more packages or elements (Ctrl+click for several).<br>"
                + "Each one is sent with everything it contains. Relations to elements outside the selection keep their ids.</html>");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        add(hint, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10), sp.getBorder()));
        add(sp, BorderLayout.CENTER);

        JButton ok = new JButton("Use selection");
        ok.addActionListener(e -> {
            List<Element> sel = selected();
            if (sel.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Select at least one package or element.", getTitle(), JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            result = sel;
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
    }

    /**
     * Öffnet die Auswahl.
     * @return gewählte Elemente oder null bei Abbruch
     */
    public static List<Element> choose(Window owner, Project project, Collection<Element> preselected) {
        ElementPickerDialog d = new ElementPickerDialog(owner, project, preselected);
        d.setVisible(true);
        return d.result;
    }

    private static void addChildren(DefaultMutableTreeNode node) {
        Object u = node.getUserObject();
        if (!(u instanceof Element)) return;
        Collection<Element> owned;
        try {
            owned = ((Element) u).getOwnedElement();
        } catch (Throwable t) {
            return;
        }
        if (owned == null) return;
        List<Element> sorted = new ArrayList<>();
        for (Element c : owned) if (isShown(c)) sorted.add(c);
        sorted.sort((a, b) -> {
            int pa = isPackage(a) ? 0 : 1, pb = isPackage(b) ? 0 : 1;
            return pa != pb ? pa - pb : label(a).compareToIgnoreCase(label(b));
        });
        for (Element c : sorted) node.add(new DefaultMutableTreeNode(c));
    }

    /** Nur benannte Modellelemente, keine Bibliotheken/Profile (wie der Export). */
    private static boolean isShown(Element e) {
        if (!(e instanceof NamedElement)) return false;
        NamedElement n = (NamedElement) e;
        String name = n.getName();
        if (name == null || name.isEmpty()) return false;
        String q = n.getQualifiedName() == null ? "" : n.getQualifiedName();
        for (String lib : new String[]{"SysML", "UML", "Simulation Profile", "Metric Suite", "MD Customization",
                "StandardProfile", "PrimitiveTypes", "Requirement Profile", "Cameo"}) {
            if (q.startsWith(lib)) return false;
        }
        String type = e.getHumanType() == null ? "" : e.getHumanType();
        return !type.contains("Diagram") && !type.contains("Table") && !type.contains("Matrix")
                && !type.equals("Comment") && !type.contains("Stereotype");
    }

    private static boolean isPackage(Element e) {
        String t = e.getHumanType() == null ? "" : e.getHumanType();
        return t.equals("Package") || t.equals("Model") || t.equals("Profile");
    }

    static String label(Element e) {
        String n = e instanceof NamedElement ? ((NamedElement) e).getName() : null;
        return (n == null || n.isEmpty() ? "(unnamed)" : n) + "  [" + e.getHumanType() + "]";
    }

    private List<Element> selected() {
        List<Element> l = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return l;
        for (TreePath p : paths) {
            Object u = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (u instanceof Element) l.add((Element) u);
        }
        return l;
    }

    private void preselect(DefaultMutableTreeNode root, Collection<Element> pre) {
        if (pre == null || pre.isEmpty()) return;
        Set<Element> want = new HashSet<>(pre);
        List<TreePath> paths = new ArrayList<>();
        for (Element e : want) {
            // Pfad über die Besitzer aufbauen und dabei die Knoten nachladen
            List<Element> chain = new ArrayList<>();
            for (Element o = e; o != null && o != root.getUserObject(); o = o.getOwner()) chain.add(0, o);
            DefaultMutableTreeNode cur = root;
            boolean ok = true;
            for (Element step : chain) {
                if (cur.getChildCount() == 0) addChildren(cur);
                DefaultMutableTreeNode next = null;
                for (int i = 0; i < cur.getChildCount(); i++) {
                    DefaultMutableTreeNode c = (DefaultMutableTreeNode) cur.getChildAt(i);
                    if (c.getUserObject() == step) { next = c; break; }
                }
                if (next == null) { ok = false; break; }
                cur = next;
            }
            if (ok && cur != root) paths.add(new TreePath(cur.getPath()));
        }
        ((DefaultTreeModel) tree.getModel()).reload();
        for (TreePath p : paths) tree.expandPath(p.getParentPath());
        tree.setSelectionPaths(paths.toArray(new TreePath[0]));
        if (!paths.isEmpty()) tree.scrollPathToVisible(paths.get(0));
    }
}
