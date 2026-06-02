package org.majki.intellij.ldapbrowser.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.majki.intellij.ldapbrowser.TextBundle;
import org.majki.intellij.ldapbrowser.actions.AddEntryAction;
import org.majki.intellij.ldapbrowser.actions.DeleteEntryAction;
import org.majki.intellij.ldapbrowser.ldap.LdapConnectionsService;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapIconProviderTreeNode;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapRootTreeNode;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapServerTreeNode;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapTreeNode;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

public class LdapTreePanel extends SimpleToolWindowPanel {

    private Tree tree;
    private final Project project;

    public LdapTreePanel(Project project) {
        super(true, true);
        this.project = project;
        initPanel();
    }

    private void invokeRefreshAction() {
        reloadTree();
    }

    private void addActionMenuItem(JBPopupMenu menu, String title, Icon icon, Consumer<LdapTreePanel> action) {
        JBMenuItem menuItem = new JBMenuItem(title, icon);
        menuItem.addActionListener(e -> action.accept(this));
        menu.add(menuItem);
    }

    private void openTreePopupMenu(LdapTreeNode ldapTreeNode, int x, int y) {
        JBPopupMenu menu = new JBPopupMenu(ldapTreeNode.toString());
        addActionMenuItem(menu, "Refresh", AllIcons.Actions.Refresh, panel -> panel.refreshSelectedNodes());
        if (ldapTreeNode.getAllowsChildren()) {
            addActionMenuItem(menu, TextBundle.message("ldapbrowser.new-entry"), PlatformIcons.ADD_ICON, panel -> AddEntryAction.addEntry(panel, ldapTreeNode));
        }
        addActionMenuItem(menu, TextBundle.message("ldapbrowser.delete-entry"), PlatformIcons.DELETE_ICON, panel -> DeleteEntryAction.deleteEntry(panel, ldapTreeNode));
        menu.show(tree, x, y);
    }

    private void openTreePopupMenu(LdapServerTreeNode ldapServerTreeNode, int x, int y) {
        JBPopupMenu menu = new JBPopupMenu(ldapServerTreeNode.toString());
        if (ldapServerTreeNode.getConnectionInfo().isOpened()) {
            JBMenuItem disconnectMenuItem = new JBMenuItem(TextBundle.message("ldapbrowser.disconnect"), AllIcons.Process.Stop);
            disconnectMenuItem.addActionListener(e -> {
                ldapServerTreeNode.getConnectionInfo().disconnect();
                invokeRefreshAction();
                tree.repaint();
            });
            menu.add(disconnectMenuItem);
        } else {
            JBMenuItem connectMenuItem = new JBMenuItem(TextBundle.message("ldapbrowser.connect"), AllIcons.Actions.Execute);
            connectMenuItem.addActionListener(e -> connectToLdapServer(ldapServerTreeNode));
            menu.add(connectMenuItem);
        }

        addActionMenuItem(menu, TextBundle.message("ldapbrowser.refresh"), AllIcons.Actions.Refresh, panel -> panel.reloadTree());
        menu.show(tree, x, y);
    }

    private Object getTreeComponent(int x, int y) {
        int closestRowForLocation = tree.getClosestRowForLocation(x, y);
        if (closestRowForLocation != -1) {
            TreePath pathForRow = tree.getPathForRow(closestRowForLocation);
            return pathForRow.getLastPathComponent();
        }
        return null;
    }

