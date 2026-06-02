package org.majki.intellij.ldapbrowser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.treeStructure.Tree;
import org.majki.intellij.ldapbrowser.ldap.LdapConnectionsService;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapConnectionInfoTreeNode;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapServerTreeNode;
import org.majki.intellij.ldapbrowser.toolwindow.LdapTreePanel;

import javax.swing.tree.TreeNode;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

public abstract class LdapTreeAction extends AnAction {

    protected Stream<LdapConnectionInfoTreeNode> getSelectedNodes(Tree tree) {
        return Arrays.stream(tree.getSelectedNodes(LdapConnectionInfoTreeNode.class, null));
    }

    protected Stream<LdapConnectionInfoTreeNode> getSelectedNodes() {
        return Stream.empty();
    }

    protected Stream<LdapConnectionInfoTreeNode> getSelectedNodes(AnActionEvent event) {
        return getTreePanel(event)
            .map(LdapTreePanel::getTree)
            .map(this::getSelectedNodes)
            .orElseGet(Stream::empty);
    }

    protected Optional<LdapServerTreeNode> findLdapServerNode(LdapConnectionInfoTreeNode selectedNode) {
        LdapConnectionInfoTreeNode currentNode = selectedNode;
        while (currentNode != null) {
            if (currentNode instanceof LdapServerTreeNode) {
                return Optional.of((LdapServerTreeNode) currentNode);
            }
            TreeNode parentNode = currentNode.getParent();
            currentNode = parentNode instanceof LdapConnectionInfoTreeNode ? (LdapConnectionInfoTreeNode) parentNode : null;
        }
        return Optional.empty();
    }

    protected boolean isCloseable(LdapConnectionInfoTreeNode selectedNode) {
        return findLdapServerNode(selectedNode)
            .map(ldapServerTreeNode -> ldapServerTreeNode.getConnectionInfo().isOpened())
            .orElse(false);
    }

    protected Optional<LdapTreePanel> getTreePanel(AnActionEvent event) {
        if (event == null || event.getProject() == null) {
            return Optional.empty();
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(event.getProject()).getToolWindow("LDAP");
        if (toolWindow == null) {
            return Optional.empty();
        }
        Content content = toolWindow.getContentManager().findContent("Connections");
        if (content == null || !(content.getComponent() instanceof LdapTreePanel)) {
            return Optional.empty();
        }
        return Optional.of((LdapTreePanel) content.getComponent());
    }

    protected LdapConnectionsService getConnectionsService() {
        return ApplicationManager.getApplication().getService(LdapConnectionsService.class);
    }

}
