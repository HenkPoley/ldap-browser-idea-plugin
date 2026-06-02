package org.majki.intellij.ldapbrowser.dialog;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.majki.intellij.ldapbrowser.ldap.LdapConnectionInfo;
import org.majki.intellij.ldapbrowser.ldap.LdapNode;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapTreeNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class LdapSearchDialog extends DialogWrapper {

    private final Project project;
    private final LdapConnectionInfo connectionInfo;
    private final JTextField baseDnField;
    private final JTextField filterField;
    private final JComboBox<SearchScope> scopeComboBox;
    private final JSpinner limitSpinner;
    private final DefaultListModel<LdapNode> resultModel;
    private final JBList<LdapNode> resultList;
    private final JButton searchButton;
    private final JLabel statusLabel;

    public LdapSearchDialog(@Nullable Project project, LdapConnectionInfo connectionInfo) {
        super(project);
        this.project = project;
        this.connectionInfo = connectionInfo;
        this.baseDnField = new JTextField(connectionInfo.getBaseDn());
        this.filterField = new JTextField("(objectClass=*)");
        this.scopeComboBox = new JComboBox<>(new SearchScope[]{SearchScope.SUBTREE, SearchScope.ONELEVEL, SearchScope.OBJECT});
        this.scopeComboBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(scopeLabel(value));
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(JBUI.Borders.empty(2, 4));
            return label;
        });
        this.limitSpinner = new JSpinner(new SpinnerNumberModel(500, 1, 10000, 100));
        this.resultModel = new DefaultListModel<>();
        this.resultList = new JBList<>(resultModel);
        this.searchButton = new JButton("Search");
        this.statusLabel = new JLabel(" ");

        setTitle("LDAP Search - " + connectionInfo.getName());
        init();
    }

    private String scopeLabel(SearchScope scope) {
        if (scope == SearchScope.SUBTREE) {
            return "Subtree (base and all descendants)";
        }
        if (scope == SearchScope.ONELEVEL) {
            return "One level (immediate children only)";
        }
        if (scope == SearchScope.OBJECT) {
            return "Base object only";
        }
        return String.valueOf(scope);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(8)));
        panel.setPreferredSize(JBUI.size(760, 480));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = JBUI.insets(2);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridy = 0;
        addRow(form, constraints, "Base DN", baseDnField);
        addRow(form, constraints, "Filter", filterField);
        addRow(form, constraints, "Scope", scopeComboBox);
        addRow(form, constraints, "Limit", limitSpinner);

        searchButton.addActionListener(e -> runSearch());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(searchButton);
        actions.add(Box.createHorizontalStrut(JBUI.scale(8)));
        actions.add(statusLabel);

        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.CENTER);
        top.add(actions, BorderLayout.SOUTH);

        resultList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value.getDn());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(JBUI.Borders.empty(2, 4));
            return label;
        });
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() > 1) {
                    openSelectedResult();
                }
            }
        });

        panel.add(top, BorderLayout.NORTH);
        panel.add(new JBScrollPane(resultList), BorderLayout.CENTER);
        return panel;
    }

    private void addRow(JPanel form, GridBagConstraints constraints, String label, JComponent component) {
        constraints.gridx = 0;
        constraints.weightx = 0;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        form.add(component, constraints);
        constraints.gridy++;
    }

    private void runSearch() {
        searchButton.setEnabled(false);
        statusLabel.setText("Searching...");
        resultModel.clear();

        String baseDn = baseDnField.getText();
        String filter = filterField.getText();
        SearchScope scope = (SearchScope) scopeComboBox.getSelectedItem();
        int limit = (Integer) limitSpinner.getValue();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Searching LDAP", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    List<LdapNode> results = LdapNode.search(connectionInfo, baseDn, filter, scope, limit);
                    ApplicationManager.getApplication().invokeLater(() -> showResults(results));
                } catch (LdapException e) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        searchButton.setEnabled(true);
                        statusLabel.setText("Search failed");
                        Messages.showErrorDialog(errorMessage(e), "LDAP Search Failed");
                    });
                }
            }
        });
    }

    private String errorMessage(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String detail = rootCause.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = throwable.getMessage();
        }
        return throwable.getMessage() + "\n\nRoot cause: "
            + rootCause.getClass().getSimpleName()
            + (detail == null || detail.trim().isEmpty() ? "" : "\n" + detail);
    }

    private void showResults(List<LdapNode> results) {
        results.forEach(resultModel::addElement);
        statusLabel.setText(results.size() + " result" + (results.size() == 1 ? "" : "s"));
        searchButton.setEnabled(true);
    }

    private void openSelectedResult() {
        LdapNode selected = resultList.getSelectedValue();
        if (selected != null && project != null) {
            LdapTreeNode treeNode = LdapTreeNode.searchResult(connectionInfo, selected);
            FileEditorManager.getInstance(project).openFile(treeNode.getFile(), true, true);
        }
    }
}
