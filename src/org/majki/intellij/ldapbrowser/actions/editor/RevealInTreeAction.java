package org.majki.intellij.ldapbrowser.actions.editor;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.majki.intellij.ldapbrowser.toolwindow.LdapTreePanel;

public class RevealInTreeAction extends LdapNodeEditorAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        getNodeEditor(e).ifPresent(editor -> {
            if (e.getProject() == null) {
                return;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(e.getProject()).getToolWindow("LDAP");
            if (toolWindow == null) {
                return;
            }
            toolWindow.show();
            Content content = toolWindow.getContentManager().findContent("Connections");
            if (content != null && content.getComponent() instanceof LdapTreePanel) {
                ((LdapTreePanel) content.getComponent()).selectDn(editor.getVirtualFile().getLdapTreeNode().getLdapNode().getDn());
            }
        });
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setIcon(AllIcons.General.Locate);
        e.getPresentation().setText("Reveal in LDAP Tree");
        e.getPresentation().setEnabled(getNodeEditor(e).isPresent());
    }
}
