package org.majki.intellij.ldapbrowser.ldap.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.entry.DefaultModification;
import org.apache.directory.api.ldap.model.entry.Modification;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.ldap.client.template.exception.LdapRuntimeException;
import org.majki.intellij.ldapbrowser.ldap.LdapAttribute;
import org.majki.intellij.ldapbrowser.ldap.LdapNode;

import javax.swing.*;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class LdapAttributeTableWrapper {

    private static final Logger LOGGER = Logger.getInstance(LdapAttributeTableWrapper.class);

    private LdapAttributeTableModel model;
    private JTable table;
    private LdapNode ldapNode;
    private boolean directEdit;
    private Project project;

    public LdapAttributeTableWrapper(JTable table, LdapNode node) {
        this(null, table, node, true);
    }

    public LdapAttributeTableWrapper(Project project, JTable table, LdapNode node) {
        this(project, table, node, true);
    }

    public LdapAttributeTableWrapper(JTable table, LdapNode node, boolean directEdit) {
        this(null, table, node, directEdit);
    }

    public LdapAttributeTableWrapper(Project project, JTable table, LdapNode node, boolean directEdit) {
        this.project = project;
        this.table = table;
        this.ldapNode = node;
        this.directEdit = directEdit;
        configure();
    }

    private void configure() {

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setAutoCreateColumnsFromModel(true);

        if (table instanceof JBTable) {
            ((JBTable) table).getEmptyText().setText("No attributes");
            ((JBTable) table).setShowColumns(true);
            ((JBTable) table).setEnableAntialiasing(true);
        }

        model = new LdapAttributeTableModel(ldapNode);
        table.setModel(model);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (isCtrlReferenceClick(e)) {
                    if (table.isEditing()) {
                        table.getCellEditor().cancelCellEditing();
                    }
                    e.consume();
                    openReferencedEntry(table.rowAtPoint(e.getPoint()));
                }
            }
        });

        table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setRowHeight(24);

        TableColumn attributeColumn = table.getColumn(LdapAttributeTableModel.COLUMN_NAMES[0]);
        attributeColumn.setResizable(true);
        attributeColumn.setWidth(300);
        attributeColumn.setMinWidth(120);
        attributeColumn.setMaxWidth(400);
        TableCellRenderer objectClassBoldTableCellRenderer = (table1, value, isSelected, hasFocus, row, column) -> {
            JBLabel label = new JBLabel();

            if (isSelected) {
                label.setOpaque(true);
                if (hasFocus) {
                    label.setBackground(table1.getSelectionBackground());
                } else {
                    label.setBackground(UIUtil.getListUnfocusedSelectionBackground());
                }
                label.setForeground(table1.getSelectionForeground());
            } else {
                label.setBackground(table1.getBackground());
                label.setForeground(table1.getForeground());
            }

            UIUtil.addBorder(label, JBUI.Borders.empty(1, 8));

            LdapAttributeTableModel model1 = (LdapAttributeTableModel) table1.getModel();
            String attributeName = (String) model1.getValueAt(row, 0);

            if (value instanceof String) {
                label.setText((String) value);
                if (LdapNode.OBJECTCLASS_ATTRIBUTE_NAME.equalsIgnoreCase(attributeName)) {
                    Font font = new Font(label.getFont().getName(), Font.BOLD, label.getFont().getSize());
                    label.setFont(font);
                }
            } else {
                label.setForeground(UIUtil.getInactiveTextColor());
                label.setText("Empty value");
            }
            return label;
        };
        attributeColumn.setCellRenderer(objectClassBoldTableCellRenderer);

        TableColumn valueColumn = table.getColumn(LdapAttributeTableModel.COLUMN_NAMES[1]);
        valueColumn.setResizable(true);
        valueColumn.setCellRenderer(objectClassBoldTableCellRenderer);
        LdapTableCellEditor ldapTableCellEditor = new LdapTableCellEditor(ldapNode, table);
        ldapTableCellEditor.addCellEditorListener(new CellEditorListener() {
            @Override
            public void editingStopped(ChangeEvent e) {
                LdapTableCellEditor editor = (LdapTableCellEditor) e.getSource();
                LdapAttributeTableModel model = (LdapAttributeTableModel) table.getModel();

                if (directEdit) {
                    int selectedRow = table.getSelectedRow();
                    if (selectedRow < 0) {
                        return;
                    }
                    String newValue = (String) editor.getCellEditorValue();
                    LdapAttributeTableModel.Item selectedItem = model.getItems().get(table.convertRowIndexToModel(selectedRow));
                    String oldValue = selectedItem.getValue().asString();

                    if (!oldValue.equals(newValue)) {

                        String attributeName = selectedItem.getAttribute().name();
                        LdapAttribute attribute = ldapNode.getAttributeByName(attributeName)
                            .orElseThrow(() -> new LdapRuntimeException(new LdapException("Could not get attribute by name: " + attributeName)));
                        Set<String> values = attribute.values().stream().map(LdapAttribute.Value::asString).collect(Collectors.toSet());
                        values.remove(oldValue);
                        values.add(newValue);

                        Modification modification = new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, attributeName, values.toArray(new String[0]));
                        try {
                            ldapNode.getConnection().modify(ldapNode.getDn(), modification);

                            ldapNode.refresh();
                            ((LdapAttributeTableModel) table.getModel()).refresh();
                        } catch (LdapException e1) {
                            LdapErrorHandler.handleError(e1, "Could not modify attribute");
                        }
                    }
                } else {
                    int selectedRow = table.getSelectedRow();
                    if (selectedRow < 0) {
                        return;
                    }
                    LdapAttributeTableModel.Item selectedItem = model.getItems().get(table.convertRowIndexToModel(selectedRow));

                    ((LdapAttributeTableModel) table.getModel()).refresh();
                    LdapNode.valueModifier(selectedItem.getValue(), (String) editor.getCellEditorValue());
                }
            }

            @Override
            public void editingCanceled(ChangeEvent e) {

            }
        });
        valueColumn.setCellEditor(ldapTableCellEditor);

    }

    private boolean isCtrlReferenceClick(MouseEvent e) {
        return project != null
            && SwingUtilities.isLeftMouseButton(e)
            && (e.isControlDown() || e.isMetaDown())
            && table.rowAtPoint(e.getPoint()) >= 0;
    }

    private void openReferencedEntry(int viewRow) {
        int modelRow = table.convertRowIndexToModel(viewRow);
        LdapAttributeTableModel.Item item = model.getItems().get(modelRow);
        String attributeName = item.getAttribute().name();
        String dn = item.getValue().asString();
        if (!isDnReferenceAttribute(attributeName) || dn == null || dn.trim().isEmpty()) {
            return;
        }
        LOGGER.info("LDAP editor navigation: resolving " + attributeName + " DN=\"" + dn + "\"");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Opening LDAP Reference", false) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    List<LdapNode> results = LdapNode.search(ldapNode.getLdapConnectionInfo(), dn, "(objectClass=*)", SearchScope.OBJECT, 1);
                    if (results.isEmpty()) {
                        LOGGER.warn("LDAP editor navigation: no entry found for DN=\"" + dn + "\"");
                        return;
                    }
                    LdapTreeNode treeNode = LdapTreeNode.searchResult(ldapNode.getLdapConnectionInfo(), results.get(0));
                    ApplicationManager.getApplication().invokeLater(() ->
                        FileEditorManager.getInstance(project).openFile(treeNode.getFile(), true, true));
                } catch (Exception exception) {
                    LOGGER.warn("LDAP editor navigation: could not open referenced DN=\"" + dn + "\": " + rootCauseMessage(exception), exception);
                }
            }
        });
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        return rootCause.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private boolean isDnReferenceAttribute(String attributeName) {
        return LdapAttributeTableModel.isDnReferenceAttribute(attributeName);
    }

    public JTable getTable() {
        return table;
    }

    public LdapAttributeTableModel getModel() {
        return model;
    }

    public LdapNode getLdapNode() {
        return ldapNode;
    }
}
