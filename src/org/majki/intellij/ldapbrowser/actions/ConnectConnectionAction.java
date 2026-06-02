package org.majki.intellij.ldapbrowser.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.ui.treeStructure.Tree;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapServerTreeNode;
import org.majki.intellij.ldapbrowser.toolwindow.LdapTreePanel;

import java.util.Optional;

public class ConnectConnectionAction extends LdapTreeAction {

    public static final String ID = "ldapbrowser.connectConnection";

    @Override
    public void actionPerformed(AnActionEvent e) {
        getTreePanel(e).ifPresent(treePanel -> {
            Tree tree = treePanel.getTree();
            getSelectedNodes(tree)
                .map(this::findLdapServerNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(node -> !node.getConnectionInfo().isOpened())
                .findFirst()
                .ifPresent(treePanel::connectToLdapServer);
        });
    }

    @Override
    public void update(AnActionEvent e) {
        boolean connectable = getTreePanel(e)
            .map(LdapTreePanel::getTree)
            .map(tree -> getSelectedNodes(tree)
                .findFirst()
                .flatMap(this::findLdapServerNode)
                .map(LdapServerTreeNode::getConnectionInfo)
                .map(connectionInfo -> !connectionInfo.isOpened())
                .orElse(false))
            .orElse(false);

        Presentation presentation = e.getPresentation();
        presentation.setIcon(AllIcons.Actions.Execute);
        presentation.setText("Connect");
        presentation.setDescription("Connect to LDAP server");
        presentation.setEnabledAndVisible(connectable);
    }
}
