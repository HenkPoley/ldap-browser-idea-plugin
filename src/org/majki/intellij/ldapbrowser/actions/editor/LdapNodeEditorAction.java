package org.majki.intellij.ldapbrowser.actions.editor;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditor;
import org.majki.intellij.ldapbrowser.editor.LdapNodeEditor;

import java.util.Optional;

public abstract class LdapNodeEditorAction extends AnAction {

    protected Optional<LdapNodeEditor> getNodeEditor(AnActionEvent event) {
        if (event.getProject() != null) {
            FileEditor fileEditor = FileEditorManager.getInstance(event.getProject()).getSelectedEditor();
            if (fileEditor instanceof LdapNodeEditor) {
                return Optional.of((LdapNodeEditor) fileEditor);
            }
        }
        return Optional.empty();
    }

}
