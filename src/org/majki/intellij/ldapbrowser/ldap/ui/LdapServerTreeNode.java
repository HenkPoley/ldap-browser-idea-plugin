package org.majki.intellij.ldapbrowser.ldap.ui;

import com.intellij.openapi.util.IconLoader;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.jetbrains.annotations.Nullable;
import org.majki.intellij.ldapbrowser.ldap.LdapConnectionInfo;
import org.majki.intellij.ldapbrowser.ldap.LdapNode;

import javax.swing.*;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

public class LdapServerTreeNode extends LdapConnectionInfoTreeNode {

    private static final TreeNode NOT_CONNECTED_NODE = new LdapPlaceholderTreeNode("Not connected.");
    private List<TreeNode> children;

    LdapServerTreeNode(LdapConnectionInfo info, MutableTreeNode parent) {
        super(info, parent);
        this.children = null;
    }

    private void createChildren() {
        children = new ArrayList<>();
        try {
            LdapNode root = LdapNode.createRoot(getConnectionInfo(), getConnectionInfo().getBaseDn());
            children.addAll(root.getChildren().stream().map(ldapNode -> new LdapTreeNode(getConnectionInfo(), this, ldapNode)).collect(Collectors.toList()));
            if (root.isChildrenTruncated()) {
                children.add(new LdapPlaceholderTreeNode("More than " + root.getMaxChildrenPerNode() + " entries found. Set a narrower Base DN."));
            } else if (children.isEmpty()) {
                String baseDn = getConnectionInfo().getBaseDn();
                children.add(new LdapPlaceholderTreeNode("No child entries found under " + (baseDn.trim().isEmpty() ? "Root DSE" : baseDn) + ". Check Base DN, permissions, or use a search query."));
            }
        } catch (LdapException e) {
            LdapErrorHandler.handleError(e, "Could not create server child nodes");
        }
    }

    private List<TreeNode> getChildren() {
        if (children == null) {
            createChildren();
        }
        return children;
    }

    public void reloadChildren() {
        children = null;
        getChildren();
    }

    @Override
    public TreeNode getChildAt(int childIndex) {
        if (getConnectionInfo().isOpened()) {
            List<TreeNode> currentChildren = getChildren();
            if (childIndex >= 0 && childIndex < currentChildren.size()) {
                return currentChildren.get(childIndex);
            }
        } else {
            return NOT_CONNECTED_NODE;
        }
        return new LdapPlaceholderTreeNode("Tree model changed. Refresh this LDAP connection.");
    }

    @Override
    public int getChildCount() {
        if (getConnectionInfo().isOpened()) {
            return getChildren().size();
        } else {
            return 0;
        }
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public int getIndex(TreeNode node) {
        if (getConnectionInfo().isOpened()) {
            return children.indexOf(node);
        } else {
            return 0;
        }
    }

    @Override
    public boolean getAllowsChildren() {
        return getConnectionInfo().isOpened();
    }

    @Override
    public boolean isLeaf() {
        return !getConnectionInfo().isOpened();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Enumeration<TreeNode> children() {
        return Collections.enumeration(getChildren());
    }

    @Nullable
    @Override
    public Icon getIcon() {
        return IconLoader.getIcon("/images/server.png");
    }

    @Override
    public String toString() {
        return getConnectionInfo().getName();
    }
}