    private void initPanel() {
        addToolbar();

        tree = new Tree(createTreeModel());
        tree.getEmptyText().setText(TextBundle.message("ldapbrowser.no-connections"));
        tree.setRootVisible(false);

        tree.setCellRenderer(new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                append(value.toString());
                if (value instanceof LdapServerTreeNode) {
                    String baseDn = ((LdapServerTreeNode) value).getConnectionInfo().getBaseDn();
                    if (!baseDn.trim().isEmpty()) {
                        append("  (" + baseDn + ")", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    }
                }
                if (value instanceof LdapIconProviderTreeNode) {
                    setIcon(((LdapIconProviderTreeNode) value).getIcon());
                }
            }
        });

        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    LdapTreeNode[] selectedNodes = tree.getSelectedNodes(LdapTreeNode.class, null);
                    if (selectedNodes.length > 0) {
                        FileEditorManager.getInstance(project).openFile(selectedNodes[0].getFile(), true, true);
                    }
                }
            }
        });

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int closestRowForLocation = tree.getClosestRowForLocation(e.getX(), e.getY());
                    if (closestRowForLocation != -1) {
                        TreePath pathForRow = tree.getPathForRow(closestRowForLocation);
                        Object lastPathComponent = pathForRow.getLastPathComponent();
                        if (lastPathComponent instanceof LdapServerTreeNode) {
                            openTreePopupMenu((LdapServerTreeNode) lastPathComponent, e.getX(), e.getY());
                        } else if (lastPathComponent instanceof LdapTreeNode) {
                            openTreePopupMenu((LdapTreeNode) lastPathComponent, e.getX(), e.getY());
                        }
                    }
                } else if (e.getClickCount() > 1) {
                    Object comp = getTreeComponent(e.getX(), e.getY());
                    if (comp instanceof LdapTreeNode) {
                        FileEditorManager.getInstance(project).openFile(((LdapTreeNode) comp).getFile(), true, true);
                    } else if (comp instanceof LdapServerTreeNode && !((LdapServerTreeNode) comp).getConnectionInfo().isOpened()) {
                        connectToLdapServer((LdapServerTreeNode) comp);
                    }
                }
            }
        });


        new TreeSpeedSearch(tree);

        JBScrollPane treeScroll = new JBScrollPane(tree);
        super.setContent(treeScroll);
    }

    public void connectToLdapServer(LdapServerTreeNode node) {
        tree.setPaintBusy(true);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Connecting to LDAP", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                boolean connected = node.getConnectionInfo().connect();
                if (connected) {
                    node.reloadChildren();
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    tree.setPaintBusy(false);
                    if (connected) {
                        ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(node);
                        TreePath path = new TreePath(node.getPath());
                        tree.expandPath(path);
                    }
                });
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                ApplicationManager.getApplication().invokeLater(() -> tree.setPaintBusy(false));
                org.majki.intellij.ldapbrowser.ldap.ui.LdapErrorHandler.handleError(new Exception(error), "Could not connect to LDAP server");
            }
        });
    }

    private TreeNode generateTree() {
        LdapConnectionsService ldapConnectionsService = ApplicationManager.getApplication().getService(LdapConnectionsService.class);
        return new LdapRootTreeNode(ldapConnectionsService.getLdapConnectionInfos());
    }

    private void addToolbar() {
        ActionGroup actionGroup = (ActionGroup) ActionManager.getInstance().getAction("ldapbrowser.actionGroup");
        ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar("qwe", actionGroup, false);
        actionToolbar.setTargetComponent(this);
        actionToolbar.setOrientation(JToolBar.HORIZONTAL);
        Box toolbarBox = Box.createHorizontalBox();
        toolbarBox.add(actionToolbar.getComponent());

        setToolbar(toolbarBox);
        actionToolbar.getComponent().setVisible(true);
    }

    public Tree getTree() {
        return tree;
    }

    public boolean selectDn(String dn) {
        if (dn == null || dn.trim().isEmpty()) {
            return false;
        }
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            if (path == null) {
                continue;
            }
            Object component = path.getLastPathComponent();
            if (component instanceof LdapTreeNode && dn.equalsIgnoreCase(((LdapTreeNode) component).getLdapNode().getDn())) {
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                return true;
            }
        }
        return false;
    }

    private DefaultTreeModel createTreeModel() {
        return new DefaultTreeModel(generateTree());
    }

    public void reloadTree() {
        tree.setModel(createTreeModel());
    }

    public void refreshSelectedNodes() {
        LdapTreeNode[] selectedTreeNodes = tree.getSelectedNodes(LdapTreeNode.class, null);
        LdapServerTreeNode[] selectedServerTreeNodes = tree.getSelectedNodes(LdapServerTreeNode.class, null);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Refreshing LDAP", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (selectedTreeNodes.length > 0) {
                    for (LdapTreeNode selectedNode : selectedTreeNodes) {
                        try {
                            selectedNode.getLdapNode().refreshWithChildren();
                            ApplicationManager.getApplication().invokeLater(() -> ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(selectedNode));
                        } catch (Exception e) {
                            org.majki.intellij.ldapbrowser.ldap.ui.LdapErrorHandler.handleError(e, "Could not refresh node");
                        }
                    }
                }

                if (selectedServerTreeNodes.length > 0) {
                    ApplicationManager.getApplication().invokeLater(() -> reloadTree());
                }
            }
        });
    }

}

