package org.majki.intellij.ldapbrowser.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import org.majki.intellij.ldapbrowser.dialog.LdapSearchDialog;
import org.majki.intellij.ldapbrowser.ldap.LdapConnectionInfo;
import org.majki.intellij.ldapbrowser.ldap.ui.LdapConnectionInfoTreeNode;

import java.util.Optional;

public class SearchAction extends LdapTreeAction {

    public static final String ID = "ldapbrowser.search";

    @Override
    public void actionPerformed(AnActionEvent e) {
        selectedOpenConnection(e)
            .ifPresent(connectionInfo -> new LdapSearchDialog(e.getProject(), connectionInfo).show());
    }

    @Override
    public void update(AnActionEvent e) {
        boolean canSearch = selectedOpenConnection(e).isPresent();
        Presentation presentation = e.getPresentation();
        presentation.setIcon(AllIcons.Actions.Search);
        presentation.setEnabled(canSearch);
    }

    private Optional<LdapConnectionInfo> selectedOpenConnection(AnActionEvent event) {
        return getSelectedNodes(event)
            .findFirst()
            .map(this::connectionInfo)
            .filter(LdapConnectionInfo::isOpened);
    }

    private LdapConnectionInfo connectionInfo(LdapConnectionInfoTreeNode treeNode) {
        return findLdapServerNode(treeNode)
            .map(LdapConnectionInfoTreeNode::getConnectionInfo)
            .orElseGet(treeNode::getConnectionInfo);
    }
}
